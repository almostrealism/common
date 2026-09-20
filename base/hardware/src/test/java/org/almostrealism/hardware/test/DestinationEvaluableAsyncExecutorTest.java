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
import org.almostrealism.hardware.mem.Bytes;
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
 * {@code requestNow} evaluates it on the host exactly as {@link DestinationEvaluable#evaluate(Object...)}
 * does &mdash; this test observes both that this happens on the configured executor's thread, and that
 * the result is actually delivered rather than an {@link UnsupportedOperationException} being thrown.</p>
 *
 * @see DestinationEvaluable#async(java.util.concurrent.Executor)
 */
public class DestinationEvaluableAsyncExecutorTest {

	/**
	 * A {@link DestinationEvaluable} produced by {@link DestinationEvaluable#async(java.util.concurrent.Executor)}
	 * must dispatch {@code request(...)} through the given executor rather than the calling thread, and must
	 * deliver the destination bank to the downstream consumer instead of throwing, even though the wrapped
	 * operation is not an accelerated kernel.
	 */
	@Test(timeout = 10000)
	public void requestRunsOnConfiguredExecutor() throws InterruptedException {
		Evaluable<MemoryBank> operation = args -> null;
		MemoryBank destination = new Bytes(0, 1);
		DestinationEvaluable<MemoryBank> evaluable = new DestinationEvaluable<>(operation, destination);

		Thread callingThread = Thread.currentThread();
		AtomicReference<Thread> executionThread = new AtomicReference<>();
		AtomicReference<MemoryBank> delivered = new AtomicReference<>();
		CountDownLatch executed = new CountDownLatch(1);

		StreamingEvaluable<MemoryBank> async = evaluable.async(r -> {
			Thread worker = new Thread(r, "DestinationEvaluableAsyncExecutorTest-worker");
			worker.setDaemon(true);
			worker.start();
		});

		async.request(new Object[0], null, result -> {
			executionThread.set(Thread.currentThread());
			delivered.set(result);
			executed.countDown();
		});

		Assert.assertTrue("request() should have completed on the configured executor",
				executed.await(5, TimeUnit.SECONDS));
		Assert.assertNotEquals("request() must not run on the calling thread when an executor is configured",
				callingThread, executionThread.get());
		Assert.assertSame("the destination bank should be delivered to the downstream consumer",
				destination, delivered.get());
	}
}
