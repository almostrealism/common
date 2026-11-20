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

package org.almostrealism.hardware.metal;

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
 * Validates {@link QuietCommitDeadline}, the timer that commits a {@link MetalCommandRunner}'s
 * open batch once it has gone quiet. No Metal device is needed: the deadline only schedules a
 * check on a {@link ConfinedExecutor}, so these tests drive it with a plain executor and count
 * the checks. Every deadline method is called on the executor's thread, as the runner does.
 *
 * <p>Plain JUnit: {@code TestSuiteBase} lives in the engine layer, above this module.</p>
 */
public class QuietCommitDeadlineTest {
	/** The quiet delay in force before each test, restored after it. */
	private int savedQuietCommitMicros;

	/** Records the shared quiet delay so each test can set its own. */
	@Before
	public void saveQuietDelay() {
		savedQuietCommitMicros = MetalCommandRunner.quietCommitMicros;
	}

	/** Restores the shared quiet delay. */
	@After
	public void restoreQuietDelay() {
		MetalCommandRunner.quietCommitMicros = savedQuietCommitMicros;
	}

	/** A dispatch schedules one expiry, which runs the check on the executor's thread. */
	@Test(timeout = 20000)
	public void expiryRunsCheckOnExecutor() throws InterruptedException {
		MetalCommandRunner.quietCommitMicros = 20_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		CountDownLatch checked = new CountDownLatch(1);
		AtomicReference<Boolean> onConfinedThread = new AtomicReference<>();
		QuietCommitDeadline deadline = new QuietCommitDeadline(executor, () -> {
			onConfinedThread.set(executor.isConfinedThread());
			checked.countDown();
		});

		try {
			executor.run(deadline::dispatched);
			Assert.assertTrue("The expiry must run the check", checked.await(10, TimeUnit.SECONDS));
			Assert.assertEquals(Boolean.TRUE, onConfinedThread.get());
		} finally {
			executor.destroy();
		}
	}

	/**
	 * Dispatches made while an expiry is pending schedule no further expiry, and
	 * {@link QuietCommitDeadline#expire()} reports the quiet time still owed since the last of
	 * them.
	 */
	@Test(timeout = 20000)
	public void onlyOneExpiryIsPendingAndExpireReportsRemainingTime() throws InterruptedException {
		MetalCommandRunner.quietCommitMicros = 100_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicInteger checks = new AtomicInteger();
		CountDownLatch checked = new CountDownLatch(1);
		QuietCommitDeadline deadline = new QuietCommitDeadline(executor, () -> {
			checks.incrementAndGet();
			checked.countDown();
		});

		try {
			AtomicLong remaining = new AtomicLong();
			executor.run(() -> {
				for (int i = 0; i < 32; i++) deadline.dispatched();
				remaining.set(deadline.expire());
			});

			long delay = TimeUnit.MICROSECONDS.toNanos(100_000);
			Assert.assertTrue("Just after a dispatch most of the delay remains, was " + remaining.get(),
					remaining.get() > delay / 2 && remaining.get() <= delay);

			Assert.assertTrue(checked.await(10, TimeUnit.SECONDS));
			Thread.sleep(400);
			Assert.assertEquals("32 dispatches must schedule a single expiry", 1, checks.get());
		} finally {
			executor.destroy();
		}
	}

	/** A cancelled expiry never runs the check. */
	@Test(timeout = 20000)
	public void cancelledExpiryNeverRunsCheck() throws InterruptedException {
		MetalCommandRunner.quietCommitMicros = 100_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicInteger checks = new AtomicInteger();
		CountDownLatch checked = new CountDownLatch(1);
		QuietCommitDeadline deadline = new QuietCommitDeadline(executor, () -> {
			checks.incrementAndGet();
			checked.countDown();
		});

		try {
			executor.run(() -> {
				deadline.dispatched();
				deadline.cancel();
			});

			Thread.sleep(500);
			Assert.assertEquals(0, checks.get());

			executor.run(deadline::dispatched);
			Assert.assertTrue("A dispatch after a cancel schedules an expiry again",
					checked.await(10, TimeUnit.SECONDS));
			Assert.assertEquals(1, checks.get());
		} finally {
			executor.destroy();
		}
	}

	/** An expiry that fires after the executor has been destroyed does nothing. */
	@Test(timeout = 20000)
	public void expiryAfterDestroyDoesNothing() throws InterruptedException {
		MetalCommandRunner.quietCommitMicros = 100_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicInteger checks = new AtomicInteger();
		QuietCommitDeadline deadline = new QuietCommitDeadline(executor, checks::incrementAndGet);

		executor.run(deadline::dispatched);
		executor.destroy();

		Thread.sleep(500);
		Assert.assertEquals(0, checks.get());
	}

	/**
	 * Cancelling a pending expiry lets the timer release the deadline, and with it the runner its
	 * check belongs to, at once rather than when the expiry would have fired. The delay here is
	 * a minute, far longer than the test waits, so without the cancellation, or with a timer that
	 * keeps cancelled expiries queued, the deadline stays reachable from the timer.
	 */
	@Test(timeout = 30000)
	public void cancelReleasesDeadlineFromTimer() throws InterruptedException {
		MetalCommandRunner.quietCommitMicros = 60_000_000;
		ConfinedExecutor executor = new ConfinedExecutor();
		WeakReference<QuietCommitDeadline> reference = scheduleAndCancel(executor);

		try {
			for (int i = 0; i < 50 && reference.get() != null; i++) {
				System.gc();
				Thread.sleep(100);
			}

			Assert.assertNull("A cancelled expiry must not keep the deadline reachable", reference.get());
		} finally {
			executor.destroy();
		}
	}

	/**
	 * Creates a deadline, schedules its expiry and cancels it on the executor's thread, and returns
	 * only a weak reference to it, so nothing in the test keeps it reachable.
	 *
	 * @param executor the executor the deadline's check runs on
	 * @return a weak reference to the cancelled deadline
	 */
	private static WeakReference<QuietCommitDeadline> scheduleAndCancel(ConfinedExecutor executor) {
		QuietCommitDeadline deadline = new QuietCommitDeadline(executor, () -> { });
		executor.run(() -> {
			deadline.dispatched();
			deadline.cancel();
		});
		return new WeakReference<>(deadline);
	}
}
