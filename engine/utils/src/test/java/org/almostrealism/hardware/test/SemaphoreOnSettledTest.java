/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.DeferredSemaphore;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates {@link Semaphore#onSettled(Runnable)}, the prompt settlement primitive that
 * {@link Semaphore#then(java.util.function.Supplier)}, {@link Semaphore#all(List)},
 * {@link Semaphore#onComplete(Runnable)} and backend dependency ordering are built on, for the
 * implementations that register the callback instead of parking a thread in
 * {@link Semaphore#waitFor()}: {@link LatchSemaphore} and {@link DeferredSemaphore}.
 */
public class SemaphoreOnSettledTest extends TestSuiteBase {
	/** How long a callback that should not run is given the chance to run anyway. */
	private static final long QUIET_MILLIS = 250;

	/** How long a callback that should run is waited for. */
	private static final long SETTLE_SECONDS = 10;

	/**
	 * A callback registered on a pending latch runs only once the count-down that reaches zero
	 * happens, and then exactly once, however many times the latch is counted down afterwards.
	 */
	@Test(timeout = 30000)
	public void latchCallbackRunsOnceAtZero() throws InterruptedException {
		LatchSemaphore latch = new LatchSemaphore(2);
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch ran = new CountDownLatch(1);

		latch.onSettled(() -> {
			runs.incrementAndGet();
			ran.countDown();
		});

		latch.countDown();
		assertFalse(ran.await(QUIET_MILLIS, TimeUnit.MILLISECONDS));
		assertEquals(0, runs.get());

		latch.countDown();
		assertTrue(ran.await(SETTLE_SECONDS, TimeUnit.SECONDS));

		latch.countDown();
		Thread.sleep(QUIET_MILLIS);
		assertEquals(1, runs.get());
	}

	/**
	 * Every callback registered on the same pending latch runs when it settles, and a callback
	 * registered after it has settled runs at once.
	 */
	@Test(timeout = 30000)
	public void latchRunsEveryCallbackAndLateRegistrations() throws InterruptedException {
		LatchSemaphore latch = new LatchSemaphore(1);
		CountDownLatch early = new CountDownLatch(3);
		for (int i = 0; i < 3; i++) latch.onSettled(early::countDown);

		latch.countDown();
		assertTrue(early.await(SETTLE_SECONDS, TimeUnit.SECONDS));

		CountDownLatch late = new CountDownLatch(1);
		latch.onSettled(late::countDown);
		assertTrue(late.await(SETTLE_SECONDS, TimeUnit.SECONDS));
	}

	/**
	 * A re-attributed view shares the latch it was made from, and with it the registered
	 * callbacks: a callback on the view runs when the original is counted down to zero, and
	 * not before.
	 */
	@Test(timeout = 30000)
	public void reattributedViewObservesTheSharedLatch() throws InterruptedException {
		DefaultLatchSemaphore original = new DefaultLatchSemaphore((OperationMetadata) null, 1);
		Semaphore view = original.withRequester(new OperationMetadata("view", "re-attributed view"));
		CountDownLatch ran = new CountDownLatch(1);

		view.onSettled(ran::countDown);
		assertFalse(ran.await(QUIET_MILLIS, TimeUnit.MILLISECONDS));

		original.countDown();
		assertTrue(ran.await(SETTLE_SECONDS, TimeUnit.SECONDS));
	}

	/**
	 * A latch handed to the semaphore from outside cannot report its own settlement, so the
	 * callback is driven by a wait on a callback thread instead: it still runs only once the
	 * latch reaches zero.
	 */
	@Test(timeout = 30000)
	public void foreignLatchFallsBackToAWaitingCallback() throws InterruptedException {
		CountDownLatch foreign = new CountDownLatch(1);
		LatchSemaphore latch = new ForeignLatchSemaphore(foreign);
		CountDownLatch ran = new CountDownLatch(1);

		latch.onSettled(ran::countDown);
		assertFalse(ran.await(QUIET_MILLIS, TimeUnit.MILLISECONDS));

		latch.countDown();
		assertEquals(0L, foreign.getCount());
		assertTrue(ran.await(SETTLE_SECONDS, TimeUnit.SECONDS));
	}

	/** A {@link LatchSemaphore} over a latch it did not create. */
	private static final class ForeignLatchSemaphore extends LatchSemaphore {
		/**
		 * @param latch the latch to count down, created outside {@link LatchSemaphore}
		 */
		ForeignLatchSemaphore(CountDownLatch latch) {
			super(latch);
		}
	}

	/**
	 * A failed latch is settled: {@link Semaphore#onSettled(Runnable)} runs its callback, while
	 * {@link Semaphore#onComplete(Runnable)} does not, leaving the failure to the waiter.
	 */
	@Test(timeout = 30000)
	public void failureSettlesWithoutCompleting() throws InterruptedException {
		LatchSemaphore latch = new LatchSemaphore(1);
		CountDownLatch settled = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(1);

		latch.onSettled(settled::countDown);
		latch.onComplete(completed::countDown);

		latch.fail(new IllegalStateException("guarded work failed"));
		latch.countDown();

		assertTrue(settled.await(SETTLE_SECONDS, TimeUnit.SECONDS));
		assertFalse(completed.await(QUIET_MILLIS, TimeUnit.MILLISECONDS));

		boolean threw = false;
		try {
			latch.waitFor();
		} catch (IllegalStateException e) {
			threw = true;
			assertEquals("guarded work failed", e.getMessage());
		}

		assertTrue(threw);
	}

	/**
	 * A completion callback skipped because its latch failed with an exception leaves the
	 * failure to the waiter: nothing escapes the callback thread as an uncaught exception.
	 */
	@Test(timeout = 30000)
	public void skippedCompletionCallbackDoesNotEscapeItsThread() throws InterruptedException {
		List<Throwable> uncaught = skipCompletion(new IllegalStateException("guarded work failed"), false);
		assertTrue("uncaught: " + uncaught, uncaught.isEmpty());
	}

	/**
	 * A latch that failed with an {@link Error} (which {@link LatchSemaphore#waitFor()} rethrows
	 * as an Error) skips its completion callback, but does not conceal the Error: it escapes the
	 * callback thread to the uncaught-exception handler, so it is reported as soon as it is
	 * observed, and the waiter still receives it.
	 */
	@Test(timeout = 30000)
	public void skippedCompletionCallbackReportsError() throws InterruptedException {
		AssertionError failure = new AssertionError("guarded work failed");
		List<Throwable> uncaught = skipCompletion(failure, true);
		Assert.assertEquals(List.of(failure), uncaught);
	}

	/**
	 * Fails a latch with {@code failure}, verifies its {@link Semaphore#onComplete(Runnable)}
	 * callback is skipped and that the waiter receives {@code failure} itself, and returns what
	 * escaped a thread as an uncaught exception meanwhile.
	 *
	 * @param failure  the failure the guarded work records
	 * @param reported whether {@code failure} is expected to reach the uncaught-exception handler,
	 *                 in which case it is waited for rather than merely given a chance to arrive
	 * @return the uncaught exceptions observed while the callback was skipped
	 */
	private List<Throwable> skipCompletion(Throwable failure, boolean reported) throws InterruptedException {
		List<Throwable> uncaught = new ArrayList<>();
		CountDownLatch escaped = new CountDownLatch(1);
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
			synchronized (uncaught) {
				uncaught.add(t);
			}

			escaped.countDown();
		});

		try {
			LatchSemaphore latch = new LatchSemaphore(1);
			CountDownLatch completed = new CountDownLatch(1);
			latch.onComplete(completed::countDown);

			latch.fail(failure);
			latch.countDown();

			assertFalse(completed.await(QUIET_MILLIS, TimeUnit.MILLISECONDS));
			if (reported) assertTrue(escaped.await(SETTLE_SECONDS, TimeUnit.SECONDS));

			Throwable seen = null;
			try {
				latch.waitFor();
			} catch (RuntimeException | Error e) {
				seen = e;
			}

			Assert.assertSame(failure, seen);

			synchronized (uncaught) {
				return new ArrayList<>(uncaught);
			}
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previous);
		}
	}

	/**
	 * A callback on deferred work waits for the semaphore the work returned, not merely for the
	 * work to have started.
	 */
	@Test(timeout = 30000)
	public void deferredCallbackWaitsForStartedWork() throws InterruptedException {
		LatchSemaphore dependency = new LatchSemaphore(1);
		LatchSemaphore child = new LatchSemaphore(1);
		CountDownLatch started = new CountDownLatch(1);

		Semaphore chained = dependency.then(() -> {
			started.countDown();
			return child;
		});

		CountDownLatch settled = new CountDownLatch(1);
		CountDownLatch passive = new CountDownLatch(1);
		chained.onSettled(settled::countDown);
		chained.whenSettled(passive::countDown);

		assertFalse(started.await(QUIET_MILLIS, TimeUnit.MILLISECONDS));

		dependency.countDown();
		assertTrue(started.await(SETTLE_SECONDS, TimeUnit.SECONDS));
		assertFalse(settled.await(QUIET_MILLIS, TimeUnit.MILLISECONDS));
		assertEquals(1L, passive.getCount());

		child.countDown();
		assertTrue(settled.await(SETTLE_SECONDS, TimeUnit.SECONDS));
		assertTrue(passive.await(SETTLE_SECONDS, TimeUnit.SECONDS));
	}

	/**
	 * Deferred work that finishes synchronously, or that fails, settles its semaphore with
	 * nothing further to wait for, so callbacks on it run.
	 */
	@Test(timeout = 30000)
	public void deferredCallbackRunsForSynchronousAndFailedWork() throws InterruptedException {
		LatchSemaphore dependency = new LatchSemaphore(1);
		Semaphore synchronous = dependency.then(() -> null);
		Semaphore failing = dependency.then(() -> {
			throw new IllegalStateException("work failed");
		});

		CountDownLatch settled = new CountDownLatch(2);
		synchronous.onSettled(settled::countDown);
		failing.onSettled(settled::countDown);

		dependency.countDown();
		assertTrue(settled.await(SETTLE_SECONDS, TimeUnit.SECONDS));
	}

	/**
	 * A long chain of deferred work on latches is a chain of registrations: issuing it, before
	 * anything in it has run, gains far fewer threads than it has links, and releasing its head
	 * runs every link in order.
	 */
	@Test(timeout = 60000)
	public void deferredChainHoldsNoThreadPerLink() {
		int links = 1000;
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();

		LatchSemaphore head = new LatchSemaphore(1);
		List<Integer> order = new ArrayList<>();

		int before = threads.getThreadCount();
		threads.resetPeakThreadCount();

		Semaphore pending = head;
		for (int i = 0; i < links; i++) {
			int link = i;
			pending = pending.then(() -> {
				synchronized (order) {
					order.add(link);
				}

				return null;
			});
		}

		int growth = threads.getPeakThreadCount() - before;
		log("links=" + links + " peakThreadGrowth=" + growth);
		assertTrue("issuing the chain gained " + growth + " threads", growth < 64);

		head.countDown();
		pending.waitFor();

		assertEquals(links, order.size());
		for (int i = 0; i < links; i++) {
			assertEquals(i, order.get(i).intValue());
		}
	}
}
