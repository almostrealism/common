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

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for the pending kernel semaphore tracking added to {@link Heap.HeapStage}.
 *
 * <p>Verifies that {@link Heap.HeapStage#destroy()} waits for all registered
 * kernel semaphores to complete before freeing memory, and that the static
 * {@link Heap#addPendingKernel(Semaphore)} method correctly delegates to the
 * active stage.</p>
 */
public class HeapStagePendingKernelTest extends TestSuiteBase {

	/**
	 * Verifies that {@link Heap.HeapStage#addPendingKernel(Semaphore)} accepts
	 * null without throwing, and that destroy still completes normally.
	 */
	@Test(timeout = 10_000)
	public void addNullPendingKernelIsNoOp() {
		Heap heap = new Heap(1024);
		heap.use(() -> {
			Heap.getDefault().getStage().addPendingKernel(null);
		});
		heap.destroy();
	}

	/**
	 * Verifies that destroy waits for a pending kernel semaphore to complete
	 * before returning. Registers a semaphore on the root stage, then verifies
	 * that {@link Heap#destroy()} blocks until the semaphore is signaled.
	 */
	@Test(timeout = 10_000)
	public void destroyWaitsForPendingKernel() throws InterruptedException {
		Heap heap = new Heap(1024);
		AtomicBoolean destroyCompleted = new AtomicBoolean(false);
		DefaultLatchSemaphore sem = new DefaultLatchSemaphore((Semaphore) null, 1);

		heap.use(() -> {
			Heap.getDefault().getStage().addPendingKernel(sem);
		});

		Thread destroyThread = new Thread(() -> {
			heap.destroy();
			destroyCompleted.set(true);
		}, "destroy-thread");
		destroyThread.start();

		// Give the destroy thread time to enter waitFor
		Thread.sleep(200);
		Assert.assertFalse("destroy() should block while semaphore is pending",
				destroyCompleted.get());

		// Complete the semaphore
		sem.countDown();

		destroyThread.join(5000);
		Assert.assertTrue("destroy() should complete after semaphore is signaled",
				destroyCompleted.get());
	}

	/**
	 * Verifies that destroy waits for multiple pending kernel semaphores
	 * and only completes after all of them are signaled.
	 */
	@Test(timeout = 10_000)
	public void destroyWaitsForMultiplePendingKernels() throws InterruptedException {
		Heap heap = new Heap(1024);
		AtomicBoolean destroyCompleted = new AtomicBoolean(false);
		DefaultLatchSemaphore sem1 = new DefaultLatchSemaphore((Semaphore) null, 1);
		DefaultLatchSemaphore sem2 = new DefaultLatchSemaphore((Semaphore) null, 1);
		DefaultLatchSemaphore sem3 = new DefaultLatchSemaphore((Semaphore) null, 1);

		heap.use(() -> {
			Heap.HeapStage stage = Heap.getDefault().getStage();
			stage.addPendingKernel(sem1);
			stage.addPendingKernel(sem2);
			stage.addPendingKernel(sem3);
		});

		Thread destroyThread = new Thread(() -> {
			heap.destroy();
			destroyCompleted.set(true);
		}, "destroy-thread");
		destroyThread.start();

		Thread.sleep(200);
		Assert.assertFalse("destroy() should block while any semaphore is pending",
				destroyCompleted.get());

		sem1.countDown();
		Thread.sleep(100);
		Assert.assertFalse("destroy() should still block with 2 semaphores pending",
				destroyCompleted.get());

		sem2.countDown();
		Thread.sleep(100);
		Assert.assertFalse("destroy() should still block with 1 semaphore pending",
				destroyCompleted.get());

		sem3.countDown();
		destroyThread.join(5000);
		Assert.assertTrue("destroy() should complete after all semaphores signaled",
				destroyCompleted.get());
	}

	/**
	 * Verifies that the static {@link Heap#addPendingKernel(Semaphore)} is a
	 * no-op when no default heap is active (does not throw).
	 */
	@Test(timeout = 10_000)
	public void staticAddPendingKernelWithNoHeapIsNoOp() {
		Assert.assertNull("No default heap should be active", Heap.getDefault());
		Heap.addPendingKernel(new DefaultLatchSemaphore((Semaphore) null, 1));
		Heap.addPendingKernel(null);
	}

	/**
	 * Verifies that the static {@link Heap#addPendingKernel(Semaphore)} registers
	 * the semaphore with the current active stage so that destroy blocks.
	 */
	@Test(timeout = 10_000)
	public void staticAddPendingKernelDelegatesToActiveStage() throws InterruptedException {
		Heap heap = new Heap(1024);
		AtomicBoolean destroyCompleted = new AtomicBoolean(false);
		DefaultLatchSemaphore sem = new DefaultLatchSemaphore((Semaphore) null, 1);

		heap.use(() -> {
			Heap.addPendingKernel(sem);
		});

		Thread destroyThread = new Thread(() -> {
			heap.destroy();
			destroyCompleted.set(true);
		}, "destroy-thread");
		destroyThread.start();

		Thread.sleep(200);
		Assert.assertFalse("destroy() should block on semaphore registered via static method",
				destroyCompleted.get());

		sem.countDown();
		destroyThread.join(5000);
		Assert.assertTrue("destroy() should complete after semaphore signaled",
				destroyCompleted.get());
	}

	/**
	 * Verifies that destroy handles a semaphore whose {@code waitFor()} throws
	 * an exception. The stage should still complete destruction despite the failure.
	 */
	@Test(timeout = 10_000)
	public void destroyHandlesFailingSemaphore() {
		Heap heap = new Heap(1024);

		Semaphore failingSemaphore = new Semaphore() {
			@Override
			public OperationMetadata getRequester() { return null; }

			@Override
			public void waitFor() { throw new RuntimeException("Simulated kernel failure"); }

			@Override
			public Semaphore withRequester(OperationMetadata requester) {
				return this;
			}
		};

		heap.use(() -> {
			Heap.getDefault().getStage().addPendingKernel(failingSemaphore);
		});

		// Should not throw - the exception should be caught and logged
		heap.destroy();
	}

	/**
	 * Verifies that {@link Heap.stage(Runnable)} waits for pending kernels
	 * registered during the staged runnable before the stage is popped.
	 */
	@Test(timeout = 10_000)
	public void stagedRunnableWaitsForPendingKernels() throws InterruptedException {
		Heap heap = new Heap(1024, 512);
		AtomicBoolean stageCompleted = new AtomicBoolean(false);
		DefaultLatchSemaphore sem = new DefaultLatchSemaphore((Semaphore) null, 1);

		Thread stageThread = new Thread(() -> {
			heap.use(() -> {
				Heap.stage(() -> {
					Heap.addPendingKernel(sem);
				});
			});
			stageCompleted.set(true);
		}, "stage-thread");
		stageThread.start();

		Thread.sleep(200);
		Assert.assertFalse("stage() should block while pending kernel is active",
				stageCompleted.get());

		sem.countDown();
		stageThread.join(5000);
		Assert.assertTrue("stage() should complete after semaphore signaled",
				stageCompleted.get());

		heap.destroy();
	}

	/**
	 * Verifies that already-completed semaphores (count of 0) do not cause
	 * destroy to block.
	 */
	@Test(timeout = 10_000)
	public void alreadyCompletedSemaphoreDoesNotBlock() {
		Heap heap = new Heap(1024);
		DefaultLatchSemaphore sem = new DefaultLatchSemaphore((Semaphore) null, 1);
		sem.countDown();

		heap.use(() -> {
			Heap.getDefault().getStage().addPendingKernel(sem);
		});

		// Should return immediately since semaphore is already completed
		heap.destroy();
	}
}
