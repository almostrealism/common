/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Tests that a GPU memory provider reclaims unreachable allocations when an allocation would
 * exceed its ceiling, instead of rejecting the allocation while the ceiling is filled with
 * garbage that has simply not been collected yet, and that its usage accounting stays exact
 * when allocations race each other.
 */
public class MemoryReclaimTest extends TestSuiteBase {
	/** Elements per allocated block. */
	private static final int BLOCK_ELEMENTS = 4 * 1024 * 1024;

	/** Bytes per allocated block, for 4-byte elements. */
	private static final long BLOCK_BYTES = 4L * BLOCK_ELEMENTS;

	/** Number of blocks that fit under the ceiling of the providers under test. */
	private static final int BLOCKS_PER_CEILING = 4;

	/** Memory ceiling of the providers under test, in bytes. */
	private static final long CEILING = BLOCKS_PER_CEILING * BLOCK_BYTES;

	/**
	 * Allocating and discarding many Metal blocks whose total far exceeds a small ceiling
	 * succeeds, because each allocation that would exceed the ceiling first reclaims the
	 * discarded blocks.
	 */
	@Test(timeout = 120000)
	public void discardedAllocationsAreReclaimed() {
		MetalDataContext context = dataContext(ComputeRequirement.MTL, MetalDataContext.class);
		Assume.assumeTrue("requires the Metal backend", context != null);

		MetalMemoryProvider provider = new MetalMemoryProvider(context, 4, CEILING);
		assertDiscardedReclaimed(provider::allocate, provider::getAllocatedMemory);
	}

	/**
	 * Allocating and discarding many OpenCL blocks whose total far exceeds a small ceiling
	 * succeeds, because each allocation that would exceed the ceiling first reclaims the
	 * discarded blocks.
	 */
	@Test(timeout = 120000)
	public void discardedClAllocationsAreReclaimed() {
		CLMemoryProvider provider = clProvider();
		assertDiscardedReclaimed(provider::allocate, provider::getAllocatedMemory);
	}

	/**
	 * Concurrent Metal allocations that are all held admit exactly as many blocks as fit under
	 * the ceiling, and the provider's usage equals the bytes actually held.
	 */
	@Test(timeout = 120000)
	public void concurrentAllocationsRespectCeiling() throws InterruptedException {
		MetalDataContext context = dataContext(ComputeRequirement.MTL, MetalDataContext.class);
		Assume.assumeTrue("requires the Metal backend", context != null);

		MetalMemoryProvider provider = new MetalMemoryProvider(context, 4, CEILING);
		assertConcurrentCeiling(provider::allocate, provider::getAllocatedMemory);
	}

	/**
	 * Concurrent OpenCL allocations that are all held admit exactly as many blocks as fit under
	 * the ceiling, and the provider's usage equals the bytes actually held.
	 */
	@Test(timeout = 120000)
	public void concurrentClAllocationsRespectCeiling() throws InterruptedException {
		CLMemoryProvider provider = clProvider();
		assertConcurrentCeiling(provider::allocate, provider::getAllocatedMemory);
	}

	/**
	 * Allocates and discards six ceilings' worth of blocks, checking after every allocation that
	 * the provider's usage never exceeds the ceiling.
	 *
	 * @param allocate  allocates a block of the given number of elements
	 * @param allocated the provider's current usage in bytes
	 */
	private void assertDiscardedReclaimed(IntFunction<?> allocate, LongSupplier allocated) {
		for (int i = 0; i < 6 * BLOCKS_PER_CEILING; i++) {
			Object block = allocate.apply(BLOCK_ELEMENTS);
			Assert.assertNotNull(block);
			Assert.assertTrue(allocated.getAsLong() <= CEILING);
		}

		log("allocatedBytes=" + allocated.getAsLong() + " ceiling=" + CEILING);
	}

	/**
	 * Starts twice as many threads as there are blocks under the ceiling, releases them together,
	 * and has each allocate and hold one block. Exactly {@link #BLOCKS_PER_CEILING} allocations
	 * must succeed, the rest must be rejected, and the provider's usage must equal the held bytes.
	 *
	 * @param allocate  allocates a block of the given number of elements
	 * @param allocated the provider's current usage in bytes
	 */
	private void assertConcurrentCeiling(IntFunction<?> allocate, LongSupplier allocated)
			throws InterruptedException {
		int threads = 2 * BLOCKS_PER_CEILING;
		List<Object> held = Collections.synchronizedList(new ArrayList<>());
		List<HardwareException> rejected = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);

		try {
			for (int i = 0; i < threads; i++) {
				executor.execute(() -> {
					try {
						start.await();
						held.add(allocate.apply(BLOCK_ELEMENTS));
					} catch (HardwareException e) {
						rejected.add(e);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
			}

			start.countDown();
			executor.shutdown();
			Assert.assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}

		Assert.assertEquals(BLOCKS_PER_CEILING, held.size());
		Assert.assertEquals(threads - BLOCKS_PER_CEILING, rejected.size());
		Assert.assertEquals(BLOCKS_PER_CEILING * BLOCK_BYTES, allocated.getAsLong());
	}

	/**
	 * Creates an OpenCL memory provider with the test ceiling, skipping the test when no OpenCL
	 * backend is available. Only allocation is exercised, so no command queue is needed.
	 *
	 * @return the provider
	 */
	private CLMemoryProvider clProvider() {
		CLDataContext context = dataContext(ComputeRequirement.CL, CLDataContext.class);
		Assume.assumeTrue("requires the OpenCL backend", context != null);
		return new CLMemoryProvider(context, null, 4, CEILING, CLMemoryProvider.Location.DEVICE);
	}

	/**
	 * Returns the data context of the given type for a compute requirement, or null when no such
	 * backend is available.
	 *
	 * @param requirement the backend to look up
	 * @param type        the expected data context type
	 * @param <D>         the data context type
	 * @return the data context, or null
	 */
	private <D> D dataContext(ComputeRequirement requirement, Class<D> type) {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, requirement).stream()
					.map(c -> c.getDataContext())
					.filter(type::isInstance)
					.map(type::cast)
					.findFirst().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
