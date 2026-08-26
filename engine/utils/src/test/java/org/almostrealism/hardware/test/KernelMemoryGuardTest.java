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

		KernelMemoryGuard.Reservation held = guard.acquire(data);
		Assert.assertFalse("Should not be deallocatable after acquire",
				guard.canDeallocate(1000L));

		guard.release(held);
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

		KernelMemoryGuard.Reservation first = guard.acquire(data);
		KernelMemoryGuard.Reservation second = guard.acquire(data);
		KernelMemoryGuard.Reservation third = guard.acquire(data);

		guard.release(first);
		Assert.assertFalse("Should not be deallocatable with 2 refs remaining",
				guard.canDeallocate(2000L));

		guard.release(second);
		Assert.assertFalse("Should not be deallocatable with 1 ref remaining",
				guard.canDeallocate(2000L));

		guard.release(third);
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

		KernelMemoryGuard.Reservation heldA = guard.acquire(dataA);
		KernelMemoryGuard.Reservation heldB = guard.acquire(dataB);

		guard.release(heldA);
		Assert.assertTrue("Address A should be deallocatable",
				guard.canDeallocate(100L));
		Assert.assertFalse("Address B should still be guarded",
				guard.canDeallocate(200L));

		guard.release(heldB);
		Assert.assertTrue("Address B should now be deallocatable",
				guard.canDeallocate(200L));
	}

	/**
	 * Verifies that null args array is handled gracefully in acquire and release.
	 */
	@Test(timeout = 10_000)
	public void nullArgsArrayIsNoOp() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		guard.release(guard.acquire((MemoryData[]) null));
		guard.release(null);
	}

	/**
	 * Verifies that null elements within the args array are skipped.
	 */
	@Test(timeout = 10_000)
	public void nullElementsInArgsAreSkipped() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = stubMemoryData(300L);

		KernelMemoryGuard.Reservation held = guard.acquire(null, data, null);
		Assert.assertFalse("Address should be guarded",
				guard.canDeallocate(300L));

		guard.release(held);
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

		guard.release(guard.acquire(noOp));
	}

	/**
	 * Verifies that a {@link MemoryData} whose getMem() returns a non-RAM
	 * Memory implementation is silently skipped.
	 */
	@Test(timeout = 10_000)
	public void nonRamMemoryIsSkipped() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MemoryData data = new StubMemoryData(new NonRamMemory());

		guard.release(guard.acquire(data));
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

		guard.release(guard.acquire());
		guard.release(null);

		Assert.assertTrue("Address should be deallocatable",
				guard.canDeallocate(400L));
		Assert.assertNotNull(data);
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

		guard.release(guard.acquire(failing));
	}

	/**
	 * Memory destroyed while a kernel is running is still given back.
	 *
	 * <p>This used to leak, and was documented as a known limitation: release
	 * asked the argument which address it had used, and an argument whose
	 * memory has been cleared can no longer answer, so the count stayed up and
	 * the address was reported as in use for the life of the process. Rendering
	 * destroys its intermediates as a matter of course, so this was not a
	 * corner case — it left hundreds of addresses permanently marked in use,
	 * and anything that trusted that verdict was misled by it.</p>
	 *
	 * <p>What was taken is now recorded when it is taken, so giving it back
	 * does not depend on the argument still being able to describe itself.</p>
	 */
	@Test(timeout = 10_000)
	public void memoryDestroyedDuringAKernelIsStillReleased() {
		KernelMemoryGuard guard = new KernelMemoryGuard();
		MutableMemoryData data = new MutableMemoryData(new StubRAM(700L));

		KernelMemoryGuard.Reservation held = guard.acquire(data);
		Assert.assertFalse("Should be guarded after acquire",
				guard.canDeallocate(700L));

		// Simulate MemoryData.destroy() clearing the memory reference
		data.clearMem();

		guard.release(held);
		Assert.assertTrue("The count must come back down even though the argument"
						+ " can no longer say which address it used",
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

		KernelMemoryGuard.Reservation guard = KernelMemoryGuard.acquireFor(new MemoryData[]{ data });
		// In test environment without Hardware initialized, guard may be null
		// Either way, releaseFor should not throw
		KernelMemoryGuard.releaseFor(guard);
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
						guard.release(guard.acquire(data));
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
		/** The content pointer address returned by this stub. */
		private final long address;

		/**
		 * Creates a StubRAM with the specified content pointer address.
		 * @param address the content pointer value to return
		 */
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
		/**
		 * Returns null since this is a minimal stub implementation.
		 * @return null
		 */
		@Override
		public MemoryProvider getProvider() { return null; }
	}

	/**
	 * Minimal {@link MemoryData} implementation backed by a given {@link Memory}.
	 */
	private static class StubMemoryData implements MemoryData {
		/** The Memory backing this data. */
		private final Memory mem;

		/**
		 * Creates a StubMemoryData backed by the specified Memory.
		 * @param mem the Memory backing this data
		 */
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
		/** The Memory backing this data, can be cleared. */
		private Memory mem;

		/**
		 * Creates a MutableMemoryData backed by the specified Memory.
		 * @param mem the Memory backing this data
		 */
		MutableMemoryData(Memory mem) { this.mem = mem; }

		/**
		 * Clears the memory reference, simulating a destroyed MemoryData.
		 */
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
		/**
		 * Always throws a RuntimeException to simulate a failing MemoryData.
		 * @return never returns
		 * @throws RuntimeException always
		 */
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
