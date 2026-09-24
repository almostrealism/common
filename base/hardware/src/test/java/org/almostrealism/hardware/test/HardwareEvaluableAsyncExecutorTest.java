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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.streams.StreamingEvaluable;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces the gap described in the review of {@link HardwareEvaluable#async(java.util.concurrent.Executor)}:
 * the method used to build a new {@link HardwareEvaluable} without ever storing the supplied executor, so
 * {@link HardwareEvaluable#request(Object[], io.almostrealism.streams.Semaphore, java.util.function.Consumer)}
 * always ran (and could block) on the calling thread regardless of which executor {@code ProcessDetailsFactory}
 * selected.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that class
 * lives in the engine layer, which sits above this module.</p>
 *
 * @see HardwareEvaluable#async(java.util.concurrent.Executor)
 */
public class HardwareEvaluableAsyncExecutorTest {

	/**
	 * A {@link HardwareEvaluable} produced by {@link HardwareEvaluable#async(java.util.concurrent.Executor)}
	 * must dispatch {@code request(...)} through the given executor rather than the calling thread.
	 */
	@Test(timeout = 10000)
	public void requestRunsOnConfiguredExecutor() throws InterruptedException {
		Evaluable<String> shortCircuit = args -> "result";
		HardwareEvaluable<String> evaluable = new HardwareEvaluable<>(
				() -> { throw new UnsupportedOperationException("kernel should not be reached"); },
				null, shortCircuit, false);

		Thread callingThread = Thread.currentThread();
		AtomicReference<Thread> executionThread = new AtomicReference<>();
		CountDownLatch executed = new CountDownLatch(1);

		StreamingEvaluable<String> async = evaluable.async(r -> {
			Thread worker = new Thread(r, "HardwareEvaluableAsyncExecutorTest-worker");
			worker.setDaemon(true);
			worker.start();
		});

		AtomicReference<String> delivered = new AtomicReference<>();
		async.request(new Object[0], null, result -> {
			executionThread.set(Thread.currentThread());
			delivered.set(result);
			executed.countDown();
		});

		Assert.assertTrue("request() should have completed on the configured executor",
				executed.await(5, TimeUnit.SECONDS));
		Assert.assertNotEquals("request() must not run on the calling thread when an executor is configured",
				callingThread, executionThread.get());
		Assert.assertEquals("result", delivered.get());
	}
}
