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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.ctx.SubmissionStream;
import org.almostrealism.hardware.mem.KernelMemoryGuard;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.hardware.metal.MTLCommandBuffer;
import org.almostrealism.hardware.metal.MetalCommand;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that a {@link MetalCommandRunner} runs and releases work that nothing ever waits for:
 * a dispatch left in the open command buffer is committed once the buffer has gone quiet
 * ({@link MetalCommandRunner#quietCommitMicros}), and a committed buffer is drained, its
 * released-memory callbacks run and its {@link KernelMemoryGuard} reservations given back, as soon
 * as Metal reports it finished, with no host wait, no destroy and no explicit drain.
 *
 * <p>Each test uses its own runner on the Metal context's queue, so the commit and drain counters
 * it reads count only its own work, and every test observes completion through a callback it
 * registers rather than by sleeping.</p>
 */
public class MetalCommandRunnerAbandonedWorkTest extends TestSuiteBase {
	/** A dispatch that encodes nothing. */
	private static final MetalCommand NOTHING = buffer -> { };

	/** How long a test waits for abandoned work to be released before failing. */
	private static final long RELEASE_TIMEOUT_SECONDS = 20;

	/**
	 * Dispatches whose argument memory is reserved, submitted back to back and never waited for,
	 * with no reference to their arguments left in the test, still have their reservations given
	 * back and their callbacks run. They share one command buffer, which the quiet delay commits
	 * and the completed handler drains.
	 */
	@Test(timeout = 60000)
	public void abandonedDispatchesReleaseTheirReservations() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int idle = SubmissionStream.minIdleCommit;
		SubmissionStream.minIdleCommit = 0;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		try {
			int count = 8;
			CountDownLatch released = new CountDownLatch(count);
			long[] addresses = submitAbandoned(runner, count, released);

			Assert.assertTrue("Abandoned work must be released without any wait",
					released.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

			KernelMemoryGuard guard = Hardware.getLocalHardware().getKernelMemoryGuard();
			for (long address : addresses) {
				Assert.assertTrue("A reservation of abandoned work must be given back",
						guard.canDeallocate(address));
			}

			Assert.assertEquals("The back-to-back dispatches must share one buffer", 1, runner.getCommitCount());
			Assert.assertEquals(1, runner.getQuietCommitCount());
			Assert.assertEquals(0, runner.getHostCompleteCommitCount());
			Assert.assertEquals(1, runner.getCompletionDrainCount());
		} finally {
			runner.destroy();
			SubmissionStream.minIdleCommit = idle;
		}
	}

	/**
	 * A single dispatch left in the open buffer, with nothing waiting for it, is committed by the
	 * quiet delay and then drained by its completed handler.
	 */
	@Test(timeout = 60000)
	public void singleDispatchIsCommittedWhenQuiet() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		try {
			CountDownLatch drained = new CountDownLatch(1);
			runner.submit(null, NOTHING, null, drained::countDown);

			Assert.assertTrue("A quiet open buffer must be committed and drained",
					drained.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
			Assert.assertEquals(1, runner.getCommitCount());
			Assert.assertEquals(1, runner.getQuietCommitCount());
			Assert.assertEquals(1, runner.getCompletionDrainCount());
		} finally {
			runner.destroy();
		}
	}

	/**
	 * A wait for a dispatch whose buffer the completed handler has already drained returns
	 * normally and finds the dispatch's callback run.
	 */
	@Test(timeout = 60000)
	public void waitAfterHandlerDrainedReturns() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		try {
			CountDownLatch drained = new CountDownLatch(1);
			MetalSemaphore dispatch = runner.submit(null, NOTHING, null, drained::countDown);

			Assert.assertTrue(drained.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
			Assert.assertEquals(1, runner.getCompletionDrainCount());

			dispatch.waitFor();
			Assert.assertEquals("The late wait must not commit anything", 0, runner.getHostCompleteCommitCount());
			Assert.assertEquals("The late wait must find its buffer drained", 1, runner.getDrainedWaitCount());

			AtomicInteger next = new AtomicInteger();
			runner.submit(null, NOTHING, null, next::incrementAndGet).waitFor();
			Assert.assertEquals("The runner must keep working after a handler drain", 1, next.get());
		} finally {
			runner.destroy();
		}
	}

	/**
	 * With no quiet delay, every dispatch's buffer is committed as soon as the runner's thread is
	 * free, so a wait issued right after the dispatch races the completed handler for the drain.
	 * Whichever drains the buffer, the wait returns normally with the callback run.
	 */
	@Test(timeout = 120000)
	public void waitRacingHandlerReturns() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int quiet = MetalCommandRunner.quietCommitMicros;
		MetalCommandRunner.quietCommitMicros = 0;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		try {
			int rounds = 200;
			AtomicInteger callbacks = new AtomicInteger();

			for (int i = 0; i < rounds; i++) {
				MetalSemaphore dispatch = runner.submit(null, NOTHING, null, callbacks::incrementAndGet);
				dispatch.waitFor();
				Assert.assertEquals("A wait must return with its dispatch's callback run",
						i + 1, callbacks.get());
			}

			log("completionDrains=" + runner.getCompletionDrainCount() +
					" hostCompleteCommits=" + runner.getHostCompleteCommitCount() +
					" quietCommits=" + runner.getQuietCommitCount());
		} finally {
			runner.destroy();
			MetalCommandRunner.quietCommitMicros = quiet;
		}
	}

	/**
	 * A buffer that finishes with the error status and is drained by its completed handler, with
	 * no wait, still records the failure, so a later wait for its dispatch throws it.
	 */
	@Test(timeout = 60000)
	public void failedBufferDrainedByHandlerFailsLaterWait() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int quiet = MetalCommandRunner.quietCommitMicros;
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;

		// Long enough that the buffer is still open when it is marked as failing
		MetalCommandRunner.quietCommitMicros = (int) TimeUnit.SECONDS.toMicros(60);
		MetalCommandFailureTest.FailingRunner runner = new MetalCommandFailureTest.FailingRunner(metal.getMtlQueue());

		try {
			CountDownLatch drained = new CountDownLatch(1);
			MetalSemaphore failed = runner.submit(null, NOTHING, null, drained::countDown);
			runner.failBufferOf(failed);

			// A bridged dispatch closes the open buffer, which is then committed with nothing waiting
			runner.submit(null, NOTHING, MetalCommandFailureTest.settled(), null);

			Assert.assertTrue("The failed buffer must be drained without a wait",
					drained.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
			Assert.assertEquals(1, runner.getErrorCompletionCount());
			Assert.assertEquals(1, runner.getCompletionDrainCount());

			try {
				failed.waitFor();
				Assert.fail("A wait after the handler drained a failed buffer must throw");
			} catch (HardwareException e) {
				Assert.assertTrue("Expected the recorded failure, got " + e, e.getMessage().contains(MetalCommandFailureTest.ERROR));
			}
		} finally {
			MetalCommandRunner.quietCommitMicros = quiet;
			MetalCommandRunner.enableHostSignaledBridges = bridges;
			runner.destroy();
		}
	}

	/**
	 * Destroying the runner while several committed buffers still have completed handlers to come
	 * drains every buffer, so every callback has run when destruction returns, and a handler that
	 * arrives afterwards does nothing.
	 */
	@Test(timeout = 60000)
	public void destroyWithPendingHandlersDrainsEverything() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());
		AtomicInteger callbacks = new AtomicInteger();
		int buffers = 16;

		try {
			// Each bridged dispatch closes and commits the buffer before it
			for (int i = 0; i < buffers; i++) {
				runner.submit(null, NOTHING, MetalCommandFailureTest.settled(), callbacks::incrementAndGet);
				runner.submit(null, NOTHING, null, callbacks::incrementAndGet);
			}
		} finally {
			runner.destroy();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}

		Assert.assertEquals("Destruction must drain every buffer", 2 * buffers, callbacks.get());

		long drains = runner.getCompletionDrainCount();
		CountDownLatch late = new CountDownLatch(1);
		Assert.assertFalse(late.await(200, TimeUnit.MILLISECONDS));
		Assert.assertEquals("A handler arriving after destruction must not drain", drains,
				runner.getCompletionDrainCount());
		Assert.assertEquals(2 * buffers, callbacks.get());
	}

	/**
	 * Dispatches submitted back to back still share one command buffer: the quiet delay is
	 * measured from the last dispatch and never commits a buffer that is still being filled.
	 */
	@Test(timeout = 60000)
	public void backToBackDispatchesShareBuffer() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int idle = SubmissionStream.minIdleCommit;
		SubmissionStream.minIdleCommit = 0;
		MetalCommandRunner runner = new MetalCommandRunner(metal.getMtlQueue());

		try {
			List<MetalSemaphore> dispatches = new ArrayList<>();
			for (int i = 0; i < 32; i++) {
				dispatches.add(runner.submit(null, NOTHING, null, null));
			}

			// TODO(review): flaky if a submit gap exceeds quietCommitMicros
			Assert.assertEquals("A buffer being filled must not be committed", 0, runner.getCommitCount());

			dispatches.get(dispatches.size() - 1).waitFor();

			MTLCommandBuffer shared = dispatches.get(0).getCommandBuffer();
			for (MetalSemaphore d : dispatches) {
				Assert.assertSame(shared, d.getCommandBuffer());
			}

			Assert.assertEquals(1, runner.getCommitCount());
			Assert.assertEquals(0, runner.getQuietCommitCount());
		} finally {
			runner.destroy();
			SubmissionStream.minIdleCommit = idle;
		}
	}

	/**
	 * Submits dispatches whose argument memory is reserved, as a Metal operator reserves it, and
	 * returns only the reserved addresses, so nothing in the test keeps the arguments reachable.
	 * Each dispatch gives its reservation back, and counts down {@code released}, from its
	 * released-memory callback.
	 *
	 * @param runner   the runner to submit to
	 * @param count    the number of dispatches
	 * @param released counted down by each dispatch's callback
	 * @return the container addresses of the reserved arguments
	 */
	private static long[] submitAbandoned(MetalCommandRunner runner, int count, CountDownLatch released) {
		KernelMemoryGuard guard = Hardware.getLocalHardware().getKernelMemoryGuard();
		long[] addresses = new long[count];

		for (int i = 0; i < count; i++) {
			PackedCollection argument = new PackedCollection(16);
			addresses[i] = ((RAM) argument.getMem()).getContainerPointer();
			KernelMemoryGuard.Reservation reservation = guard.acquire(argument);

			runner.submit(null, NOTHING, null, () -> {
				reservation.release();
				released.countDown();
			});
		}

		return addresses;
	}
}
