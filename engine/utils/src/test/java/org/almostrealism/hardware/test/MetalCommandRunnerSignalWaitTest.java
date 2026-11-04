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

import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.metal.MTLCommandBuffer;
import org.almostrealism.hardware.metal.MTLCommandQueue;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertNotSame;

/**
 * Validates how a host wait for a {@link MetalCommandRunner} dispatch observes that the GPU has
 * finished: by the timeline signal of the dispatch's command buffer, polled for up to
 * {@link MetalCommandRunner#completionSpinMicros}, rather than by the buffer's completion, which
 * Metal reports later. A wait settled by the signal returns with the buffer drained exactly as
 * after a completed wait, and the signal never settles a wait that it cannot tell apart from a
 * failure: a buffer reported as failed, or a timeline released by a buffer closed after the one
 * being waited for.
 *
 * <p>Every test runs on a runner of its own over a command queue of its own, created after the
 * polling window has been set, since a runner reads the window when it is created. The window is
 * generous so that the GPU always signals within it, which makes the expected path
 * deterministic.</p>
 */
public class MetalCommandRunnerSignalWaitTest extends TestSuiteBase {
	/** A polling window far longer than an empty command buffer takes to reach the GPU and run. */
	private static final int WINDOW_MICROS = 1_000_000;

	/** The number of dispatches each repeated scenario waits for. */
	private static final int WAITS = 20;

	/** The error text of a buffer the failing runner reports as failed. */
	private static final String FAILURE = "Reported command buffer failure";

	/**
	 * Each of a sequence of dispatches, each waited for at once, is settled by its buffer's
	 * timeline signal, and by the time each wait returns the dispatch's released-memory callback
	 * and a callback registered for the buffer's completion have both run, on the runner's
	 * thread rather than the waiting one.
	 */
	@Test(timeout = 120000)
	public void waitIsSettledBySignalWithCallbacksRun() {
		withRunner(WINDOW_MICROS, MetalCommandRunner::new, runner -> {
			Thread waiting = Thread.currentThread();

			for (int i = 0; i < WAITS; i++) {
				AtomicBoolean released = new AtomicBoolean();
				AtomicReference<Thread> completed = new AtomicReference<>();

				MetalSemaphore dispatch = runner.submit(null, buffer -> { }, null, () -> released.set(true));
				dispatch.whenComplete(() -> completed.set(Thread.currentThread()));
				dispatch.waitFor();

				assertTrue("The released-memory callback must have run when the wait returns", released.get());
				assertTrue("The completion callback must have run when the wait returns", completed.get() != null);
				assertNotSame("Completion callbacks run on the runner's thread", waiting, completed.get());
			}

			assertEquals("Every wait must be settled by its buffer's signal",
					(double) WAITS, (double) runner.getSignalCompletionCount());
		});
	}

	/**
	 * A buffer that the runner reports as failed is not settled by its signal, although the GPU
	 * executed it and signaled the timeline: the wait falls back to the buffer's completion,
	 * which reports the failure, and throws it.
	 */
	@Test(timeout = 120000)
	public void failedBufferIsNotSettledBySignal() {
		withRunner(WINDOW_MICROS, FailedBufferRunner::new, runner -> {
			MetalSemaphore dispatch = runner.submit(null, buffer -> { }, null, null);

			Throwable failure = NativeExecutionFailureTest.failureOf(dispatch);
			assertTrue("The reported failure must be thrown, got " + failure,
					failure instanceof HardwareException && failure.getMessage().contains(FAILURE));
			assertEquals("A failed buffer must not be settled by its signal",
					0.0, (double) runner.getSignalCompletionCount());
		});
	}

	/**
	 * Once a later command buffer has been closed, the timeline can be released past the buffer
	 * being waited for without that buffer having executed, so its signal does not settle the
	 * wait, which waits for the buffer's completion instead. A wait for the dispatch in the most
	 * recently closed buffer, or in the open buffer the wait closes, is settled by the signal as
	 * usual once the buffers before it have been drained.
	 */
	@Test(timeout = 120000)
	public void laterClosedBufferDefersToCompletion() {
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;

		try {
			withRunner(WINDOW_MICROS, MetalCommandRunner::new, runner -> {
				MetalSemaphore first = runner.submit(null, buffer -> { }, null, null);

				// Each bridged dispatch closes the open buffer and starts another
				MetalSemaphore second = runner.submit(null, buffer -> { }, MetalCommandFailureTest.settled(), null);
				MetalSemaphore third = runner.submit(null, buffer -> { }, MetalCommandFailureTest.settled(), null);
				assertNotSame(first.getCommandBuffer(), second.getCommandBuffer());
				assertNotSame(second.getCommandBuffer(), third.getCommandBuffer());

				first.waitFor();
				assertEquals("A buffer followed by a closed buffer must wait for its completion",
						0.0, (double) runner.getSignalCompletionCount());

				second.waitFor();
				assertEquals("The most recently closed buffer must be settled by its signal",
						1.0, (double) runner.getSignalCompletionCount());

				third.waitFor();
				assertEquals("The buffer the wait closes must be settled by its signal",
						2.0, (double) runner.getSignalCompletionCount());
			});
		} finally {
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * With a polling window of zero, no wait polls the timeline: every wait is settled by its
	 * buffer's completion, with its callbacks run, as before polling was introduced.
	 */
	@Test(timeout = 120000)
	public void zeroWindowWaitsForCompletion() {
		withRunner(0, MetalCommandRunner::new, runner -> {
			for (int i = 0; i < WAITS; i++) {
				AtomicBoolean released = new AtomicBoolean();
				runner.submit(null, buffer -> { }, null, () -> released.set(true)).waitFor();
				assertTrue("The released-memory callback must have run when the wait returns", released.get());
			}

			assertEquals("No wait may be settled by a signal when polling is disabled",
					0.0, (double) runner.getSignalCompletionCount());
		});
	}

	/**
	 * Runs {@code test} against a runner created by {@code factory} over a new command queue,
	 * with the given polling window in force when the runner is created, and destroys the runner
	 * and releases the queue afterwards. Does nothing, apart from logging, when no Metal context
	 * is available.
	 *
	 * @param windowMicros the polling window the runner is created with
	 * @param factory      creates the runner over the queue
	 * @param test         the scenario to run against the runner
	 */
	private void withRunner(int windowMicros, Function<MTLCommandQueue, MetalCommandRunner> factory,
							RunnerScenario test) {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int window = MetalCommandRunner.completionSpinMicros;
		MetalCommandRunner.completionSpinMicros = windowMicros;
		MTLCommandQueue queue = metal.getMtlDevice().newCommandQueue();
		MetalCommandRunner runner;

		try {
			runner = factory.apply(queue);
		} finally {
			MetalCommandRunner.completionSpinMicros = window;
		}

		try {
			test.run(runner);
		} finally {
			runner.destroy();
			queue.release();
		}
	}

	/** A scenario run against a dedicated runner. */
	@FunctionalInterface
	private interface RunnerScenario {
		/**
		 * Runs the scenario.
		 *
		 * @param runner the runner under test
		 */
		void run(MetalCommandRunner runner);
	}

	/**
	 * A runner that reports every command buffer as failed once it completes, as Metal does for a
	 * buffer the GPU faulted on. The buffers still execute and signal the timeline; only their
	 * reported status differs.
	 */
	private static final class FailedBufferRunner extends MetalCommandRunner {
		/**
		 * Creates a runner on the given queue.
		 *
		 * @param queue the Metal command queue
		 */
		private FailedBufferRunner(MTLCommandQueue queue) {
			super(queue);
		}

		@Override
		protected String errorOf(MTLCommandBuffer buffer) {
			return FAILURE;
		}
	}
}
