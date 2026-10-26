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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;

/**
 * Validates that {@link MetalCommandRunner#submit} keeps its ordering contract when the
 * submitting thread is interrupted: the dispatch is still encoded only after its dependency
 * has completed, and the call still returns the dispatch's completion.
 */
public class MetalCommandRunnerInterruptTest extends TestSuiteBase {

	/**
	 * With {@link MetalCommandRunner#enableHostSignaledBridges} disabled, an interrupt of the
	 * submitting thread (pending on entry, or arriving while it waits for a foreign dependency)
	 * does not let {@link MetalCommandRunner#submit} encode the dispatch before the dependency
	 * has completed, and does not make it return without a completion. The interrupt status
	 * is restored when it returns.
	 *
	 * <p>{@link Semaphore#waitFor()} returns early when the waiting thread is interrupted, so a
	 * submission that treated its return as the dependency having completed would encode a
	 * dispatch that reads the dependency's output before it was written.</p>
	 */
	@Test(timeout = 60000)
	public void interruptedUnbridgedWaitStillOrdersAfterDependency() throws InterruptedException {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		boolean bridges = MetalCommandRunner.enableHostSignaledBridges;
		MetalCommandRunner.enableHostSignaledBridges = false;

		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore((Semaphore) null, 1);
		AtomicBoolean dependencyCompleted = new AtomicBoolean();
		AtomicReference<Boolean> encodedAfterDependency = new AtomicReference<>();
		AtomicReference<Semaphore> submitted = new AtomicReference<>();
		AtomicReference<Throwable> submitFailure = new AtomicReference<>();
		AtomicBoolean interruptKept = new AtomicBoolean();
		Thread submitter = new Thread(() -> {
			Thread.currentThread().interrupt();
			try {
				submitted.set(runner.submit(null,
						buffer -> encodedAfterDependency.set(dependencyCompleted.get()), foreign, null));
			} catch (Throwable t) {
				submitFailure.set(t);
			}
			interruptKept.set(Thread.currentThread().isInterrupted());
		});

		try {
			submitter.start();
			submitter.join(200);
			assertTrue("An interrupt pending on entry must not end the dependency wait",
					submitter.isAlive());
			submitter.interrupt();
			submitter.join(200);
			assertTrue("An interrupt during the dependency wait must not end it", submitter.isAlive());
			assertNull("The dispatch must not be encoded before its dependency",
					encodedAfterDependency.get());

			dependencyCompleted.set(true);
			foreign.countDown();
			submitter.join(30000);

			assertFalse(submitter.isAlive());
			assertNull(submitFailure.get());
			assertTrue("The dispatch must be encoded after its dependency completed",
					Boolean.TRUE.equals(encodedAfterDependency.get()));
			assertTrue("The submission must return its dispatch's completion",
					runner.ordersAfter(submitted.get()));
			assertTrue("The interrupt status must be restored", interruptKept.get());
			submitted.get().waitFor();
		} finally {
			foreign.countDown();
			submitter.join(30000);
			MetalCommandRunner.enableHostSignaledBridges = bridges;
		}
	}
}
