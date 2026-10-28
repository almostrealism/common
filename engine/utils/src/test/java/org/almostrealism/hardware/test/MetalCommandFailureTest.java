/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.metal.MTLCommandBuffer;
import org.almostrealism.hardware.metal.MTLCommandQueue;
import org.almostrealism.hardware.metal.MetalCommand;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * Verifies that a Metal command buffer that completes in error fails every waiter and dependent
 * of its dispatches, rather than settling them as if the work had run, and that a failed foreign
 * dependency of a bridged dispatch reaches that dispatch's waiters without a GPU watchdog kill.
 *
 * <p>Each test uses its own {@link FailingRunner} on the Metal context's queue, never the
 * context's shared runner: a recorded failure is permanent for its runner. The runner reports a
 * chosen buffer as failed through {@link MetalCommandRunner}'s error-status hook, so the real
 * commit, wait, drain and composition code runs without depending on the GPU watchdog.</p>
 */
public class MetalCommandFailureTest extends TestSuiteBase {
	/** The error text the failing runner reports. */
	private static final String ERROR = "Simulated command buffer timeout";

	/** A dispatch that encodes nothing. */
	private static final MetalCommand NOTHING = buffer -> { };

	/**
	 * Every wait for a dispatch of the failed buffer throws, including a wait for an earlier
	 * dispatch of that buffer that arrives after another waiter drained it, and so does a wait for
	 * a dispatch submitted to the runner afterwards.
	 */
	@Test(timeout = 60000)
	public void failedBufferFailsEveryLaterWaiter() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		FailingRunner runner = new FailingRunner(metal.getMtlQueue());

		try {
			MetalSemaphore first = runner.submit(null, NOTHING, null, null);
			MetalSemaphore second = runner.submit(null, NOTHING, null, null);
			runner.failBufferOf(first);

			assertMetalFailure(NativeExecutionFailureTest.failureOf(second));
			assertEquals(1, runner.getErrorCompletionCount());

			assertMetalFailure(NativeExecutionFailureTest.failureOf(first));
			assertMetalFailure(NativeExecutionFailureTest.failureOf(second));

			MetalSemaphore later = runner.submit(null, NOTHING, null, null);
			assertMetalFailure(NativeExecutionFailureTest.failureOf(later));
		} finally {
			runner.destroy();
		}

		assertEquals(1, runner.getErrorCompletionCount());
	}

	/**
	 * Several threads waiting for the failed buffer at once all throw, not only the one that
	 * happens to drain it.
	 */
	@Test(timeout = 60000)
	public void concurrentWaitersAllFail() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		FailingRunner runner = new FailingRunner(metal.getMtlQueue());

		try {
			MetalSemaphore dispatch = runner.submit(null, NOTHING, null, null);
			runner.failBufferOf(dispatch);

			List<Throwable> failures = new ArrayList<>();
			List<Thread> waiters = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				waiters.add(new Thread(() -> {
					Throwable t = NativeExecutionFailureTest.failureOf(dispatch);
					synchronized (failures) {
						failures.add(t);
					}
				}));
			}

			waiters.forEach(Thread::start);
			for (Thread t : waiters) t.join(30000);

			assertEquals(waiters.size(), failures.size());
			failures.forEach(MetalCommandFailureTest::assertMetalFailure);
		} finally {
			runner.destroy();
		}
	}

	/**
	 * The failed buffer is followed by an independent buffer and then by a dispatch that depends
	 * on the failed one. The dependent's buffer completes without a Metal error of its own (the
	 * timeline it waits on is released by the later buffers' signals), and a single trailing host
	 * wait for it &mdash; the shape of a long asynchronous chain waited once at the end &mdash; must
	 * still throw with the original error text. Only the one failed buffer is reported as an error
	 * completion.
	 */
	@Test(timeout = 60000)
	public void dependentReleasedByLaterSignalStillFails() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		FailingRunner runner = new FailingRunner(metal.getMtlQueue());

		try {
			MetalSemaphore failed = runner.submit(null, NOTHING, null, null);
			runner.failBufferOf(failed);

			// A bridged dispatch closes the open buffer and starts another, so each of these
			// independent dispatches occupies a buffer of its own
			MetalSemaphore independent = runner.submit(null, NOTHING, settled(), null);
			MetalSemaphore next = runner.submit(null, NOTHING, settled(), null);
			MetalSemaphore dependent = runner.submit(null, NOTHING, failed, null);

			assertNotSame(failed.getCommandBuffer(), independent.getCommandBuffer());
			assertNotSame(independent.getCommandBuffer(), dependent.getCommandBuffer());
			assertSame(next.getCommandBuffer(), dependent.getCommandBuffer());

			assertMetalFailure(NativeExecutionFailureTest.failureOf(dependent));
			assertMetalFailure(NativeExecutionFailureTest.failureOf(failed));
		} finally {
			runner.destroy();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}

		assertEquals(1, runner.getErrorCompletionCount());
	}

	/**
	 * A native dispatch chained on a Metal completion whose buffer failed does not run, and its
	 * completion rethrows the Metal failure.
	 */
	@Test(timeout = 60000)
	public void nativeDispatchAfterFailedMetalCompletionFails() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		FailingRunner runner = new FailingRunner(metal.getMtlQueue());
		ScriptedInstructionSet kernel = new ScriptedInstructionSet(1, null);

		try (PackedCollection arg = new PackedCollection(4)) {
			MetalSemaphore failed = runner.submit(null, NOTHING, null, null);
			runner.failBufferOf(failed);

			Semaphore dependent = kernel.execution().accept(new Object[] { arg }, failed);

			assertMetalFailure(NativeExecutionFailureTest.failureOf(dependent));
			assertEquals(0, kernel.getInvocations());
			NativeExecutionFailureTest.awaitGuardReleased(arg);
		} finally {
			runner.destroy();
		}
	}

	/**
	 * A Metal dispatch bridged on a native dispatch whose worker fails throws the native cause
	 * from its wait, promptly: the bridge is released when the foreign work settles, so the GPU
	 * does not wait for a watchdog kill and no buffer completes in error.
	 */
	@Test(timeout = 60000)
	public void bridgedDispatchOnFailedNativeWorkFails() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		FailingRunner runner = new FailingRunner(metal.getMtlQueue());
		RuntimeException cause = new RuntimeException("native worker failed");
		ScriptedInstructionSet kernel = new ScriptedInstructionSet(1, cause);

		try (PackedCollection arg = new PackedCollection(4)) {
			Semaphore foreign = kernel.execution().accept(new Object[] { arg }, null);
			MetalSemaphore bridged = runner.submit(null, NOTHING, foreign, null);

			long start = System.currentTimeMillis();
			Throwable failure = NativeExecutionFailureTest.failureOf(bridged);
			long elapsed = System.currentTimeMillis() - start;

			assertTrue(failure instanceof HardwareException);
			assertTrue("The bridged dispatch must report the foreign cause", causedBy(failure, cause));
			assertTrue("The failure must not wait for a GPU watchdog kill, took " + elapsed + "ms",
					elapsed < 3000);
			assertEquals(0, runner.getErrorCompletionCount());
		} finally {
			runner.destroy();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}

		assertEquals(0, runner.getErrorCompletionCount());
	}

	/**
	 * Returns a foreign completion that has already completed normally.
	 *
	 * @return a settled completion
	 */
	private static Semaphore settled() {
		DefaultLatchSemaphore settled = new DefaultLatchSemaphore((Semaphore) null, 1);
		settled.countDown();
		return settled;
	}

	/**
	 * Checks that a wait failed with the Metal failure the runner reports.
	 *
	 * @param failure what the wait threw
	 */
	private static void assertMetalFailure(Throwable failure) {
		if (!(failure instanceof HardwareException) || !failure.getMessage().contains(ERROR)) {
			throw new AssertionError("Expected the recorded Metal failure, got " + failure, failure);
		}
	}

	/**
	 * Returns whether {@code cause} is in the cause chain of {@code failure}.
	 *
	 * @param failure the failure thrown
	 * @param cause   the expected underlying cause
	 * @return true if the chain contains {@code cause}
	 */
	private static boolean causedBy(Throwable failure, Throwable cause) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t == cause) return true;
		}

		return false;
	}

	/**
	 * A runner that reports one chosen command buffer as failed once it completes, as Metal does
	 * for a buffer the GPU watchdog killed. The buffer still executes; only its reported status
	 * differs, which is all the runner's failure handling reads.
	 */
	private static final class FailingRunner extends MetalCommandRunner {
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

		/**
		 * Reports the command buffer the given dispatch was encoded into as failed.
		 *
		 * @param dispatch a dispatch of this runner
		 */
		private void failBufferOf(MetalSemaphore dispatch) {
			failing = dispatch.getCommandBuffer();
		}

		@Override
		protected String errorOf(MTLCommandBuffer buffer) {
			return buffer == failing ? ERROR : super.errorOf(buffer);
		}
	}
}
