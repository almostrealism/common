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
import org.almostrealism.hardware.ctx.SubmissionStream;
import org.almostrealism.hardware.metal.MTLCommandBuffer;
import org.almostrealism.hardware.metal.MTLCommandQueue;
import org.almostrealism.hardware.metal.MetalCommand;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.io.Console;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertNull;

/**
 * Verifies that an interrupted {@link MetalSemaphore#waitFor()} never returns as though the GPU
 * work had finished while it is still pending: it either throws or waits for the work, a failure
 * recorded by the drain after the wait is still reported, and one interrupt is reported by one
 * diagnostic.
 */
public class MetalInterruptedWaitTest extends TestSuiteBase {
	/** The text of every interrupt diagnostic on the completion wait path. */
	private static final String INTERRUPTED = "Interrupted while";

	/** A dispatch that encodes nothing. */
	private static final MetalCommand NOTHING = buffer -> { };

	/** How long to wait for a thread or callback that must finish. */
	private static final long TIMEOUT_SECONDS = 30;

	/**
	 * An interrupt of a wait for a dispatch whose buffer is held for an unsettled bridge, so its
	 * work cannot have run, makes the wait throw rather than return, with one diagnostic and the
	 * interrupt status left set. The work is still pending when the wait has thrown, and runs
	 * once the bridge is signaled.
	 *
	 * @throws InterruptedException if the test thread is interrupted
	 */
	@Test(timeout = 120000)
	public void interruptedWaitForPendingWorkThrows() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore((Semaphore) null, 1);
		CountDownLatch ran = new CountDownLatch(1);
		AtomicReference<Throwable> waitFailure = new AtomicReference<>();
		AtomicBoolean interruptKept = new AtomicBoolean();
		List<String> diagnostics = new CopyOnWriteArrayList<>();
		Consumer<String> listener = diagnosticListener(diagnostics);

		try {
			MetalSemaphore bridged = runner.submit(null, NOTHING, foreign, ran::countDown);

			Thread waiter = new Thread(() -> {
				try {
					bridged.waitFor();
				} catch (Throwable t) {
					waitFailure.set(t);
				}

				interruptKept.set(Thread.currentThread().isInterrupted());
			});

			Console.root().addListener(listener);
			waiter.start();
			awaitBlocked(waiter);

			waiter.interrupt();
			waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
			Console.root().removeListener(listener);

			assertFalse("An interrupted wait must end", waiter.isAlive());
			assertTrue("An interrupted wait for pending work must throw, not return, got " + waitFailure.get(),
					waitFailure.get() instanceof HardwareException);
			assertEquals("The work must still be pending when the wait throws", 1, ran.getCount());
			assertTrue("The interrupt status must be left set", interruptKept.get());
			assertEquals("One interrupt must yield one diagnostic: " + diagnostics, 1, diagnostics.size());

			foreign.countDown();
			bridged.waitFor();
			assertTrue("The work must run once its bridge is signaled",
					ran.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		} finally {
			Console.root().removeListener(listener);
			foreign.countDown();
			runner.destroy();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * An interrupt pending when a wait starts, while the runner's thread is still occupied so the
	 * wait's registration is queued, makes the wait throw with exactly one diagnostic, where it
	 * used to return normally after two warnings.
	 *
	 * @throws InterruptedException if the test thread is interrupted
	 */
	@Test(timeout = 120000)
	public void interruptedRegistrationThrowsWithOneDiagnostic() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int quiet = SubmissionStream.quietCommitMicros;
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		SubmissionStream.quietCommitMicros = (int) TimeUnit.SECONDS.toMicros(60);
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		CountDownLatch blocking = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);
		List<String> diagnostics = new CopyOnWriteArrayList<>();
		Consumer<String> listener = diagnosticListener(diagnostics);
		HardwareException thrown = null;

		try {
			// The first buffer's callback occupies the runner's thread when its handler drains it
			runner.submit(null, NOTHING, null, () -> {
				blocking.countDown();

				try {
					gate.await();
				} catch (InterruptedException e) {
					throw new IllegalStateException("The runner's thread must not be interrupted", e);
				}
			});

			MetalSemaphore waited = runner.submit(null, NOTHING, MetalCommandFailureTest.settled(),
					released::countDown);
			assertTrue("The first buffer must be drained by its completed handler",
					blocking.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			Console.root().addListener(listener);
			Thread.currentThread().interrupt();

			try {
				waited.waitFor();
			} catch (HardwareException e) {
				thrown = e;
			} finally {
				assertTrue("The interrupt status must be left set", Thread.interrupted());
				Console.root().removeListener(listener);
				gate.countDown();
			}

			assertNotNull("A wait whose registration has not run must throw", thrown);
			assertEquals("One interrupt must yield one diagnostic: " + diagnostics, 1, diagnostics.size());
			assertTrue("The buffer of an interrupted wait must still be released",
					released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		} finally {
			Console.root().removeListener(listener);
			gate.countDown();
			runner.destroy();
			SubmissionStream.quietCommitMicros = quiet;
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * A wait interrupted after its buffer has completed on the GPU, but before the buffer has been
	 * drained, still drains it, so the failure that drain records is thrown instead of the wait
	 * returning normally. No interrupt diagnostic is logged, since the wait did not end early.
	 */
	@Test(timeout = 60000)
	public void drainFailureIsReportedAfterInterrupt() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		InterruptingRunner runner = new InterruptingRunner(metal.getMtlQueue());
		List<String> diagnostics = new CopyOnWriteArrayList<>();
		Consumer<String> listener = diagnosticListener(diagnostics);
		Throwable thrown = null;

		try {
			MetalSemaphore dispatch = runner.submit(null, NOTHING, null, null);
			runner.failBufferOf(dispatch, Thread.currentThread());
			Console.root().addListener(listener);

			try {
				dispatch.waitFor();
			} catch (RuntimeException e) {
				thrown = e;
			} finally {
				assertTrue("The interrupt must have reached the waiting thread", Thread.interrupted());
				Console.root().removeListener(listener);
			}

			assertTrue("The failure recorded by the drain must be thrown, got " + thrown,
					thrown instanceof HardwareException
							&& thrown.getMessage().contains(MetalCommandFailureTest.ERROR));
			assertEquals("A wait that did not end early logs no interrupt: " + diagnostics,
					0, diagnostics.size());
		} finally {
			Console.root().removeListener(listener);
			runner.destroy();
		}
	}

	/**
	 * {@link MetalSemaphore#waitForUninterruptibly()} waits through an interrupt for a held
	 * buffer, returns normally once the work has run, and restores the interrupt status.
	 *
	 * @throws InterruptedException if the test thread is interrupted
	 */
	@Test(timeout = 120000)
	public void uninterruptibleWaitWaitsForPendingWork() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore((Semaphore) null, 1);
		CountDownLatch ran = new CountDownLatch(1);
		AtomicReference<Throwable> waitFailure = new AtomicReference<>();
		AtomicBoolean interruptKept = new AtomicBoolean();
		AtomicBoolean ranBeforeReturn = new AtomicBoolean();

		try {
			MetalSemaphore bridged = runner.submit(null, NOTHING, foreign, ran::countDown);

			Thread waiter = new Thread(() -> {
				try {
					bridged.waitForUninterruptibly();
					ranBeforeReturn.set(ran.getCount() == 0);
				} catch (Throwable t) {
					waitFailure.set(t);
				}

				interruptKept.set(Thread.currentThread().isInterrupted());
			});

			waiter.start();
			awaitBlocked(waiter);
			waiter.interrupt();
			waiter.join(200);
			assertTrue("An uninterruptible wait must not end while the work is pending", waiter.isAlive());

			foreign.countDown();
			waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

			assertFalse(waiter.isAlive());
			assertNull(waitFailure.get());
			assertTrue("The work must have run when the wait returns", ranBeforeReturn.get());
			assertTrue("The interrupt status must be restored", interruptKept.get());
		} finally {
			foreign.countDown();
			runner.destroy();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * Returns a console listener collecting every warning about an interrupted wait.
	 *
	 * @param diagnostics the list to add each such warning to
	 * @return the listener
	 */
	private static Consumer<String> diagnosticListener(List<String> diagnostics) {
		return line -> {
			if (line.contains("WARN") && line.contains(INTERRUPTED)) diagnostics.add(line);
		};
	}

	/**
	 * Waits until the given thread is blocked, so an interrupt reaches it inside its wait.
	 *
	 * @param thread the waiting thread
	 * @throws InterruptedException if the test thread is interrupted
	 */
	private void awaitBlocked(Thread thread) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
		while (thread.getState() != Thread.State.WAITING && thread.isAlive()
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}

		// Let the wait settle into the wait for the held buffer
		Thread.sleep(200);
		assertTrue("The wait for a held buffer must block", thread.isAlive());
	}

	/**
	 * A runner that reports one chosen command buffer as failed, and interrupts a chosen thread
	 * when that thread reads the failure, which a wait does on its own thread after the GPU has
	 * finished the buffer and before the buffer is drained.
	 */
	private static final class InterruptingRunner extends MetalCommandRunner {
		/** The command buffer to report as failed, or {@code null}. */
		private volatile MTLCommandBuffer failing;

		/** The thread to interrupt when it reads the failure. */
		private volatile Thread interruptee;

		/**
		 * Creates a runner on the given queue.
		 *
		 * @param queue the Metal command queue
		 */
		InterruptingRunner(MTLCommandQueue queue) {
			super(queue);
		}

		/**
		 * Reports the buffer of the given dispatch as failed, interrupting {@code thread} when it
		 * reads that failure.
		 *
		 * @param dispatch a dispatch of this runner
		 * @param thread   the thread to interrupt
		 */
		void failBufferOf(MetalSemaphore dispatch, Thread thread) {
			interruptee = thread;
			failing = dispatch.getCommandBuffer();
		}

		@Override
		protected String errorOf(MTLCommandBuffer buffer) {
			if (buffer != failing) return super.errorOf(buffer);
			if (Thread.currentThread() == interruptee) interruptee.interrupt();
			return MetalCommandFailureTest.ERROR;
		}
	}
}
