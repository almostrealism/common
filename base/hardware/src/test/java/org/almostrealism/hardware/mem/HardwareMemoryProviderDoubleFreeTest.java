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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.io.Console;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A {@link HardwareMemoryProvider} reports a lost release claim as a prevented double free only
 * when two explicit releases raced for the same block. A garbage-collector release that finds
 * the block already released explicitly, or an explicit release that finds the collector got
 * there first, is the expected overlap of the two release paths: the block is freed once and
 * nothing is reported, so the bounded double-free detail budget is left for real ownership bugs.
 *
 * <p>Garbage collection is simulated with {@link java.lang.ref.Reference#enqueue()} on the
 * tracked {@link NativeRef}, which puts it on the provider's reference queue as the collector
 * would. Every explicit release is made before the reference is enqueued, because enqueueing
 * clears the referent and the provider would then no longer resolve the {@link RAM} to it.</p>
 *
 * <p>Like {@link HardwareMemoryProviderLeaseTest}, this does not extend {@code TestSuiteBase},
 * which lives in a module above this one, and needs no hardware backend: the test provider answers
 * {@link HardwareMemoryProvider#reservations(long)} itself and logs to its own console.</p>
 */
public class HardwareMemoryProviderDoubleFreeTest {

	/** Size, in bytes, of every fake block. */
	private static final int SIZE = 64;

	/** How long a wait for a background deallocation thread may take before the test fails. */
	private static final long WAIT_MS = 10_000;

	/** Text that begins every prevented double free report. */
	private static final String DOUBLE_FREE = "Skipping double deallocate";

	/** Gives each fake block its own address. */
	private static final AtomicLong nextAddress = new AtomicLong(0x7e00_0000_0000L);

	/** {@link RAM#enableWarnings} before the test, restored afterwards. */
	private boolean enableWarnings;

	/** {@link HardwareMemoryProvider#queueDeallocation} before the test, restored afterwards. */
	private boolean queueDeallocation;

	/** {@link MemoryReference#captureFreeStackTrace} before the test, restored afterwards. */
	private boolean captureFreeStackTrace;

	/** The provider under test. */
	private TestProvider provider;

	/** Every line the provider logged during the test. */
	private final List<String> logged = new ArrayList<>();

	/** Captures the provider's console output into {@link #logged}. */
	private final Consumer<String> listener = line -> {
		synchronized (logged) {
			logged.add(line);
		}
	};

	/** Turns on the reporting under test and creates a provider whose console is captured. */
	@Before
	public void setUp() {
		enableWarnings = RAM.enableWarnings;
		queueDeallocation = HardwareMemoryProvider.queueDeallocation;
		captureFreeStackTrace = MemoryReference.captureFreeStackTrace;

		RAM.enableWarnings = true;
		MemoryReference.captureFreeStackTrace = true;

		provider = new TestProvider();
		provider.console.addListener(listener);
	}

	/** Restores the global flags and detaches the console listener. */
	@After
	public void tearDown() {
		provider.console.removeListener(listener);
		RAM.enableWarnings = enableWarnings;
		HardwareMemoryProvider.queueDeallocation = queueDeallocation;
		MemoryReference.captureFreeStackTrace = captureFreeStackTrace;
	}

	/**
	 * An explicit release followed by the collector enqueueing the same reference frees the block
	 * once and reports nothing.
	 */
	@Test(timeout = 30000)
	public void collectedAfterExplicitFreeIsSilent() {
		FakeRam ram = provider.register();
		NativeRef<RAM> ref = provider.trackedRef(ram);
		long attempts = HardwareMemoryProvider.getDoubleFreeAttempts();

		provider.deallocate(SIZE, ram);
		Assert.assertEquals(1, provider.releases.get());

		enqueueAndAwait(ref);

		assertReleasedOnceSilently(attempts);
	}

	/**
	 * An explicit release held back for a kernel, which the sweep finishes before the collector
	 * enqueues the reference, frees the block once and reports nothing.
	 */
	@Test(timeout = 30000)
	public void deferredSweepThenCollectedIsSilent() {
		FakeRam ram = provider.register();
		NativeRef<RAM> ref = provider.trackedRef(ram);
		long attempts = HardwareMemoryProvider.getDoubleFreeAttempts();

		provider.heldForProcessThread = true;
		provider.heldForOthers = true;
		provider.deallocate(SIZE, ram);
		Assert.assertEquals("the release must be held back while a kernel uses the block",
				0, provider.releases.get());

		provider.heldForProcessThread = false;
		provider.heldForOthers = false;
		await("the sweep to finish the held-back release", () -> provider.releases.get() == 1);

		enqueueAndAwait(ref);

		assertReleasedOnceSilently(attempts);
	}

	/**
	 * An explicit release held back for a kernel, where the collector enqueues the reference and
	 * frees the block before the sweep reaches it, frees the block once and reports nothing. The
	 * ordering is forced by keeping the block reserved for the process thread's sweep until the
	 * submit thread has handled the reference.
	 */
	@Test(timeout = 30000)
	public void collectedThenDeferredSweepIsSilent() {
		FakeRam ram = provider.register();
		NativeRef<RAM> ref = provider.trackedRef(ram);
		long attempts = HardwareMemoryProvider.getDoubleFreeAttempts();

		provider.heldForProcessThread = true;
		provider.heldForOthers = true;
		provider.deallocate(SIZE, ram);
		Assert.assertEquals("the release must be held back while a kernel uses the block",
				0, provider.releases.get());

		provider.heldForOthers = false;
		enqueueAndAwait(ref);
		Assert.assertEquals("the collected path should have freed the block",
				1, provider.releases.get());

		provider.heldForProcessThread = false;
		awaitSweeps();

		assertReleasedOnceSilently(attempts);
	}

	/**
	 * Two explicit releases racing for the same block free it once, and the one that loses the
	 * claim is reported in full as a prevented double free.
	 */
	@Test(timeout = 30000)
	public void racingExplicitReleasesAreReported() throws InterruptedException {
		FakeRam ram = provider.register();
		long attempts = HardwareMemoryProvider.getDoubleFreeAttempts();

		provider.releaseEntered = new CountDownLatch(1);
		provider.releaseGate = new CountDownLatch(1);

		Thread first = new Thread(() -> provider.deallocate(SIZE, ram), "first explicit release");
		first.start();

		try {
			Assert.assertTrue("the first release should reach the native free",
					provider.releaseEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));

			provider.deallocate(SIZE, ram);
		} finally {
			provider.releaseGate.countDown();
			first.join(WAIT_MS);
		}

		Assert.assertEquals("the block must be freed exactly once", 1, provider.releases.get());

		long counted = HardwareMemoryProvider.getDoubleFreeAttempts();
		Assert.assertTrue("the losing explicit release must be counted as a double free",
				counted > attempts);

		if (counted <= 64) {
			Assert.assertTrue("the losing explicit release must be reported in full",
					loggedContains(DOUBLE_FREE + " of "));
			Assert.assertTrue("the report must show where the block was first freed",
					loggedContains("First freed at:"));
		}
	}

	/** A collected reference that was never released explicitly is freed once, silently. */
	@Test(timeout = 30000)
	public void collectedWithoutExplicitFreeReleasesOnce() {
		FakeRam ram = provider.register();
		NativeRef<RAM> ref = provider.trackedRef(ram);
		long attempts = HardwareMemoryProvider.getDoubleFreeAttempts();

		enqueueAndAwait(ref);

		assertReleasedOnceSilently(attempts);
		Assert.assertEquals("the collected block must no longer be tracked",
				0, provider.getAllocatedCount());
	}

	/**
	 * In queued mode the same reference queued once by an explicit release and once by the
	 * collector is freed once, whichever entry the process thread takes first, and nothing is
	 * reported.
	 */
	@Test(timeout = 30000)
	public void queuedExplicitAndCollectedIsSilent() {
		HardwareMemoryProvider.queueDeallocation = true;

		FakeRam ram = provider.register();
		NativeRef<RAM> ref = provider.trackedRef(ram);
		long attempts = HardwareMemoryProvider.getDoubleFreeAttempts();

		provider.deallocate(SIZE, ram);
		enqueueAndAwait(ref);

		await("the queued release to finish", () -> provider.releases.get() >= 1);
		awaitSweeps();

		assertReleasedOnceSilently(attempts);
	}

	/**
	 * A stale collected reference whose address has been reused by a new block that a kernel
	 * holds must not be held back in the new block's place. If it were, the new block's own
	 * held-back release would be absorbed by the stale entry (the held-back releases are keyed
	 * by address and the first entry is kept), the sweep would retry only the stale reference,
	 * and the new block would never be freed.
	 */
	@Test(timeout = 30000)
	public void staleCollectedRefDoesNotTakeReusedAddress() {
		long address = nextAddress.addAndGet(0x1000);

		FakeRam first = provider.register(address);
		NativeRef<RAM> staleRef = provider.trackedRef(first);
		provider.deallocate(SIZE, first);
		Assert.assertEquals(1, provider.releases.get());

		FakeRam second = provider.register(address);
		Assert.assertNotSame("the new block must have its own reference",
				staleRef, provider.trackedRef(second));

		long attempts = HardwareMemoryProvider.getDoubleFreeAttempts();
		provider.heldForProcessThread = true;
		provider.heldForOthers = true;

		enqueueAndAwait(staleRef);

		provider.deallocate(SIZE, second);
		Assert.assertEquals("the new block's release must be held back while a kernel uses it",
				1, provider.releases.get());

		provider.heldForProcessThread = false;
		provider.heldForOthers = false;
		await("the sweep to free the new block", () -> provider.releases.get() == 2);
		awaitSweeps();

		Assert.assertEquals("each block must be freed exactly once", 2, provider.releases.get());
		Assert.assertEquals("no block may remain tracked", 0, provider.getAllocatedCount());
		Assert.assertFalse("no double free may be reported", loggedContains(DOUBLE_FREE));
		Assert.assertEquals("the double-free detail budget must not be consumed",
				attempts, HardwareMemoryProvider.getDoubleFreeAttempts());
	}

	/**
	 * A successful explicit release clears the reference, so the collector can no longer find
	 * its referent, and it stops being tracked.
	 */
	@Test(timeout = 30000)
	public void explicitReleaseClearsReference() {
		FakeRam ram = provider.register();
		NativeRef<RAM> ref = provider.trackedRef(ram);
		Assert.assertTrue(ref.refersTo(ram));

		provider.deallocate(SIZE, ram);

		Assert.assertTrue("the reference must be claimed", ref.isFreed());
		Assert.assertEquals(ReleaseOrigin.EXPLICIT, ref.getFreedBy());
		Assert.assertFalse("an explicitly released reference must be cleared", ref.refersTo(ram));
		Assert.assertEquals("the released block must no longer be tracked",
				0, provider.getAllocatedCount());
	}

	/**
	 * A release freed by the collected path records that origin on the reference and leaves
	 * nothing tracked.
	 */
	@Test(timeout = 30000)
	public void collectedReleaseRecordsOrigin() {
		FakeRam ram = provider.register();
		NativeRef<RAM> ref = provider.trackedRef(ram);

		enqueueAndAwait(ref);

		Assert.assertEquals(1, provider.releases.get());
		Assert.assertTrue(ref.isFreed());
		Assert.assertEquals(ReleaseOrigin.COLLECTED, ref.getFreedBy());
	}

	/**
	 * The claim records its origin atomically: the first claim wins and its origin is kept, a
	 * losing claim does not overwrite it, and reversing the claim clears both the flag and the
	 * origin so a retry records its own.
	 */
	@Test(timeout = 30000)
	public void claimRecordsWinningOrigin() {
		FakeRam ram = new FakeRam(provider, nextAddress.addAndGet(0x1000));
		NativeRef<RAM> ref = new NativeRef<>(ram, new ReferenceQueue<>());

		Assert.assertFalse(ref.isFreed());
		Assert.assertNull("an unclaimed reference has no origin", ref.getFreedBy());

		Assert.assertTrue(ref.tryClaimFreed(ReleaseOrigin.COLLECTED));
		Assert.assertFalse("a second claim must lose", ref.tryClaimFreed(ReleaseOrigin.EXPLICIT));
		Assert.assertFalse("the no-argument claim must lose as well", ref.tryClaimFreed());
		Assert.assertEquals("a losing claim must not overwrite the winner's origin",
				ReleaseOrigin.COLLECTED, ref.getFreedBy());
		Assert.assertNotNull("the first claim's stack trace must be captured",
				ref.getFirstFreeStackTrace());

		ref.unclaimFreed();
		Assert.assertFalse(ref.isFreed());
		Assert.assertNull("reversing the claim must clear its origin", ref.getFreedBy());
		Assert.assertNull("reversing the claim must clear its stack trace", ref.getFirstFreeStackTrace());

		Assert.assertTrue("the no-argument claim is an explicit claim", ref.tryClaimFreed());
		Assert.assertEquals(ReleaseOrigin.EXPLICIT, ref.getFreedBy());
	}

	/** Only an explicit request losing to an explicit winner is a double free. */
	@Test(timeout = 30000)
	public void onlyExplicitAfterExplicitIsDoubleFree() {
		Assert.assertTrue(ReleaseOrigin.EXPLICIT.isDoubleFreeAfter(ReleaseOrigin.EXPLICIT));
		Assert.assertFalse(ReleaseOrigin.EXPLICIT.isDoubleFreeAfter(ReleaseOrigin.COLLECTED));
		Assert.assertFalse(ReleaseOrigin.COLLECTED.isDoubleFreeAfter(ReleaseOrigin.EXPLICIT));
		Assert.assertFalse(ReleaseOrigin.COLLECTED.isDoubleFreeAfter(ReleaseOrigin.COLLECTED));
		Assert.assertFalse("a claim reversed before the loser looked is not a double free",
				ReleaseOrigin.EXPLICIT.isDoubleFreeAfter(null));
		Assert.assertFalse(ReleaseOrigin.COLLECTED.isDoubleFreeAfter(null));
	}

	/**
	 * Enqueues the reference as the garbage collector would, and waits until the submit thread
	 * has handled it.
	 *
	 * @param ref the reference to enqueue
	 */
	private void enqueueAndAwait(NativeRef<RAM> ref) {
		long handled = provider.getCollectedHandledCount();
		Assert.assertTrue("the reference should not have been enqueued already", ref.enqueue());
		await("the submit thread to handle the collected reference",
				() -> provider.getCollectedHandledCount() > handled);
	}

	/**
	 * Waits until the process thread has completed at least one full iteration (queue poll and
	 * deferred sweep) that began after this call.
	 */
	private void awaitSweeps() {
		long iterations = provider.getProcessIterationCount();
		await("the process thread to complete a sweep",
				() -> provider.getProcessIterationCount() >= iterations + 2);
	}

	/**
	 * Asserts that the block was freed exactly once and that no double free was reported.
	 *
	 * @param attempts the double-free count before the case ran
	 */
	private void assertReleasedOnceSilently(long attempts) {
		Assert.assertEquals("the block must be freed exactly once", 1, provider.releases.get());
		Assert.assertFalse("no double free may be reported", loggedContains(DOUBLE_FREE));
		Assert.assertEquals("the double-free detail budget must not be consumed",
				attempts, HardwareMemoryProvider.getDoubleFreeAttempts());
	}

	/**
	 * Returns whether any captured console line contains the given text.
	 *
	 * @param text the text to look for
	 * @return whether it was logged
	 */
	private boolean loggedContains(String text) {
		synchronized (logged) {
			return logged.stream().anyMatch(line -> line.contains(text));
		}
	}

	/**
	 * Waits for a condition set by a background thread, failing the test if it does not hold
	 * within {@link #WAIT_MS}.
	 *
	 * @param description what is being waited for
	 * @param condition   the condition
	 */
	private static void await(String description, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + WAIT_MS;

		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				Assert.fail("Timed out waiting for " + description);
			}

			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				Assert.fail("Interrupted waiting for " + description);
			}
		}
	}

	/**
	 * A provider whose native release only counts, whose kernel reservations are set by the
	 * test, and which logs to its own console rather than the hardware console.
	 */
	private static class TestProvider extends HardwareMemoryProvider<RAM> {
		/** The console this provider logs to. */
		private final Console console = Console.root().child();

		/** How many blocks the native release has freed. */
		private final AtomicInteger releases = new AtomicInteger();

		/** Whether a kernel holds every block, as seen by the deallocation process thread's sweep. */
		private volatile boolean heldForProcessThread;

		/** Whether a kernel holds every block, as seen by every other thread. */
		private volatile boolean heldForOthers;

		/** Counted down when the native release is entered, if set. */
		private volatile CountDownLatch releaseEntered;

		/** Awaited by the native release before it returns, if set. */
		private volatile CountDownLatch releaseGate;

		/**
		 * Allocates and registers a fake block at a fresh address.
		 *
		 * @return the block
		 */
		FakeRam register() {
			return register(nextAddress.addAndGet(0x1000));
		}

		/**
		 * Allocates and registers a fake block at the given address.
		 *
		 * @param address the block's address
		 * @return the block
		 */
		FakeRam register(long address) {
			FakeRam ram = new FakeRam(this, address);
			allocated(ram);
			return ram;
		}

		/**
		 * Returns the reference tracking the given block.
		 *
		 * @param ram the block
		 * @return its reference
		 */
		NativeRef<RAM> trackedRef(RAM ram) {
			NativeRef<RAM> ref = getNativeRef(ram);
			Assert.assertNotNull("the block should be tracked", ref);
			return ref;
		}

		@Override
		protected ReservationState reservations(long address) {
			boolean processThread = Thread.currentThread().getName().endsWith("Deallocation Process Thread");
			boolean held = processThread ? heldForProcessThread : heldForOthers;
			return held ? new ReservationState(1, 0) : ReservationState.NONE;
		}

		@Override
		protected void deallocate(NativeRef<RAM> ref) {
			CountDownLatch entered = releaseEntered;
			if (entered != null) entered.countDown();

			CountDownLatch gate = releaseGate;
			if (gate != null) {
				try {
					gate.await(WAIT_MS, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			releases.incrementAndGet();
		}

		@Override
		public void getMem(RAM mem, int sOffset, double[] out, int oOffset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int getNumberSize() { return 8; }

		@Override
		public String getName() { return "double-free-test"; }

		@Override
		public Console console() { return console; }
	}

	/** A block with no memory behind it, at an address of its own. */
	private static class FakeRam extends RAM {
		/** The provider this block is registered with. */
		private final MemoryProvider<RAM> provider;

		/** The block's address. */
		private final long address;

		/**
		 * Creates a block owned by the given provider.
		 *
		 * @param provider the owning provider
		 * @param address  the block's address
		 */
		FakeRam(MemoryProvider<RAM> provider, long address) {
			this.provider = provider;
			this.address = address;
		}

		@Override
		public long getContainerPointer() { return address; }

		@Override
		public long getSize() { return SIZE; }

		@Override
		public MemoryProvider getProvider() { return provider; }
	}
}
