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
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces the gap described in the review of {@link DestinationEvaluable#async(java.util.concurrent.Executor)}:
 * the method used to build a new {@link DestinationEvaluable} without ever storing the supplied executor
 * ("the executor is not currently used"), so
 * {@link DestinationEvaluable#request(Object[], io.almostrealism.streams.Semaphore, java.util.function.Consumer)}
 * always ran (and could block in {@code awaitReady()}) on the calling thread regardless of which executor
 * {@code ProcessDetailsFactory} selected via {@code isSharedExecutorSafe()}.
 *
 * <p>The wrapped operation here is a plain {@link Evaluable}, not an {@code AcceleratedOperation}, so
 * {@code requestNow} throws {@link UnsupportedOperationException} immediately &mdash; this test only
 * needs to observe which thread that happens on, not complete a real dispatch.</p>
 *
 * @see DestinationEvaluable#async(java.util.concurrent.Executor)
 */
public class DestinationEvaluableAsyncExecutorTest {

	/**
	 * A {@link DestinationEvaluable} produced by {@link DestinationEvaluable#async(java.util.concurrent.Executor)}
	 * must dispatch {@code request(...)} through the given executor rather than the calling thread.
	 */
	@Test(timeout = 10000)
	public void requestRunsOnConfiguredExecutor() throws InterruptedException {
		Evaluable<MemoryBank> operation = args -> null;
		DestinationEvaluable<MemoryBank> evaluable = new DestinationEvaluable<>(operation, null);

		Thread callingThread = Thread.currentThread();
		AtomicReference<Thread> executionThread = new AtomicReference<>();
		CountDownLatch executed = new CountDownLatch(1);

		StreamingEvaluable<MemoryBank> async = evaluable.async(r -> {
			Thread worker = new Thread(() -> {
				try {
					r.run();
				} catch (UnsupportedOperationException expected) {
					// expected: the wrapped operation is not an AcceleratedOperation
				} finally {
					executionThread.set(Thread.currentThread());
					executed.countDown();
				}
			}, "DestinationEvaluableAsyncExecutorTest-worker");
			worker.setDaemon(true);
			worker.start();
		});

		async.request(new Object[0], null, ignored -> { });

		Assert.assertTrue("request() should have completed on the configured executor",
				executed.await(5, TimeUnit.SECONDS));
		Assert.assertNotEquals("request() must not run on the calling thread when an executor is configured",
				callingThread, executionThread.get());
	}
}
