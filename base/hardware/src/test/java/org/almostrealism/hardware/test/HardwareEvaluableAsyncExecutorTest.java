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
import io.almostrealism.streams.Semaphore;
import io.almostrealism.streams.StreamingEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.Bytes;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

	/**
	 * Reproduces the gap described in the review of {@link HardwareEvaluable#withDestination(MemoryBank)}:
	 * without a {@link HardwareEvaluable#setResultProcessor(java.util.function.UnaryOperator) result
	 * processor}, the method used to return the raw destination-based evaluable directly, discarding
	 * whichever {@link HardwareEvaluable#async(java.util.concurrent.Executor) executor} the caller had
	 * configured. {@code request(...)} on the value returned by {@code async(executor).withDestination(...)}
	 * must still dispatch through that executor rather than the calling thread.
	 */
	@Test(timeout = 10000)
	public void withDestinationPreservesConfiguredExecutorWithoutResultProcessor() throws InterruptedException {
		Evaluable<MemoryBank> kernel = args -> null;
		HardwareEvaluable<MemoryBank> evaluable = new HardwareEvaluable<>(() -> kernel, null, null, false);

		Thread callingThread = Thread.currentThread();
		AtomicReference<Thread> executionThread = new AtomicReference<>();
		CountDownLatch executed = new CountDownLatch(1);

		HardwareEvaluable<MemoryBank> async = (HardwareEvaluable<MemoryBank>) evaluable.async(r -> {
			Thread worker = new Thread(r, "HardwareEvaluableAsyncExecutorTest-destination-worker");
			worker.setDaemon(true);
			worker.start();
		});

		StreamingEvaluable<MemoryBank> sized = (StreamingEvaluable<MemoryBank>) async.withDestination(new Bytes(0, 1));
		sized.request(new Object[0], null, result -> {
			executionThread.set(Thread.currentThread());
			executed.countDown();
		});

		Assert.assertTrue("request() should have completed on the configured executor",
				executed.await(5, TimeUnit.SECONDS));
		Assert.assertNotEquals("request() must not run on the calling thread when withDestination() is called " +
						"without a result processor but an executor is configured",
				callingThread, executionThread.get());
	}

	/**
	 * Reproduces the review finding that {@link HardwareEvaluable#isDispatchBacked()} used to
	 * unconditionally report {@code true}, even though {@code requestNow(...)} forwards a
	 * non-null {@code dependsOn} directly to the wrapped kernel when it is a {@link
	 * StreamingEvaluable}, without waiting on it itself. A caller such as
	 * {@code ProcessDetailsFactory} that relies on {@code isDispatchBacked()} to decide whether
	 * it must otherwise handle the dependency would be told a dependency-disregarding kernel is
	 * safe, risking a stale argument read. The reported capability must instead track the kernel
	 * actually reached by {@code requestNow(...)}: {@code false} when it is a {@link
	 * StreamingEvaluable} that itself disregards {@code dependsOn}, {@code true} when one honors
	 * it, and {@code true} for a short-circuit or a non-streaming kernel, both of which are
	 * always ordered after {@code dependsOn} via {@code Semaphore.onComplete(...)}.
	 */
	@Test(timeout = 10000)
	public void isDispatchBackedReflectsWrappedKernelCapability() {
		StreamingKernel notDispatchBacked = new StreamingKernel(false);
		HardwareEvaluable<String> wrappingNotDispatchBacked = new HardwareEvaluable<>(
				() -> notDispatchBacked, null, null, false);
		Assert.assertFalse("isDispatchBacked() must forward false from a wrapped kernel that disregards dependsOn",
				wrappingNotDispatchBacked.isDispatchBacked());

		StreamingKernel dispatchBacked = new StreamingKernel(true);
		HardwareEvaluable<String> wrappingDispatchBacked = new HardwareEvaluable<>(
				() -> dispatchBacked, null, null, false);
		Assert.assertTrue("isDispatchBacked() must forward true from a wrapped kernel that honors dependsOn",
				wrappingDispatchBacked.isDispatchBacked());

		Evaluable<String> plainKernel = args -> "result";
		HardwareEvaluable<String> wrappingPlainKernel = new HardwareEvaluable<>(() -> plainKernel, null, null, false);
		Assert.assertTrue("isDispatchBacked() must be true for a non-streaming kernel, which is always ordered " +
						"after dependsOn via Semaphore.onComplete",
				wrappingPlainKernel.isDispatchBacked());

		Evaluable<String> shortCircuit = args -> "result";
		HardwareEvaluable<String> wrappingShortCircuit = new HardwareEvaluable<>(
				() -> notDispatchBacked, null, shortCircuit, false);
		Assert.assertTrue("isDispatchBacked() must be true whenever a shortCircuit is set, regardless of the " +
						"kernel's own capability, since the shortCircuit path always uses Semaphore.onComplete",
				wrappingShortCircuit.isDispatchBacked());
	}

	/**
	 * A kernel evaluable that is also a {@link StreamingEvaluable}, with a configurable
	 * {@link #isDispatchBacked()} answer, used to test how {@link HardwareEvaluable} forwards
	 * that capability from whichever kernel it wraps.
	 */
	private static class StreamingKernel implements Evaluable<String>, StreamingEvaluable<String> {
		/** The fixed answer {@link #isDispatchBacked()} should give. */
		private final boolean dispatchBacked;

		/** Creates a kernel whose {@link #isDispatchBacked()} always returns {@code dispatchBacked}. */
		StreamingKernel(boolean dispatchBacked) {
			this.dispatchBacked = dispatchBacked;
		}

		@Override
		public String evaluate(Object... args) { return "result"; }

		@Override
		public void request(Object[] args, Semaphore dependsOn) { }

		@Override
		public void setDownstream(Consumer<String> consumer) { }

		@Override
		public boolean isDispatchBacked() { return dispatchBacked; }
	}
}
