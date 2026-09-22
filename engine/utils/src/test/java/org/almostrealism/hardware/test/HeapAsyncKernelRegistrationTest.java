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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression test for the {@link Heap} thread-local propagation gap in
 * {@code AcceleratedOperation#apply}: the listener that registers a dispatched kernel's
 * completion with {@link Heap.HeapStage#addPendingKernel(io.almostrealism.streams.Semaphore)}
 * can run on a different thread than the one that called {@code apply} (see that method's
 * javadoc), so a registration that relies on {@link Heap#getDefault()} inside the listener
 * silently loses the semaphore whenever it runs on that other thread &mdash; {@link Heap#pop()}
 * then frees the stage's memory without waiting for a kernel that is still running.
 *
 * <p>This is reproduced here with a Metal kernel whose completion is held outstanding by a
 * foreign dependency (the same host-signaled bridge exercised by
 * {@link SemaphoreChainBatchingTest#foreignDependencyBridgesWithoutHostWait()}), so the
 * dispatch returns from {@code submit} while genuinely incomplete. Before the fix, exiting the
 * enclosing {@link Heap#stage(Runnable)} block returns immediately regardless; after the fix,
 * it blocks until the foreign dependency is released.</p>
 */
public class HeapAsyncKernelRegistrationTest extends TestSuiteBase {

	/**
	 * Repeats {@link #verifyStageExitWaitsForOutstandingKernel()} ten times, skipping when no
	 * Metal backend is available.
	 */
	@Test(timeout = 60000)
	public void stagedKernelWithOutstandingCompletionBlocksStageExit() throws InterruptedException {
		if (SemaphoreChainBatchingTest.metalContext() == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		for (int i = 0; i < 10; i++) {
			verifyStageExitWaitsForOutstandingKernel();
		}
	}

	/**
	 * Dispatches a Metal kernel with an outstanding foreign dependency inside a
	 * {@link Heap#stage(Runnable)} on a background thread, and verifies that the stage does not
	 * exit until that dependency is released.
	 */
	private void verifyStageExitWaitsForOutstandingKernel() throws InterruptedException {
		int n = 16;

		PackedCollection src = new PackedCollection(n);
		PackedCollection dst = new PackedCollection(n);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		Heap heap = new Heap(4096, 2048);
		DefaultLatchSemaphore foreign = new DefaultLatchSemaphore(
				new OperationMetadata("foreignWork", "outstanding dependency held open by the test"), 1);
		AtomicBoolean stageExited = new AtomicBoolean(false);

		Thread stageThread = new Thread(() -> heap.use(() -> {
			Heap.stage(() -> {
				Submittable op = SemaphoreChainBatchingTest.copyKernel(src, dst, n, ComputeRequirement.MTL);
				op.submit(foreign);
			});
			stageExited.set(true);
		}), "heap-stage-thread");
		stageThread.start();

		Thread.sleep(300);
		Assert.assertFalse("Heap.stage() exit must wait for the dispatched kernel's completion " +
				"before freeing its memory, even though the registering listener ran on a " +
				"different thread than the one that entered the stage",
				stageExited.get());

		foreign.countDown();
		stageThread.join(5000);
		Assert.assertTrue("Heap.stage() should exit once the outstanding dependency completes",
				stageExited.get());

		heap.destroy();

		for (int i = 0; i < n; i++) {
			assertEquals(src.toDouble(i), dst.toDouble(i));
		}
	}
}
