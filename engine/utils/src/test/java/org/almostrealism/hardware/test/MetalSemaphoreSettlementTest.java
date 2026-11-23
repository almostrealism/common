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
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.metal.MetalCommand;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that {@link MetalSemaphore#onSettled(Runnable)}, and the
 * {@link Semaphore#onComplete(Runnable)} built on it, deliver their callbacks from the command
 * buffer's completion rather than from a thread parked in {@link MetalSemaphore#waitFor()} for
 * each pending callback.
 *
 * <p>A burst of completion callbacks registered on Metal work that has not finished used to hold
 * one pooled callback thread per callback for as long as the work was outstanding, so the JVM's
 * thread count grew with the number of pending callbacks.</p>
 */
public class MetalSemaphoreSettlementTest extends TestSuiteBase {
	/** A dispatch that encodes nothing. */
	private static final MetalCommand NOTHING = buffer -> { };

	/** The number of callbacks registered on pending work. */
	private static final int CALLBACKS = 200;

	/**
	 * Registers many {@code onComplete} callbacks on dispatches whose command buffer cannot be
	 * committed yet (it waits for a host-signaled bridge on a foreign completion that has not
	 * settled), checks that the thread count does not grow with them, and then releases the work
	 * and checks that every callback runs.
	 */
	@Test(timeout = 120000)
	public void pendingCallbacksHoldNoThreads() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());
		DefaultLatchSemaphore gate = new DefaultLatchSemaphore((Semaphore) null, 1);
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();

		try {
			List<MetalSemaphore> dispatches = new ArrayList<>();
			dispatches.add(runner.submit(null, NOTHING, gate, null));
			for (int i = 1; i < CALLBACKS; i++) {
				dispatches.add(runner.submit(null, NOTHING, null, null));
			}

			CountDownLatch completed = new CountDownLatch(CALLBACKS);
			int before = threads.getThreadCount();
			dispatches.forEach(d -> d.onComplete(completed::countDown));

			// Give any thread a callback might have started time to appear
			Thread.sleep(500);
			int pending = threads.getThreadCount();
			log("threadsBefore=" + before + " threadsWhilePending=" + pending +
					" pendingCallbacks=" + completed.getCount());

			assertEquals("No callback may run before its work has settled",
					CALLBACKS, completed.getCount());
			assertTrue("Pending callbacks must not hold a thread each: " + (pending - before) +
							" threads were added for " + CALLBACKS + " callbacks",
					pending - before < CALLBACKS / 10);

			threads.resetPeakThreadCount();
			gate.countDown();
			assertTrue("Every callback must run once the work has completed",
					completed.await(60, TimeUnit.SECONDS));

			int peak = threads.getPeakThreadCount();
			log("peakThreadsDuringDelivery=" + peak + " threadsAfterDelivery=" + threads.getThreadCount());
			assertTrue("Delivering the settled callbacks must not start a thread each: " + (peak - before) +
							" threads were added for " + CALLBACKS + " callbacks",
					peak - before < CALLBACKS / 10);
		} finally {
			gate.countDown();
			runner.destroy();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * A dispatch whose command buffer failed still settles its {@code onSettled} callbacks,
	 * without anything waiting for it, and skips its {@code onComplete} callbacks.
	 */
	@Test(timeout = 60000)
	public void failedWorkSettlesButSkipsCompletionCallbacks() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandFailureTest.FailingRunner runner =
				new MetalCommandFailureTest.FailingRunner(metal.getMtlQueue());

		try {
			List<MetalSemaphore> dispatches = new ArrayList<>();
			for (int i = 0; i < 16; i++) {
				dispatches.add(runner.submit(null, NOTHING, null, null));
			}

			runner.failBufferOf(dispatches.get(0));

			AtomicInteger completions = new AtomicInteger();
			CountDownLatch settled = new CountDownLatch(dispatches.size());
			for (MetalSemaphore d : dispatches) {
				d.onComplete(completions::incrementAndGet);
				d.onSettled(settled::countDown);
			}

			assertTrue("Every onSettled callback must run after the failure",
					settled.await(30, TimeUnit.SECONDS));

			// The onComplete callbacks were registered first, so they have been delivered too
			Thread.sleep(200);
			assertEquals("No onComplete callback may run after a failure", 0, completions.get());
		} finally {
			runner.destroy();
		}
	}
}
