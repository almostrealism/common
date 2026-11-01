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
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.metal.MTLCommandBuffer;
import org.almostrealism.hardware.metal.MTLCommandQueue;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotSame;

/**
 * Validates when {@link MetalCommandRunner} commits its open command buffer before it is full:
 * once the buffer holds {@link MetalCommandRunner#minIdleCommit} dispatches and the GPU has
 * finished everything committed before it, so the GPU starts on that work while the host is
 * still encoding what follows. A buffer that cannot reach the GPU at once, because it waits for
 * a host-signaled bridge, is never committed early.
 *
 * <p>Each test sets the threshold itself, small enough that the buffer stays far below
 * {@link MetalCommandRunner#MAX_OPEN}, and starts from a drained runner, so the GPU is idle
 * when the dispatches are submitted.</p>
 */
public class MetalCommandRunnerIdleCommitTest extends TestSuiteBase {
	/** The threshold every test runs with. */
	private static final int THRESHOLD = 8;

	/**
	 * With the GPU idle, the open buffer is committed as soon as it holds the threshold, without
	 * anything waiting for it, and the commit is attributed to the GPU running out of work.
	 */
	@Test(timeout = 60000)
	public void idleRunnerCommitsOnceBufferHoldsThreshold() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		int threshold = MetalCommandRunner.minIdleCommit;
		MetalCommandRunner.minIdleCommit = THRESHOLD;

		try {
			runner.submit(null, buffer -> { }, null, null).waitFor();
			long commits = runner.getCommitCount();
			long idleCommits = runner.getIdleCommitCount();

			for (int i = 0; i < THRESHOLD - 1; i++) {
				runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals("A buffer below the threshold must stay open",
					(double) commits, (double) runner.getCommitCount());

			Semaphore last = runner.submit(null, buffer -> { }, null, null);

			assertEquals("An idle GPU must be given the buffer once it holds the threshold",
					(double) (commits + 1), (double) runner.getCommitCount());
			assertEquals((double) (idleCommits + 1), (double) runner.getIdleCommitCount());

			last.waitFor();
			assertEquals("Waiting for a committed dispatch must not commit again",
					(double) (commits + 1), (double) runner.getCommitCount());
		} finally {
			MetalCommandRunner.minIdleCommit = threshold;
		}
	}

	/**
	 * A buffer committed early stays with the runner until something waits for it, so the next
	 * buffer is judged against it: once the GPU has finished that buffer, which nothing has
	 * waited for, the next buffer is committed early as well when it reaches the threshold.
	 */
	@Test(timeout = 60000)
	public void idleRunnerCommitsAgainOnceUnwaitedBufferFinishes() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		int threshold = MetalCommandRunner.minIdleCommit;
		MetalCommandRunner.minIdleCommit = THRESHOLD;

		try {
			runner.submit(null, buffer -> { }, null, null).waitFor();
			long commits = runner.getCommitCount();
			long idleCommits = runner.getIdleCommitCount();

			MetalSemaphore first = null;
			for (int i = 0; i < THRESHOLD; i++) {
				first = runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals((double) (commits + 1), (double) runner.getCommitCount());

			awaitFinished(first);

			Semaphore last = null;
			for (int i = 0; i < THRESHOLD; i++) {
				last = runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals("A buffer must be committed early once the GPU has finished the one before it",
					(double) (commits + 2), (double) runner.getCommitCount());
			assertEquals((double) (idleCommits + 2), (double) runner.getIdleCommitCount());

			last.waitFor();
		} finally {
			MetalCommandRunner.minIdleCommit = threshold;
		}
	}

	/**
	 * A buffer that waits for a host-signaled bridge is not committed early, however many
	 * dispatches it holds: it could not run until the bridge is signaled, and closing it would
	 * only hold it back on the host. It is committed once it is waited for, after the foreign
	 * work it depends on has settled.
	 */
	@Test(timeout = 60000)
	public void bufferWaitingForBridgeIsNotCommittedEarly() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		int threshold = MetalCommandRunner.minIdleCommit;
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.minIdleCommit = THRESHOLD;
		MetalCommandRunner.enableHostSignaledBridges = true;

		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore((Semaphore) null, 1);

		try {
			runner.submit(null, buffer -> { }, null, null).waitFor();
			long commits = runner.getCommitCount();
			long idleCommits = runner.getIdleCommitCount();

			Semaphore bridged = runner.submit(null, buffer -> { }, foreign, null);
			Semaphore last = bridged;
			for (int i = 0; i < 4 * THRESHOLD; i++) {
				last = runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals("A buffer waiting for a bridge must not be committed early",
					(double) commits, (double) runner.getCommitCount());
			assertEquals((double) idleCommits, (double) runner.getIdleCommitCount());

			foreign.countDown();
			last.waitFor();
			bridged.waitFor();

			assertEquals((double) (commits + 1), (double) runner.getCommitCount());
			assertEquals((double) idleCommits, (double) runner.getIdleCommitCount());
		} finally {
			foreign.countDown();
			MetalCommandRunner.minIdleCommit = threshold;
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * A command buffer that finished with an error never signaled its timeline value, so it is
	 * not proof that the GPU has run out of work: a dependent of one of its dispatches would wait
	 * on the GPU until the watchdog killed it. The next buffer is therefore not committed early
	 * once the previous one has failed, however many dispatches it holds.
	 */
	@Test(timeout = 60000)
	public void failedBufferIsNotTreatedAsIdleDevice() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		FailingRunner runner = new FailingRunner(metal.getMtlQueue());
		int threshold = MetalCommandRunner.minIdleCommit;
		MetalCommandRunner.minIdleCommit = THRESHOLD;

		try {
			MetalSemaphore first = null;
			for (int i = 0; i < THRESHOLD; i++) {
				first = runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals("An idle GPU must be given the first buffer once it holds the threshold",
					1.0, (double) runner.getIdleCommitCount());
			runner.failing = first.getCommandBuffer();

			awaitFinished(first);

			MetalSemaphore dependent = runner.submit(null, buffer -> { }, first, null);
			for (int i = 0; i < 4 * THRESHOLD; i++) {
				runner.submit(null, buffer -> { }, null, null);
			}

			assertNotSame(first.getCommandBuffer(), dependent.getCommandBuffer());
			assertEquals("A buffer after a failed one must not be committed early",
					1.0, (double) runner.getCommitCount());
			assertEquals(1.0, (double) runner.getIdleCommitCount());
		} finally {
			runner.destroy();
			MetalCommandRunner.minIdleCommit = threshold;
		}
	}

	/**
	 * Only the last committed buffer decides whether the GPU is idle. When an earlier buffer
	 * failed but a later one finished without an error, the later one has signaled a timeline
	 * value above every value of the failed one, so a buffer that waits for the failed buffer is
	 * not stalled and is committed early. The failure is not lost: it is recorded when the failed
	 * buffer is drained, and waiting for the dependent dispatch reports it.
	 */
	@Test(timeout = 60000)
	public void earlierFailedBufferIsReportedWhenWaitedFor() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		FailingRunner runner = new FailingRunner(metal.getMtlQueue());
		int threshold = MetalCommandRunner.minIdleCommit;
		MetalCommandRunner.minIdleCommit = THRESHOLD;

		try {
			MetalSemaphore first = null;
			for (int i = 0; i < THRESHOLD; i++) {
				first = runner.submit(null, buffer -> { }, null, null);
			}

			awaitFinished(first);

			MetalSemaphore second = null;
			for (int i = 0; i < THRESHOLD; i++) {
				second = runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals(2.0, (double) runner.getIdleCommitCount());
			awaitFinished(second);
			runner.failing = first.getCommandBuffer();

			MetalSemaphore dependent = runner.submit(null, buffer -> { }, first, null);
			for (int i = 0; i < THRESHOLD - 1; i++) {
				runner.submit(null, buffer -> { }, null, null);
			}

			assertNotSame(second.getCommandBuffer(), dependent.getCommandBuffer());
			assertEquals("A later buffer that finished must let the next one be committed early",
					3.0, (double) runner.getCommitCount());
			assertEquals(3.0, (double) runner.getIdleCommitCount());

			try {
				dependent.waitFor();
				throw new AssertionError("Waiting after a failed buffer must report the failure");
			} catch (HardwareException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains(FailingRunner.ERROR));
			}
		} finally {
			runner.destroy();
			MetalCommandRunner.minIdleCommit = threshold;
		}
	}

	/**
	 * A threshold of zero turns early commits off: the open buffer is committed only when it is
	 * waited for (or reaches {@link MetalCommandRunner#MAX_OPEN}), however idle the GPU is.
	 */
	@Test(timeout = 60000)
	public void zeroThresholdLeavesBufferOpen() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		int threshold = MetalCommandRunner.minIdleCommit;
		MetalCommandRunner.minIdleCommit = 0;

		try {
			runner.submit(null, buffer -> { }, null, null).waitFor();
			long commits = runner.getCommitCount();

			Semaphore last = null;
			for (int i = 0; i < 4 * THRESHOLD; i++) {
				last = runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals("With early commits off the buffer must stay open",
					(double) commits, (double) runner.getCommitCount());

			last.waitFor();
			assertEquals((double) (commits + 1), (double) runner.getCommitCount());
		} finally {
			MetalCommandRunner.minIdleCommit = threshold;
		}
	}

	/**
	 * Waits, without committing or draining anything, until the GPU has finished the command
	 * buffer a dispatch was encoded into.
	 *
	 * @param dispatch a dispatch whose buffer has been committed
	 */
	private void awaitFinished(MetalSemaphore dispatch) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!dispatch.getCommandBuffer().isFinished()) {
			assertTrue("The GPU must finish an empty command buffer", System.nanoTime() < deadline);
			Thread.onSpinWait();
		}
	}

	/**
	 * A runner of its own on the Metal context's queue, since a failure is permanent for its
	 * runner, that reports one chosen command buffer as failed, as Metal does for a buffer the GPU
	 * watchdog killed. The buffer still executes; only its reported status differs.
	 */
	private static final class FailingRunner extends MetalCommandRunner {
		/** The error reported for the failing command buffer. */
		private static final String ERROR = "Simulated command buffer failure";

		/** The command buffer to report as failed, or {@code null}. */
		private volatile MTLCommandBuffer failing;

		/**
		 * Creates a runner on the given queue.
		 *
		 * @param queue the Metal command queue
		 */
		private FailingRunner(MTLCommandQueue queue) {
			super(queue);
		}

		@Override
		protected String errorOf(MTLCommandBuffer buffer) {
			return buffer == failing ? ERROR : super.errorOf(buffer);
		}
	}
}
