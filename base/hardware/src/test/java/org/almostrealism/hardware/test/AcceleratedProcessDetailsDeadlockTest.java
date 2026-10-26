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

import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.MemoryReplacementManager;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reproduces the lock-ordering hazard described on
 * {@link AcceleratedProcessDetails#releaseDestinationLeases()}: a {@link
 * AcceleratedProcessDetails#whenReady(Runnable)} listener must not run while this
 * instance's monitor is held by the thread running it, since a device-completion
 * callback (such as {@code releaseDestinationLeases()}) needing that same monitor
 * may run concurrently and would otherwise block behind the listener.
 *
 * <p>The test drives {@link AcceleratedProcessDetails#whenReady(Runnable)} with a
 * same-thread {@link java.util.concurrent.Executor} so that {@code notifyListeners()}
 * always executes synchronously within the {@code whenReady()}/{@code checkReady()}
 * call stack, on both the {@code Hardware.isAsync()} true and false paths. This makes
 * the reentrant-monitor hazard deterministic rather than a timing-dependent race: with
 * {@code checkReady()}/{@code whenReady()} declared {@code synchronized} at the method
 * level, the listener runs while the calling thread still holds the instance monitor,
 * so a concurrent {@code releaseDestinationLeases()} call blocks until the listener
 * (and the test's wait for it) times out. With the monitor released before the listener
 * runs, the concurrent call completes immediately.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module.</p>
 *
 * @see AcceleratedProcessDetails#releaseDestinationLeases()
 */
public class AcceleratedProcessDetailsDeadlockTest {

	/**
	 * A listener running inside {@code whenReady()} must not block a concurrent
	 * {@link AcceleratedProcessDetails#releaseDestinationLeases()} call: that call needs
	 * only the instance monitor, which must already have been released by the time the
	 * listener runs.
	 */
	@Test(timeout = 30000)
	public void whenReadyListenerDoesNotBlockConcurrentLeaseRelease() throws InterruptedException {
		MemoryReplacementManager replacementManager = new MemoryReplacementManager(null, null, null);
		AcceleratedProcessDetails details = new AcceleratedProcessDetails(
				new Object[0], 0, replacementManager, Runnable::run);

		AtomicBoolean concurrentCallCompleted = new AtomicBoolean(false);
		CountDownLatch concurrentCallFinished = new CountDownLatch(1);

		details.whenReady(() -> {
			Thread concurrent = new Thread(() -> {
				// Needs the same instance monitor as whenReady()/checkReady()/notifyListeners().
				details.releaseDestinationLeases();
				concurrentCallCompleted.set(true);
				concurrentCallFinished.countDown();
			});
			concurrent.setDaemon(true);
			concurrent.start();

			boolean finishedInTime = false;
			try {
				finishedInTime = concurrentCallFinished.await(3, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			Assert.assertTrue("releaseDestinationLeases() should not be blocked by a " +
					"whenReady() listener still running on another thread's call stack",
					finishedInTime);
		});

		Assert.assertTrue("the concurrent releaseDestinationLeases() call should have completed",
				concurrentCallCompleted.get());
	}
}
