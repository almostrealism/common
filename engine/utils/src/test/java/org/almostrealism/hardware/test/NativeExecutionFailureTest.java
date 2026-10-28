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
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.jni.NativeExecution;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertSame;

/**
 * Verifies that a {@link NativeExecution} dispatch reports a failure to whoever waits for it: a
 * dispatch chained on a failed completion, and one whose worker throws, must rethrow from their
 * completion's {@link Semaphore#waitFor()} rather than settle as if the work had run, and must
 * still release the kernel memory reservation held for the dispatch. Needs no GPU: the kernel is a
 * {@link ScriptedInstructionSet}, and the coordination, latch and memory guard are the real ones.
 */
public class NativeExecutionFailureTest extends TestSuiteBase {

	/**
	 * A dispatch whose dependency failed does not run its workers, and its completion rethrows
	 * the dependency's failure.
	 */
	@Test(timeout = 60000)
	public void failedDependencyIsRethrownByDispatch() {
		RuntimeException cause = new RuntimeException("dependency failed");
		ScriptedInstructionSet kernel = new ScriptedInstructionSet(2, null);

		try (PackedCollection arg = new PackedCollection(4)) {
			Semaphore done = kernel.execution().accept(new Object[] { arg }, failed(cause));

			assertSame(cause, failureOf(done));
			assertEquals(0, kernel.getInvocations());
			awaitGuardReleased(arg);
		}
	}

	/**
	 * A dispatch whose single worker (run on the coordinating thread) throws reports the
	 * worker's failure from its completion.
	 */
	@Test(timeout = 60000)
	public void failedInlineWorkerIsRethrownByDispatch() {
		assertWorkerFailureRethrown(1);
	}

	/**
	 * A dispatch whose workers, run on the worker pool, throw reports the first failure from
	 * its completion.
	 */
	@Test(timeout = 60000)
	public void failedPooledWorkerIsRethrownByDispatch() {
		assertWorkerFailureRethrown(2);
	}

	/**
	 * A failure propagates along a chain: a dispatch chained on a failed dispatch does not run,
	 * and the single wait at the end of the chain reports the original failure.
	 */
	@Test(timeout = 60000)
	public void failurePropagatesAlongChain() {
		RuntimeException cause = new RuntimeException("head of chain failed");
		ScriptedInstructionSet head = new ScriptedInstructionSet(1, cause);
		ScriptedInstructionSet tail = new ScriptedInstructionSet(1, null);

		try (PackedCollection arg = new PackedCollection(4)) {
			Semaphore first = head.execution().accept(new Object[] { arg }, null);
			Semaphore second = tail.execution().accept(new Object[] { arg }, first);
			Semaphore third = tail.execution().accept(new Object[] { arg }, second);

			assertSame(cause, failureOf(third));
			assertEquals(1, head.getInvocations());
			assertEquals(0, tail.getInvocations());
			awaitGuardReleased(arg);
		}
	}

	/**
	 * Work deferred with {@link Semaphore#then} after a composite of native completions, one of
	 * which failed, does not run, and the deferred completion rethrows the member's failure:
	 * the composition layer carries a native dispatch failure as it carries any other.
	 */
	@Test(timeout = 60000)
	public void deferredWorkAfterFailedCompositeDoesNotRun() {
		RuntimeException cause = new RuntimeException("member failed");
		ScriptedInstructionSet failing = new ScriptedInstructionSet(1, cause);
		ScriptedInstructionSet passing = new ScriptedInstructionSet(1, null);
		AtomicBoolean ran = new AtomicBoolean();

		try (PackedCollection a = new PackedCollection(4); PackedCollection b = new PackedCollection(4)) {
			Semaphore all = Semaphore.all(Arrays.asList(
					passing.execution().accept(new Object[] { a }, null),
					failing.execution().accept(new Object[] { b }, null)));
			Semaphore deferred = all.then(() -> {
				ran.set(true);
				return null;
			});

			assertSame(cause, failureOf(deferred));
			assertFalse(ran.get());
		}
	}

	/**
	 * Dispatches a kernel whose every worker throws and checks that the completion rethrows the
	 * failure and the memory reservation is released.
	 *
	 * @param parallelism the number of workers the dispatch is split across
	 */
	private void assertWorkerFailureRethrown(int parallelism) {
		RuntimeException cause = new RuntimeException("worker failed");
		ScriptedInstructionSet kernel = new ScriptedInstructionSet(parallelism, cause);

		try (PackedCollection arg = new PackedCollection(4)) {
			Semaphore done = kernel.execution().accept(new Object[] { arg }, null);

			assertSame(cause, failureOf(done));
			assertEquals(parallelism, kernel.getInvocations());
			awaitGuardReleased(arg);
		}
	}

	/**
	 * Returns a completion that has already failed with the given cause.
	 *
	 * @param cause the failure
	 * @return a settled, failed completion
	 */
	static Semaphore failed(RuntimeException cause) {
		DefaultLatchSemaphore failed = new DefaultLatchSemaphore((Semaphore) null, 1);
		failed.fail(cause);
		failed.countDown();
		return failed;
	}

	/**
	 * Waits for the completion and returns what it threw, failing the test if it returned.
	 *
	 * @param completion the completion to wait for
	 * @return the failure its wait threw
	 */
	static Throwable failureOf(Semaphore completion) {
		try {
			completion.waitFor();
		} catch (RuntimeException e) {
			return e;
		}

		throw new AssertionError("The wait returned normally for work that failed");
	}

	/**
	 * Waits until no kernel reservation holds the collection's memory. The dispatch's waiters are
	 * released as its latch reaches zero, a moment before the coordinator releases the
	 * reservation, so the release is polled for.
	 *
	 * @param arg the dispatch argument
	 */
	static void awaitGuardReleased(PackedCollection arg) {
		long address = ((RAM) arg.getMem()).getContainerPointer();
		long deadline = System.currentTimeMillis() + 10000;

		while (!Hardware.getLocalHardware().getKernelMemoryGuard().canDeallocate(address)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("The failed dispatch never released its memory reservation");
			}

			Thread.onSpinWait();
		}
	}
}
