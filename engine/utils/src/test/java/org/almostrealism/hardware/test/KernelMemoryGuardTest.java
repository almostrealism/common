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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.TraversalOrdering;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.NoOpMemoryData;
import org.almostrealism.hardware.mem.KernelMemoryGuard;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link KernelMemoryGuard} reference-counting registry.
 *
 * <p>Verifies that acquire/release correctly track memory addresses,
 * that {@link KernelMemoryGuard#canDeallocate(long)} reflects the
 * current reference count, and that concurrent operations are safe.</p>
 */
public class KernelMemoryGuardTest extends TestSuiteBase {

	/**
	 * Verifies that a single acquire prevents deallocation, and
	 * a corresponding release allows it.
	 */
	@Test(timeout = 10_000)
	public void acquireAndReleaseSingleArg() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = stubMemoryData(1000L);

		Assert.assertTrue("Should be deallocatable before acquire",
				guard.canDeallocate(1000L));

		guard.acquire(data);
		Assert.assertFalse("Should not be deallocatable after acquire",
				guard.canDeallocate(1000L));

		guard.release(data);
		Assert.assertTrue("Should be deallocatable after release",
				guard.canDeallocate(1000L));
	}

	/**
	 * Verifies that multiple acquires on the same address require
	 * the same number of releases before deallocation is allowed.
	 */
	@Test(timeout = 10_000)
	public void multipleAcquiresRequireMultipleReleases() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = stubMemoryData(2000L);

		guard.acquire(data);
		guard.acquire(data);
		guard.acquire(data);

		guard.release(data);
		Assert.assertFalse("Should not be deallocatable with 2 refs remaining",
				guard.canDeallocate(2000L));

		guard.release(data);
		Assert.assertFalse("Should not be deallocatable with 1 ref remaining",
				guard.canDeallocate(2000L));

		guard.release(data);
		Assert.assertTrue("Should be deallocatable after all releases",
				guard.canDeallocate(2000L));
	}

	/**
	 * Verifies that different addresses are tracked independently.
	 */
	@Test(timeout = 10_000)
	public void independentAddressTracking() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData dataA = stubMemoryData(100L);
		MemoryData dataB = stubMemoryData(200L);

		guard.acquire(dataA);
		guard.acquire(dataB);

		guard.release(dataA);
		Assert.assertTrue("Address A should be deallocatable",
				guard.canDeallocate(100L));
		Assert.assertFalse("Address B should still be guarded",
				guard.canDeallocate(200L));

		guard.release(dataB);
		Assert.assertTrue("Address B should now be deallocatable",
				guard.canDeallocate(200L));
	}

	/**
	 * Verifies that null args array is handled gracefully in acquire and release.
	 */
	@Test(timeout = 10_000)
	public void nullArgsArrayIsNoOp() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		guard.acquire((MemoryData[]) null);
		guard.release((MemoryData[]) null);
	}

	/**
	 * Verifies that null elements within the args array are skipped.
	 */
	@Test(timeout = 10_000)
	public void nullElementsInArgsAreSkipped() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = stubMemoryData(300L);

		guard.acquire(null, data, null);
		Assert.assertFalse("Address should be guarded",
				guard.canDeallocate(300L));

		guard.release(null, data, null);
		Assert.assertTrue("Address should be deallocatable",
				guard.canDeallocate(300L));
	}

	/**
	 * Verifies that a {@link MemoryData} whose getMem() returns null
	 * (e.g., {@link NoOpMemoryData}) is silently skipped.
	 */
	@Test(timeout = 10_000)
	public void noOpMemoryDataIsSkipped() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		NoOpMemoryData noOp = new NoOpMemoryData();

		guard.acquire(noOp);
		guard.release(noOp);
	}

	/**
	 * Verifies that a {@link MemoryData} whose getMem() returns a non-RAM
	 * Memory implementation is silently skipped.
	 */
	@Test(timeout = 10_000)
	public void nonRamMemoryIsSkipped() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = new StubMemoryData(new NonRamMemory());

		guard.acquire(data);
		// No addresses should be tracked
		Assert.assertTrue("Unknown address should be deallocatable",
				guard.canDeallocate(999L));
	}

	/**
	 * Verifies that releasing an address that was never acquired is a no-op.
	 */
	@Test(timeout = 10_000)
	public void releaseWithoutAcquireIsNoOp() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = stubMemoryData(400L);

		guard.release(data);
		Assert.assertTrue("Address should be deallocatable",
				guard.canDeallocate(400L));
	}

	/**
	 * Verifies that canDeallocate returns true for an address
	 * that has never been seen.
	 */
	@Test(timeout = 10_000)
	public void unknownAddressIsDeallocatable() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		Assert.assertTrue(guard.canDeallocate(0L));
		Assert.assertTrue(guard.canDeallocate(Long.MAX_VALUE));
		Assert.assertTrue(guard.canDeallocate(-1L));
	}

	/**
	 * Verifies that a {@link MemoryData} whose getMem() throws an exception
	 * is handled gracefully without crashing.
	 */
	@Test(timeout = 10_000)
	public void exceptionInGetMemIsHandled() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData failing = new FailingMemoryData();

		guard.acquire(failing);
		guard.release(failing);
	}

	/**
	 * Documents a known limitation: if resolveRAM returns null during release
	 * (e.g., MemoryData was explicitly destroyed between acquire and release),
	 * the reference count is not decremented and the map entry leaks.
	 *
	 * <p>This test verifies the current behavior (leak) so that a future fix
	 * can be validated by changing the assertion.</p>
	 */
	@Test(timeout = 10_000)
	public void releaseWithNullResolveRAMLeaksEntry() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MutableMemoryData data = new MutableMemoryData(new StubRAM(700L));

		guard.acquire(data);
		Assert.assertFalse("Should be guarded after acquire",
				guard.canDeallocate(700L));

		// Simulate MemoryData.destroy() clearing the memory reference
		data.clearMem();

		guard.release(data);
		// Known limitation: the entry leaks because resolveRAM returns null
		Assert.assertFalse("Entry leaks when resolveRAM returns null during release "
				+ "(known limitation — see NATIVE_MEMORY_GC_LIFECYCLE_REVIEW.md Issue 1)",
				guard.canDeallocate(700L));
	}

	/**
	 * Verifies that the static {@link KernelMemoryGuard#acquireFor} returns
	 * null when no Hardware is available, and that
	 * {@link KernelMemoryGuard#releaseFor} handles null guard gracefully.
	 */
	@Test(timeout = 10_000)
	public void staticHelpersWithNoHardware() {
		MemoryData data = stubMemoryData(500L);

		KernelMemoryGuard guard = KernelMemoryGuard.acquireFor(new MemoryData[]{ data });
		// In test environment without Hardware initialized, guard may be null
		// Either way, releaseFor should not throw
		KernelMemoryGuard.releaseFor(guard, new MemoryData[]{ data });
	}

	/**
	 * Verifies that concurrent acquire and release operations on the
	 * same address do not corrupt the reference count.
	 */
	@Test(timeout = 30_000)
	public void concurrentAcquireAndRelease() throws InterruptedException {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = stubMemoryData(600L);
		int threadCount = 8;
		int iterations = 1000;
		CyclicBarrier barrier = new CyclicBarrier(threadCount);
		CountDownLatch done = new CountDownLatch(threadCount);
		AtomicInteger errors = new AtomicInteger(0);

		for (int t = 0; t < threadCount; t++) {
			new Thread(() -> {
				try {
					barrier.await();
					for (int i = 0; i < iterations; i++) {
						guard.acquire(data);
						guard.release(data);
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					done.countDown();
				}
			}, "guard-thread-" + t).start();
		}

		done.await();
		Assert.assertEquals("No errors during concurrent operations", 0, errors.get());
		Assert.assertTrue("Address should be deallocatable after balanced acquire/release",
				guard.canDeallocate(600L));
	}

	/**
	 * Creates a stub {@link MemoryData} backed by a {@link StubRAM}
	 * with the given content pointer.
	 */
	private MemoryData stubMemoryData(long address) {
		return new StubMemoryData(new StubRAM(address));
	}

	/**
	 * Minimal {@link RAM} subclass for testing that returns a fixed content pointer.
	 */
	private static class StubRAM extends RAM {
		private final long address;

		StubRAM(long address) {
			super(0);
			this.address = address;
		}

		@Override
		public long getContentPointer() { return address; }

		@Override
		public long getSize() { return 1024; }

		@Override
		public MemoryProvider getProvider() { return null; }
	}

	/**
	 * Minimal {@link Memory} implementation that is not a {@link RAM} subclass,
	 * used to test the non-RAM code path in resolveRAM.
	 */
	private static class NonRamMemory implements Memory {
		@Override
		public MemoryProvider getProvider() { return null; }
	}

	/**
	 * Minimal {@link MemoryData} implementation backed by a given {@link Memory}.
	 */
	private static class StubMemoryData implements MemoryData {
		private final Memory mem;

		StubMemoryData(Memory mem) { this.mem = mem; }

		@Override
		public Memory getMem() { return mem; }

		@Override
		public void reassign(Memory mem) { }

		@Override
		public int getMemLength() { return 0; }

		@Override
		public void setDelegate(MemoryData m, int offset, TraversalOrdering order) { }

		@Override
		public MemoryData getDelegate() { return null; }

		@Override
		public int getDelegateOffset() { return 0; }

		@Override
		public TraversalOrdering getDelegateOrdering() { return null; }

		@Override
		public void destroy() { }
	}

	/**
	 * {@link MemoryData} whose memory reference can be cleared to simulate
	 * {@link MemoryData#destroy()} between acquire and release.
	 */
	private static class MutableMemoryData implements MemoryData {
		private Memory mem;

		MutableMemoryData(Memory mem) { this.mem = mem; }

		/** Clears the memory reference, simulating a destroyed MemoryData. */
		void clearMem() { this.mem = null; }

		@Override
		public Memory getMem() { return mem; }

		@Override
		public void reassign(Memory mem) { }

		@Override
		public int getMemLength() { return 0; }

		@Override
		public void setDelegate(MemoryData m, int offset, TraversalOrdering order) { }

		@Override
		public MemoryData getDelegate() { return null; }

		@Override
		public int getDelegateOffset() { return 0; }

		@Override
		public TraversalOrdering getDelegateOrdering() { return null; }

		@Override
		public void destroy() { }
	}

	/**
	 * {@link MemoryData} that throws on getMem(), used to test exception handling.
	 */
	private static class FailingMemoryData implements MemoryData {
		@Override
		public Memory getMem() { throw new RuntimeException("Simulated failure"); }

		@Override
		public void reassign(Memory mem) { }

		@Override
		public int getMemLength() { return 0; }

		@Override
		public void setDelegate(MemoryData m, int offset, TraversalOrdering order) { }

		@Override
		public MemoryData getDelegate() { return null; }

		@Override
		public int getDelegateOffset() { return 0; }

		@Override
		public TraversalOrdering getDelegateOrdering() { return null; }

		@Override
		public void destroy() { }
	}
}
