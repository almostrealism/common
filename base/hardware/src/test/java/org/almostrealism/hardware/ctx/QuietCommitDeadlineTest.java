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

package org.almostrealism.hardware.ctx;

import io.almostrealism.concurrent.ConfinedExecutor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates {@link QuietCommitDeadline}, the timer that commits a {@link SubmissionStream}'s open
 * batch once it has gone quiet. No device is needed: the deadline's expiry only queues a check on
 * the stream's own thread, so these tests stand a {@link ConfinedExecutor} in for that thread and
 * count the checks. Every deadline method is called on the executor's thread, as a stream calls
 * it on its own.
 *
 * <p>Plain JUnit: {@code TestSuiteBase} lives in the engine layer, above this module.</p>
 */
public class QuietCommitDeadlineTest {
	/** The quiet delay in force before each test, restored after it. */
	private int savedQuietCommitMicros;

	/** Records the shared quiet delay so each test can set its own. */
	@Before
	public void saveQuietDelay() {
		savedQuietCommitMicros = SubmissionStream.quietCommitMicros;
	}

	/** Restores the shared quiet delay. */
	@After
	public void restoreQuietDelay() {
		SubmissionStream.quietCommitMicros = savedQuietCommitMicros;
	}

	/**
	 * Returns a deadline whose expiry queues {@code check} on {@code executor}, as a stream's
	 * expiry queues its check on the stream's own thread.
	 *
	 * @param executor stands in for the stream's own thread
	 * @param check    the check of the open batch
	 * @return the deadline
	 */
	private static QuietCommitDeadline deadline(ConfinedExecutor executor, Runnable check) {
		return new QuietCommitDeadline(() -> executor.runLater(check));
	}

	/** A submission schedules one expiry, which runs the check on the executor's thread. */
	@Test(timeout = 20000)
	public void expiryRunsCheckOnExecutor() throws InterruptedException {
		SubmissionStream.quietCommitMicros = 20_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		CountDownLatch checked = new CountDownLatch(1);
		AtomicReference<Boolean> onConfinedThread = new AtomicReference<>();
		QuietCommitDeadline deadline = deadline(executor, () -> {
			onConfinedThread.set(executor.isConfinedThread());
			checked.countDown();
		});

		try {
			executor.run(deadline::submitted);
			Assert.assertTrue("The expiry must run the check", checked.await(10, TimeUnit.SECONDS));
			Assert.assertEquals(Boolean.TRUE, onConfinedThread.get());
		} finally {
			executor.destroy();
		}
	}

	/**
	 * Submissions made while an expiry is pending schedule no further expiry, and
	 * {@link QuietCommitDeadline#expire()} reports the quiet time still owed since the last of
	 * them.
	 */
	@Test(timeout = 20000)
	public void onlyOneExpiryIsPendingAndExpireReportsRemainingTime() throws InterruptedException {
		SubmissionStream.quietCommitMicros = 100_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicInteger checks = new AtomicInteger();
		CountDownLatch checked = new CountDownLatch(1);
		QuietCommitDeadline deadline = deadline(executor, () -> {
			checks.incrementAndGet();
			checked.countDown();
		});

		try {
			AtomicLong remaining = new AtomicLong();
			executor.run(() -> {
				for (int i = 0; i < 32; i++) deadline.submitted();
				remaining.set(deadline.expire());
			});

			long delay = TimeUnit.MICROSECONDS.toNanos(100_000);
			Assert.assertTrue("Just after a submission most of the delay remains, was " + remaining.get(),
					remaining.get() > delay / 2 && remaining.get() <= delay);

			Assert.assertTrue(checked.await(10, TimeUnit.SECONDS));
			Thread.sleep(400);
			Assert.assertEquals("32 submissions must schedule a single expiry", 1, checks.get());
		} finally {
			executor.destroy();
		}
	}

	/** A cancelled expiry never runs the check. */
	@Test(timeout = 20000)
	public void cancelledExpiryNeverRunsCheck() throws InterruptedException {
		SubmissionStream.quietCommitMicros = 100_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicInteger checks = new AtomicInteger();
		CountDownLatch checked = new CountDownLatch(1);
		QuietCommitDeadline deadline = deadline(executor, () -> {
			checks.incrementAndGet();
			checked.countDown();
		});

		try {
			executor.run(() -> {
				deadline.submitted();
				deadline.cancel();
			});

			Thread.sleep(500);
			Assert.assertEquals(0, checks.get());

			executor.run(deadline::submitted);
			Assert.assertTrue("A submission after a cancel schedules an expiry again",
					checked.await(10, TimeUnit.SECONDS));
			Assert.assertEquals(1, checks.get());
		} finally {
			executor.destroy();
		}
	}

	/** An expiry that fires after the stream's thread has been destroyed does nothing. */
	@Test(timeout = 20000)
	public void expiryAfterDestroyDoesNothing() throws InterruptedException {
		SubmissionStream.quietCommitMicros = 100_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicInteger checks = new AtomicInteger();
		QuietCommitDeadline deadline = deadline(executor, checks::incrementAndGet);

		executor.run(deadline::submitted);
		executor.destroy();

		Thread.sleep(500);
		Assert.assertEquals(0, checks.get());
	}

	/**
	 * Cancelling a pending expiry lets the timer release the expiry, and with it the stream whose
	 * check it queues, at once rather than when the expiry would have fired. The delay here is a
	 * minute, far longer than the test waits, so without the cancellation, or with a timer that
	 * keeps cancelled expiries queued, the expiry stays reachable from the timer.
	 */
	@Test(timeout = 30000)
	public void cancelReleasesExpiryFromTimer() throws InterruptedException {
		SubmissionStream.quietCommitMicros = 60_000_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		WeakReference<Runnable> reference = scheduleAndCancel(executor);

		try {
			for (int i = 0; i < 50 && reference.get() != null; i++) {
				System.gc();
				Thread.sleep(100);
			}

			Assert.assertNull("A cancelled expiry must not stay reachable from the timer", reference.get());
		} finally {
			executor.destroy();
		}
	}

	/**
	 * Creates a deadline, schedules its expiry and cancels it on the executor's thread, and returns
	 * only a weak reference to the expiry, so nothing in the test keeps it reachable.
	 *
	 * @param executor the executor the deadline's check would run on
	 * @return a weak reference to the cancelled deadline's expiry
	 */
	private static WeakReference<Runnable> scheduleAndCancel(ConfinedExecutor executor) {
		Runnable expiry = () -> executor.runLater(() -> { });
		QuietCommitDeadline deadline = new QuietCommitDeadline(expiry);
		executor.run(() -> {
			deadline.submitted();
			deadline.cancel();
		});
		return new WeakReference<>(expiry);
	}
}
