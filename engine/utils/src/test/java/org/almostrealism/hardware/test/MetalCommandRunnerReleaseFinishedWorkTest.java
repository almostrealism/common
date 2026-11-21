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

import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates that work a Metal command runner encoded, and that no thread ever waits for,
 * releases what it holds when the runner is asked to release finished work.
 *
 * <p>A command buffer's released-memory callbacks give back the kernel memory reservations of its
 * dispatches and drop the references to their arguments. The runner used to run them only when a
 * host wait drained the buffer, so the dispatches of a caller that never waited (one that failed
 * after submitting, for instance) kept every block they referenced reachable for as long as no
 * other wait reached their buffer. A memory provider at its ceiling could then collect garbage
 * indefinitely without freeing any of it.</p>
 */
public class MetalCommandRunnerReleaseFinishedWorkTest extends TestSuiteBase {
	/** Longest time the runner is given to finish and release the work, in milliseconds. */
	private static final long RELEASE_TIMEOUT_MS = 20000;

	/**
	 * Dispatches that nothing waits for, left in the open buffer, run their released-memory
	 * callbacks once the runner releases finished work: the first call commits the buffer and a
	 * later one drains it after the GPU has finished.
	 */
	@Test(timeout = 60000)
	public void unwaitedDispatchesAreReleased() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("Skipping unwaitedDispatchesAreReleased - no Metal device");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		int dispatches = 3;
		AtomicInteger released = new AtomicInteger();
		long commitsBefore = runner.getReleaseCommitCount();

		for (int i = 0; i < dispatches; i++) {
			runner.submit(null, buffer -> { }, null, released::incrementAndGet);
		}

		Assert.assertEquals("Nothing is released before the work has run and been drained",
				0, released.get());

		awaitRelease(runner::releaseFinishedWork, released, dispatches);

		Assert.assertEquals("Every dispatch's callbacks must have run", dispatches, released.get());
		Assert.assertTrue("The open buffer must have been committed to release its work",
				runner.getReleaseCommitCount() > commitsBefore);

		AtomicInteger next = new AtomicInteger();
		MetalSemaphore dispatch = runner.submit(null, buffer -> { }, null, next::incrementAndGet);
		dispatch.waitFor();
		Assert.assertEquals("The runner must keep working after releasing finished work", 1, next.get());
	}

	/**
	 * The release reaches the runner from its data context, as a memory provider at its ceiling
	 * requests it: from {@link io.almostrealism.code.DataContext#releaseFinishedWork()} through
	 * each of its compute contexts.
	 */
	@Test(timeout = 60000)
	public void dataContextReleasesUnwaitedDispatches() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("Skipping dataContextReleasesUnwaitedDispatches - no Metal device");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		AtomicInteger released = new AtomicInteger();
		runner.submit(null, buffer -> { }, null, released::incrementAndGet);

		awaitRelease(metal.getDataContext()::releaseFinishedWork, released, 1);
		Assert.assertEquals("The dispatch's callback must have run", 1, released.get());
	}

	/**
	 * Requests a release repeatedly, as successive collection passes of a memory provider do,
	 * until the expected number of callbacks have run or the timeout passes.
	 *
	 * @param release  requests the release of finished work
	 * @param released the number of callbacks that have run
	 * @param expected the number of callbacks expected to run
	 */
	private void awaitRelease(Runnable release, AtomicInteger released, int expected) {
		long deadline = System.currentTimeMillis() + RELEASE_TIMEOUT_MS;

		while (released.get() < expected && System.currentTimeMillis() < deadline) {
			release.run();
			if (released.get() >= expected) return;

			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
