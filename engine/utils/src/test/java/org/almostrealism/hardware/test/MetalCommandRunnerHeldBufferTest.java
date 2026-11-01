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
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;

/**
 * Validates the bound {@link MetalCommandRunner} places on command buffers that are closed but
 * not yet committed because an earlier buffer waits for a host-signaled bridge.
 */
public class MetalCommandRunnerHeldBufferTest extends TestSuiteBase {

	/**
	 * Dispatches with no dependency, chained behind a dispatch bridged to unsettled foreign work,
	 * fill and close buffers at {@link MetalCommandRunner#MAX_OPEN}, and each closed buffer waits
	 * behind the bridged one. Once {@link MetalCommandRunner#MAX_HELD} buffers wait, a dispatch
	 * that would open another must wait on its submitting thread rather than open it: buffers
	 * beyond the command queue's bound would block the runner's thread, which the bridge signal
	 * needs, so the runner could never commit anything again. When the foreign work settles,
	 * every held buffer is committed and the remaining dispatches are encoded and complete.
	 */
	@Test(timeout = 120000)
	public void unbridgedDispatchesWaitOnceHeldBuffersReachTheBound() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;

		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore((Semaphore) null, 1);
		int total = (MetalCommandRunner.MAX_HELD + 2) * MetalCommandRunner.MAX_OPEN;
		// The bridged dispatch is the first of the first held buffer
		int encodedAtBound = MetalCommandRunner.MAX_HELD * MetalCommandRunner.MAX_OPEN - 1;

		AtomicInteger encoded = new AtomicInteger();
		AtomicReference<Semaphore> last = new AtomicReference<>();
		AtomicReference<Throwable> submitFailure = new AtomicReference<>();
		Thread submitter = new Thread(() -> {
			try {
				for (int i = 0; i < total; i++) {
					last.set(runner.submit(null, buffer -> encoded.incrementAndGet(), null, null));
				}
			} catch (Throwable t) {
				submitFailure.set(t);
			}
		});

		try {
			// Start from a drained runner, so the bridged dispatch opens a fresh buffer
			runner.submit(null, buffer -> { }, null, null).waitFor();
			Semaphore bridged = runner.submit(null, buffer -> { }, foreign, null);

			submitter.start();

			long deadline = System.currentTimeMillis() + 30000;
			while (encoded.get() < encodedAtBound && submitter.isAlive()
					&& System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}
			submitter.join(500);

			assertNull(submitFailure.get());
			assertTrue("A dispatch that would open a buffer beyond the held bound must wait",
					submitter.isAlive());
			assertEquals(encodedAtBound, encoded.get());

			foreign.countDown();
			submitter.join(60000);

			assertFalse(submitter.isAlive());
			assertNull(submitFailure.get());
			assertEquals(total, encoded.get());

			last.get().waitFor();
			bridged.waitFor();
		} finally {
			foreign.countDown();
			submitter.join(60000);
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * Every buffer closed behind an unsettled bridge is counted in the runner's hold bound, and
	 * released from it when the buffer is committed. Filling the bridged buffer closes it while
	 * its bridge is unsignaled, so exactly one buffer is held; once the foreign work settles and
	 * the dispatches complete, nothing is held, so a later submission is never refused room by a
	 * count the runner failed to release.
	 */
	@Test(timeout = 120000)
	public void heldCountReturnsToZeroOnceBridgedBufferIsCommitted() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;

		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore((Semaphore) null, 1);

		try {
			runner.submit(null, buffer -> { }, null, null).waitFor();
			assertEquals(0, runner.getHeldCount());

			Semaphore bridged = runner.submit(null, buffer -> { }, foreign, null);
			Semaphore last = bridged;
			for (int i = 1; i < MetalCommandRunner.MAX_OPEN; i++) {
				last = runner.submit(null, buffer -> { }, null, null);
			}

			assertEquals("The full bridged buffer must be closed and held", 1, runner.getHeldCount());

			foreign.countDown();
			last.waitFor();
			bridged.waitFor();

			assertEquals("A committed buffer must be released from the hold bound",
					0, runner.getHeldCount());
		} finally {
			foreign.countDown();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}

	/**
	 * A host wait for a dispatch whose buffer is closed but held back for an unsettled bridge is
	 * a wait for the foreign work, since the buffer is not on the GPU yet. An interrupt must end
	 * that wait, leaving the caller's interrupt status set, without committing the held buffer.
	 * Once the foreign work settles the buffer is committed, and a later wait for the same
	 * dispatch completes normally, so the abandoned waiter did not leave the buffer wedged.
	 */
	@Test(timeout = 120000)
	public void interruptedWaitForHeldBufferReturnsWithoutCommitting() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = true;

		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore((Semaphore) null, 1);
		AtomicBoolean interruptRestored = new AtomicBoolean();
		AtomicReference<Throwable> waitFailure = new AtomicReference<>();

		try {
			runner.submit(null, buffer -> { }, null, null).waitFor();
			long errors = runner.getErrorCompletionCount();
			long commits = runner.getCommitCount();

			Semaphore bridged = runner.submit(null, buffer -> { }, foreign, null);

			Thread waiter = new Thread(() -> {
				try {
					bridged.waitFor();
					interruptRestored.set(Thread.currentThread().isInterrupted());
				} catch (Throwable t) {
					waitFailure.set(t);
				}
			});
			waiter.start();

			long deadline = System.currentTimeMillis() + 30000;
			while (waiter.getState() != Thread.State.WAITING && waiter.isAlive()
					&& System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}

			// The waiter is still blocked: the buffer it waits for cannot be committed yet
			Thread.sleep(200);
			assertTrue("A wait for a held buffer must wait for the foreign work", waiter.isAlive());
			assertEquals(commits, runner.getCommitCount());

			waiter.interrupt();
			waiter.join(30000);

			assertFalse("An interrupt must end the wait for a held buffer", waiter.isAlive());
			assertNull(waitFailure.get());
			assertTrue("The interrupt status must be restored", interruptRestored.get());
			assertEquals("The held buffer must not be committed before its bridge is signaled",
					commits, runner.getCommitCount());

			foreign.countDown();
			bridged.waitFor();

			assertTrue(runner.getCommitCount() > commits);
			assertEquals(errors, runner.getErrorCompletionCount());
		} finally {
			foreign.countDown();
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}
}
