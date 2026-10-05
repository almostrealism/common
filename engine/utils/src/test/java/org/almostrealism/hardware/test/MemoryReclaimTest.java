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

import io.almostrealism.code.Precision;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.metal.MTLBuffer;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.jocl.cl_mem;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
	 * A Metal allocation larger than the whole ceiling is rejected at once, without the
	 * collections and waits of reclaiming, which could never make it fit.
	 */
	@Test(timeout = 120000)
	public void oversizedAllocationRejectedWithoutReclaim() {
		MetalDataContext context = dataContext(ComputeRequirement.MTL, MetalDataContext.class);
		Assume.assumeTrue("requires the Metal backend", context != null);

		MetalMemoryProvider provider = new MetalMemoryProvider(context, 4, CEILING);
		assertOversizedRejected(provider::allocate, provider::getAllocatedMemory);
	}

	/**
	 * An OpenCL allocation larger than the whole ceiling is rejected at once, without the
	 * collections and waits of reclaiming, which could never make it fit.
	 */
	@Test(timeout = 120000)
	public void oversizedClAllocationRejectedWithoutReclaim() {
		CLMemoryProvider provider = clProvider();
		assertOversizedRejected(provider::allocate, provider::getAllocatedMemory);
	}

	/**
	 * A Metal allocation whose native buffer is created just before the provider is destroyed
	 * is refused at registration, and that refusal releases the native buffer and returns its
	 * reservation, so neither the buffer nor its bytes stay charged against the ceiling.
	 */
	@Test(timeout = 60000)
	public void destroyDuringMetalAllocationReleasesBuffer() {
		MetalDataContext context = dataContext(ComputeRequirement.MTL, MetalDataContext.class);
		Assume.assumeTrue("requires the Metal backend", context != null);

		AtomicReference<MTLBuffer> created = new AtomicReference<>();
		MetalMemoryProvider provider = new MetalMemoryProvider(context, 4, CEILING) {
			@Override
			protected MTLBuffer buffer(int len) {
				MTLBuffer buffer = super.buffer(len);
				created.set(buffer);
				destroy();
				return buffer;
			}
		};

		assertDestroyDuringAllocationRefused(provider::allocate, provider::getAllocatedMemory);
		Assert.assertNotNull(created.get());
		Assert.assertTrue("the unregistered Metal buffer must be released", created.get().isReleased());
	}

	/**
	 * An OpenCL allocation whose native buffer is created just before the provider is destroyed
	 * is refused at registration, and that refusal releases the {@code cl_mem} and returns its
	 * reservation (the reservation is returned only after the release succeeds).
	 */
	@Test(timeout = 60000)
	public void destroyDuringClAllocationReleasesBuffer() {
		CLDataContext context = dataContext(ComputeRequirement.CL, CLDataContext.class);
		Assume.assumeTrue("requires the OpenCL backend", context != null);

		CLMemoryProvider provider = new CLMemoryProvider(context, null, 4, CEILING,
				CLMemoryProvider.Location.DEVICE) {
			@Override
			protected cl_mem buffer(int len) {
				cl_mem mem = super.buffer(len);
				destroy();
				return mem;
			}
		};

		assertDestroyDuringAllocationRefused(provider::allocate, provider::getAllocatedMemory);
	}

	/**
	 * A backend allocation that fails with an {@link Error} returns its reservation, so the
	 * bytes that were never allocated are not left charged against the ceiling, and an
	 * allocation of the whole ceiling still fits afterwards.
	 */
	@Test(timeout = 60000)
	public void allocationErrorReleasesReservation() {
		assertFailureReleasesReservation(new OutOfMemoryError("simulated backend failure"));
	}

	/**
	 * A backend allocation that fails with a {@link RuntimeException} returns its reservation,
	 * and an allocation of the whole ceiling still fits afterwards.
	 */
	@Test(timeout = 60000)
	public void allocationExceptionReleasesReservation() {
		assertFailureReleasesReservation(new IllegalStateException("simulated backend failure"));
	}

	/**
	 * A reservation that does not fit under the ceiling is rejected with a
	 * {@link HardwareException} before the backend allocation is attempted, and reserves nothing.
	 */
	@Test(timeout = 60000)
	public void allocationBeyondCeilingSkipsBackend() {
		ReservingProvider provider = new ReservingProvider();
		AtomicLong used = new AtomicLong(CEILING - BLOCK_BYTES + 1);
		AtomicBoolean called = new AtomicBoolean();
		long waitMs = HardwareMemoryProvider.reclaimWaitMs;
		HardwareMemoryProvider.reclaimWaitMs = 1;

		try {
			provider.allocate(used, BLOCK_BYTES, () -> {
				called.set(true);
				return BLOCK_BYTES;
			});
			Assert.fail("an allocation beyond the ceiling was accepted");
		} catch (HardwareException expected) {
			Assert.assertEquals("Memory Max Reached", expected.getMessage());
		} finally {
			HardwareMemoryProvider.reclaimWaitMs = waitMs;
		}

		Assert.assertFalse(called.get());
		Assert.assertEquals(CEILING - BLOCK_BYTES + 1, used.get());
	}

	/**
	 * Allocations that reach a full ceiling together share their collection passes rather than
	 * each requesting its own sequence of full collections. Every contender is rejected, and the
	 * number of collections stays near {@link HardwareMemoryProvider#reclaimAttempts} instead of
	 * growing with the number of contenders. The bound allows one extra pass per contender, for a
	 * contender that arrives between two passes and starts one of its own.
	 */
	@Test(timeout = 60000)
	public void concurrentReclaimSharesCollections() throws InterruptedException {
		ReservingProvider provider = new ReservingProvider();
		AtomicLong used = new AtomicLong(CEILING);
		int threads = 2 * BLOCKS_PER_CEILING;
		List<HardwareException> rejected = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		long waitMs = HardwareMemoryProvider.reclaimWaitMs;
		HardwareMemoryProvider.reclaimWaitMs = 20;

		try {
			for (int i = 0; i < threads; i++) {
				executor.execute(() -> {
					try {
						start.await();
						provider.allocate(used, BLOCK_BYTES, () -> BLOCK_BYTES);
					} catch (HardwareException e) {
						rejected.add(e);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
			}

			start.countDown();
			executor.shutdown();
			Assert.assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
			HardwareMemoryProvider.reclaimWaitMs = waitMs;
		}

		log("collections=" + provider.collections.get() + " contenders=" + threads);
		Assert.assertEquals(threads, rejected.size());
		Assert.assertEquals(CEILING, used.get());
		Assert.assertTrue("collections=" + provider.collections.get(),
				provider.collections.get() <= HardwareMemoryProvider.reclaimAttempts + threads);
	}

	/**
	 * Makes a block-sized backend allocation fail with the given throwable and checks that the
	 * same throwable reaches the caller, the usage counter is restored to zero, and a following
	 * allocation of the whole ceiling succeeds and is charged in full.
	 *
	 * @param failure the throwable the backend allocation fails with
	 */
	private void assertFailureReleasesReservation(Throwable failure) {
		ReservingProvider provider = new ReservingProvider();
		AtomicLong used = new AtomicLong();

		try {
			provider.allocate(used, BLOCK_BYTES, () -> {
				if (failure instanceof Error) throw (Error) failure;
				throw (RuntimeException) failure;
			});
			Assert.fail("the backend failure was not propagated");
		} catch (RuntimeException | Error e) {
			Assert.assertSame(failure, e);
		}

		Assert.assertEquals(0, used.get());
		Assert.assertEquals(Long.valueOf(CEILING), provider.allocate(used, CEILING, () -> CEILING));
		Assert.assertEquals(CEILING, used.get());
	}

	/**
	 * Allocates one block from a provider that is destroyed between creating the native buffer
	 * and registering it, and checks that the allocation is refused and its bytes are no longer
	 * charged.
	 *
	 * @param allocate  allocates a block of the given number of elements
	 * @param allocated the provider's current usage in bytes
	 */
	private void assertDestroyDuringAllocationRefused(IntFunction<?> allocate, LongSupplier allocated) {
		try {
			allocate.apply(BLOCK_ELEMENTS);
			Assert.fail("an allocation registered after destroy() was accepted");
		} catch (IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("destroyed"));
		}

		Assert.assertEquals(0, allocated.getAsLong());
	}

	/**
	 * Requests one element more than the ceiling holds while each reclaim attempt is made to
	 * wait longer than the allowed time, and checks that the request is rejected within that
	 * time and reserves nothing. An allocation of exactly the ceiling still succeeds afterwards.
	 *
	 * @param allocate  allocates a block of the given number of elements
	 * @param allocated the provider's current usage in bytes
	 */
	private void assertOversizedRejected(IntFunction<?> allocate, LongSupplier allocated) {
		int ceilingElements = (int) (CEILING / 4);
		long waitMs = HardwareMemoryProvider.reclaimWaitMs;
		HardwareMemoryProvider.reclaimWaitMs = 10000;

		try {
			long start = System.currentTimeMillis();
			try {
				allocate.apply(ceilingElements + 1);
				Assert.fail("an allocation larger than the ceiling was accepted");
			} catch (HardwareException expected) {
				long elapsed = System.currentTimeMillis() - start;
				Assert.assertTrue("rejection took " + elapsed + "ms", elapsed < 5000);
			}

			Assert.assertEquals(0, allocated.getAsLong());
		} finally {
			HardwareMemoryProvider.reclaimWaitMs = waitMs;
		}

		Assert.assertNotNull(allocate.apply(ceilingElements));
		Assert.assertEquals(CEILING, allocated.getAsLong());
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

	/**
	 * A provider with the test ceiling that exposes
	 * {@link HardwareMemoryProvider#allocateReserved(AtomicLong, long, long, Supplier)} against a
	 * caller-supplied usage counter, so its reservation accounting can be checked on any machine.
	 */
	private static class ReservingProvider extends NativeMemoryProvider {
		/** How many garbage collections reclaiming has requested. */
		private final AtomicInteger collections = new AtomicInteger();

		/** Creates a direct-buffer provider with the test ceiling. */
		ReservingProvider() {
			super(Precision.FP32, CEILING, false, null, true);
		}

		/**
		 * Reserves {@code size} bytes against {@code used} and runs {@code allocation}.
		 *
		 * @param used       the usage counter
		 * @param size       the bytes to reserve
		 * @param allocation the simulated backend allocation
		 * @param <B>        the type the allocation produces
		 * @return the value produced by {@code allocation}
		 */
		<B> B allocate(AtomicLong used, long size, Supplier<B> allocation) {
			return allocateReserved(used, CEILING, size, allocation);
		}

		/** Counts the collection instead of requesting one; nothing here is garbage. */
		@Override
		protected void collectGarbage() {
			collections.incrementAndGet();
		}
	}
}
