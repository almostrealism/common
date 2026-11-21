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

package org.almostrealism.hardware.metal;

import org.almostrealism.hardware.test.SemaphoreChainBatchingTest;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that a {@link CommandBatch} committed without a completed handler, because registering
 * one failed for its buffer, still reports its completion to a {@link CommandBatch#whenFinished
 * finish listener}, instead of leaving the listener to wait for an unrelated drain.
 *
 * <p>The test plays the runner's executor thread itself, and the {@code onFinished} runnable given
 * to {@link CommandBatch#commit(Runnable)} stands for the drain the runner would schedule. Each
 * batch waits on a host-signaled bridge, so its buffer cannot finish until the test signals it.</p>
 */
public class CommandBatchUnreportedCompletionTest extends TestSuiteBase {

	/**
	 * A listener registered before the commit of a batch whose handler cannot be registered is
	 * reported once the buffer finishes, not before, and the buffer stays retained until the
	 * batch is drained.
	 */
	@Test(timeout = 60000)
	public void listenerBeforeCommitIsReported() throws InterruptedException {
		MTLCommandQueue queue = queue();
		if (queue == null) return;

		MTLEvent gate = queue.getDevice().newSharedEvent();
		CommandBatch batch = gatedBatch(queue, gate);
		AtomicInteger notified = new AtomicInteger();
		batch.whenFinished(notified::incrementAndGet);

		CountDownLatch reported = new CountDownLatch(1);
		batch.commit(reported::countDown);

		assertFalse("The completion must not be reported before the buffer finishes",
				reported.await(200, TimeUnit.MILLISECONDS));
		assertFalse(batch.isFinished());

		gate.setSignaledValue(1);
		assertTrue("The completion must be reported without a completed handler",
				reported.await(30, TimeUnit.SECONDS));
		assertTrue(batch.isFinished());
		assertFalse("The buffer stays retained until the batch is drained",
				batch.getBuffer().isReleased());
		assertEquals("The listener is notified only by the runner's drain", 0, notified.get());

		assertTrue(batch.notifyIfFinished());
		assertEquals(1, notified.get());

		batch.drain();
		assertEquals("A listener is notified only once", 1, notified.get());
		assertTrue("A drained batch with no waiter releases its buffer", batch.getBuffer().isReleased());
	}

	/**
	 * Listeners registered after the commit of a batch whose handler cannot be registered are
	 * reported too, and two of them start a single substitute report.
	 */
	@Test(timeout = 60000)
	public void listenersAfterCommitAreReportedOnce() throws InterruptedException {
		MTLCommandQueue queue = queue();
		if (queue == null) return;

		MTLEvent gate = queue.getDevice().newSharedEvent();
		CommandBatch batch = gatedBatch(queue, gate);
		AtomicInteger reports = new AtomicInteger();
		CountDownLatch reported = new CountDownLatch(1);
		batch.commit(() -> {
			reports.incrementAndGet();
			reported.countDown();
		});

		AtomicInteger notified = new AtomicInteger();
		batch.whenFinished(notified::incrementAndGet);
		batch.whenFinished(notified::incrementAndGet);
		assertEquals("No listener may be notified before the buffer finishes", 0, notified.get());

		gate.setSignaledValue(1);
		assertTrue("The completion must be reported without a completed handler",
				reported.await(30, TimeUnit.SECONDS));
		Thread.sleep(200);
		assertEquals("The completion is reported once however many listeners wait", 1, reports.get());

		batch.drain();
		assertEquals("Both listeners are notified by the drain", 2, notified.get());
		assertTrue(batch.getBuffer().isReleased());
	}

	/**
	 * A listener registered after a batch without a completed handler has already finished is
	 * notified at once, and the completion is still reported, exactly once, so that the runner
	 * drains the batch instead of leaving it retained until an unrelated wait.
	 */
	@Test(timeout = 60000)
	public void listenerAfterFinishStillReports() throws InterruptedException {
		MTLCommandQueue queue = queue();
		if (queue == null) return;

		MTLEvent gate = queue.getDevice().newSharedEvent();
		CommandBatch batch = gatedBatch(queue, gate);
		AtomicInteger reports = new AtomicInteger();
		CountDownLatch reported = new CountDownLatch(1);
		batch.commit(() -> {
			reports.incrementAndGet();
			reported.countDown();
		});

		gate.setSignaledValue(1);
		batch.awaitCompletionUninterruptibly();
		assertTrue(batch.isFinished());
		assertEquals("Nothing has needed the completion reported yet", 0, reports.get());

		AtomicInteger notified = new AtomicInteger();
		batch.whenFinished(notified::incrementAndGet);
		assertEquals("A listener on a finished batch is notified at once", 1, notified.get());

		assertTrue("The completion of a finished batch must still be reported to the runner",
				reported.await(30, TimeUnit.SECONDS));

		batch.whenFinished(notified::incrementAndGet);
		Thread.sleep(200);
		assertEquals("The completion is reported once however many listeners arrive", 1, reports.get());
		assertEquals(2, notified.get());

		batch.drain();
		assertEquals("A listener is notified only once", 2, notified.get());
		assertTrue(batch.getBuffer().isReleased());
	}

	/**
	 * A batch committed without a completed handler and with no listener starts no thread to
	 * report its completion, which then only a wait or drain observes, as before.
	 */
	@Test(timeout = 60000)
	public void noListenerStartsNoReport() throws InterruptedException {
		MTLCommandQueue queue = queue();
		if (queue == null) return;

		MTLEvent gate = queue.getDevice().newSharedEvent();
		CommandBatch batch = gatedBatch(queue, gate);
		AtomicInteger reports = new AtomicInteger();
		batch.commit(reports::incrementAndGet);

		gate.setSignaledValue(1);
		batch.awaitCompletionUninterruptibly();
		Thread.sleep(200);
		assertEquals("Nothing needs the completion reported", 0, reports.get());

		batch.drain();
		assertTrue(batch.getBuffer().isReleased());
	}

	/**
	 * Opens a batch, in a command buffer of the given queue on which no completed handler can be
	 * registered (as when no JNI global reference can be created for one), that waits on the
	 * given host-signaled bridge, which the batch releases when it is drained.
	 *
	 * @param queue the queue to create the buffer from
	 * @param gate  the bridge the batch waits for to be signaled to {@code 1}
	 * @return the open batch
	 */
	private static CommandBatch gatedBatch(MTLCommandQueue queue, MTLEvent gate) {
		MTLCommandBuffer buffer = new MTLCommandBuffer(MTL.commandBuffer(queue.getNativePointer())) {
			@Override
			public boolean addCompletedHandler(Runnable handler) {
				return false;
			}
		};

		CommandBatch batch = new CommandBatch(buffer, null, 1);
		batch.encodeBridge(gate);
		return batch;
	}

	/**
	 * Returns the command queue of an available Metal context, or {@code null} if there is none.
	 *
	 * @return the queue, or {@code null}
	 */
	private MTLCommandQueue queue() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return null;
		}

		return metal.getMtlQueue();
	}
}
