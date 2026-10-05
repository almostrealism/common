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

package io.almostrealism.concurrent.test;

import io.almostrealism.concurrent.ConfinedExecutor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests {@link ConfinedExecutor#runOrElse(Runnable, Runnable)}: work runs on the confined
 * thread while the executor is active, falls back to the calling thread (inside the task
 * scope) once the executor has been destroyed, and a failure of the confined work itself is
 * never mistaken for a refusal. Also tests that {@link ConfinedExecutor#destroy(Runnable)}
 * never lets work be queued behind its final task, and that a refused fallback waits for the
 * work queued before destruction.
 */
public class ConfinedExecutorTest extends TestSuiteBase {

	/**
	 * While the executor is active, the task runs on the confined thread and the fallback
	 * never runs.
	 */
	@Test(timeout = 10000)
	public void runOrElseRunsTaskWhileActive() {
		ConfinedExecutor executor = new ConfinedExecutor();

		try {
			AtomicReference<Thread> taskThread = new AtomicReference<>();
			List<String> events = Collections.synchronizedList(new ArrayList<>());

			executor.runOrElse(() -> {
				taskThread.set(Thread.currentThread());
				events.add("task");
			}, () -> events.add("refused"));

			assertEquals(List.of("task"), events);
			assertNotNull(taskThread.get());
			Assert.assertNotSame(Thread.currentThread(), taskThread.get());
		} finally {
			executor.destroy();
		}
	}

	/**
	 * Once the executor has been destroyed, {@link ConfinedExecutor#run} refuses work, and
	 * {@code runOrElse} runs the fallback on the calling thread instead, wrapped in the task
	 * scope, without throwing.
	 */
	@Test(timeout = 10000)
	public void runOrElseFallsBackAfterDestroy() {
		List<String> events = Collections.synchronizedList(new ArrayList<>());
		ConfinedExecutor executor = new ConfinedExecutor(task -> {
			events.add("enter");
			try {
				task.run();
			} finally {
				events.add("exit");
			}
		});

		executor.destroy(() -> events.add("final"));
		assertFalse(executor.isActive());
		assertEquals(List.of("enter", "final", "exit"), events);
		events.clear();

		try {
			executor.run(() -> events.add("task"));
			Assert.fail("A destroyed executor must refuse work");
		} catch (IllegalStateException expected) {
			assertTrue(events.isEmpty());
		}

		AtomicReference<Thread> fallbackThread = new AtomicReference<>();
		executor.runOrElse(() -> events.add("task"), () -> {
			fallbackThread.set(Thread.currentThread());
			events.add("refused");
		});

		assertEquals(List.of("enter", "refused", "exit"), events);
		Assert.assertSame(Thread.currentThread(), fallbackThread.get());
	}

	/**
	 * A failure thrown by the task on an active executor is rethrown to the caller and does
	 * not trigger the fallback.
	 */
	@Test(timeout = 10000)
	public void runOrElseRethrowsTaskFailure() {
		ConfinedExecutor executor = new ConfinedExecutor();

		try {
			List<String> events = Collections.synchronizedList(new ArrayList<>());

			try {
				executor.runOrElse(() -> {
					events.add("task");
					throw new IllegalStateException("task failure");
				}, () -> events.add("refused"));
				Assert.fail("The task's failure must be rethrown");
			} catch (IllegalStateException e) {
				assertEquals("task failure", e.getMessage());
			}

			assertEquals(List.of("task"), events);
		} finally {
			executor.destroy();
		}
	}

	/**
	 * A task that fails after the executor has been destroyed underneath it is still
	 * reported as a task failure: the fallback is reserved for work the executor never
	 * started, so an inactive executor alone does not turn a failure into a refusal.
	 */
	@Test(timeout = 10000)
	public void runOrElseRethrowsFailureOfStartedTaskAfterDestroy() {
		ConfinedExecutor executor = new ConfinedExecutor();
		List<String> events = Collections.synchronizedList(new ArrayList<>());

		try {
			executor.runOrElse(() -> {
				events.add("task");
				executor.destroy();
				throw new IllegalStateException("started task failure");
			}, () -> events.add("refused"));
			Assert.fail("The started task's failure must be rethrown");
		} catch (IllegalStateException e) {
			assertEquals("started task failure", e.getMessage());
		}

		assertFalse(executor.isActive());
		assertEquals(List.of("task"), events);
	}

	/**
	 * Regression: a submitted task whose task scope fails before ever invoking the task is still
	 * reported as a failure, even when the executor is destroyed before the caller sees it. The
	 * task was accepted, so it was not refused, and the fallback must not run in its place.
	 */
	@Test(timeout = 10000)
	public void runOrElseRethrowsScopeFailureAfterDestroy() {
		AtomicReference<ConfinedExecutor> self = new AtomicReference<>();
		List<String> events = Collections.synchronizedList(new ArrayList<>());
		ConfinedExecutor executor = new ConfinedExecutor(task -> {
			events.add("scope");
			self.get().destroy();
			throw new IllegalStateException("scope failure");
		});
		self.set(executor);

		try {
			executor.runOrElse(() -> events.add("task"), () -> events.add("refused"));
			Assert.fail("The task scope's failure must be rethrown");
		} catch (IllegalStateException e) {
			assertEquals("scope failure", e.getMessage());
		}

		assertFalse(executor.isActive());
		assertEquals(List.of("scope"), events);
	}

	/**
	 * Once {@link ConfinedExecutor#destroy(Runnable)} has started, work submitted from another
	 * thread is refused even while the final task is still running, so nothing can run on the
	 * confined thread after the final task.
	 */
	@Test(timeout = 10000)
	public void destroyRefusesWorkWhileFinalTaskRuns() throws InterruptedException {
		ConfinedExecutor executor = new ConfinedExecutor();
		CountDownLatch finalStarted = new CountDownLatch(1);
		CountDownLatch releaseFinal = new CountDownLatch(1);
		List<String> events = Collections.synchronizedList(new ArrayList<>());

		Thread destroyer = new Thread(() -> executor.destroy(() -> {
			finalStarted.countDown();
			awaitLatch(releaseFinal);
			events.add("final");
		}));
		destroyer.start();
		assertTrue(finalStarted.await(5, TimeUnit.SECONDS));

		try {
			executor.run(() -> events.add("late"));
			Assert.fail("Work submitted after destroy began must be refused");
		} catch (IllegalStateException expected) {
			assertFalse(executor.isActive());
		} finally {
			releaseFinal.countDown();
			destroyer.join(5000);
		}

		assertFalse(destroyer.isAlive());
		assertEquals(List.of("final"), events);
	}

	/**
	 * A fallback refused by a destroyed executor runs only after the confined thread has
	 * finished every task queued before destruction, including the final task, even when the
	 * calling thread is already interrupted; the caller's interrupt status is preserved. This
	 * is what lets a withdrawal observe a registration made by work that was still queued when
	 * the executor was destroyed.
	 */
	@Test(timeout = 10000)
	public void runOrElseFallbackWaitsForQueuedWork() throws InterruptedException {
		ConfinedExecutor executor = new ConfinedExecutor();
		CountDownLatch queuedStarted = new CountDownLatch(1);
		CountDownLatch releaseQueued = new CountDownLatch(1);
		List<String> events = Collections.synchronizedList(new ArrayList<>());

		Thread worker = new Thread(() -> executor.run(() -> {
			queuedStarted.countDown();
			awaitLatch(releaseQueued);
			events.add("queued");
		}));
		worker.start();
		assertTrue(queuedStarted.await(5, TimeUnit.SECONDS));

		Thread destroyer = new Thread(() -> executor.destroy(() -> events.add("final")));
		destroyer.start();
		while (executor.isActive()) {
			Thread.sleep(1);
		}

		AtomicBoolean interruptedAfter = new AtomicBoolean();
		Thread caller = new Thread(() -> {
			Thread.currentThread().interrupt();
			executor.runOrElse(() -> events.add("task"), () -> events.add("refused"));
			interruptedAfter.set(Thread.currentThread().isInterrupted());
		});
		caller.start();

		caller.join(300);
		assertTrue("The fallback must wait for work queued before destruction", caller.isAlive());
		assertEquals(List.of(), events);

		releaseQueued.countDown();
		caller.join(5000);
		worker.join(5000);
		destroyer.join(5000);

		assertFalse(caller.isAlive());
		assertEquals(List.of("queued", "final", "refused"), events);
		assertTrue(interruptedAfter.get());
	}

	/**
	 * A task on the confined thread that destroys the executor with a final task is refused
	 * instead of deadlocking: the final task would queue behind the calling task, which would
	 * then wait for it forever. The refusal happens before any state changes, so the executor
	 * remains active and a later destruction from another thread still runs its final task.
	 */
	@Test(timeout = 10000)
	public void destroyWithFinalTaskFromConfinedThreadIsRejected() {
		ConfinedExecutor executor = new ConfinedExecutor();
		List<String> events = Collections.synchronizedList(new ArrayList<>());
		AtomicReference<RuntimeException> failure = new AtomicReference<>();

		try {
			executor.run(() -> {
				try {
					executor.destroy(() -> events.add("rejectedFinal"));
				} catch (RuntimeException e) {
					failure.set(e);
				}
			});

			assertTrue("Expected IllegalStateException but got " + failure.get(),
					failure.get() instanceof IllegalStateException);
			assertTrue(executor.isActive());
			assertEquals(List.of(), events);

			executor.run(() -> events.add("stillAccepted"));
		} finally {
			executor.destroy(() -> events.add("final"));
		}

		assertFalse(executor.isActive());
		assertEquals(List.of("stillAccepted", "final"), events);
	}

	/**
	 * A task on the confined thread that runs further work on the same executor is refused
	 * instead of deadlocking, and the nested work never runs.
	 */
	@Test(timeout = 10000)
	public void runFromConfinedThreadIsRejected() {
		ConfinedExecutor executor = new ConfinedExecutor();
		List<String> events = Collections.synchronizedList(new ArrayList<>());
		AtomicReference<RuntimeException> failure = new AtomicReference<>();

		try {
			executor.run(() -> {
				try {
					executor.runOrElse(() -> events.add("nested"), () -> events.add("refused"));
				} catch (RuntimeException e) {
					failure.set(e);
				}
			});

			assertTrue("Expected IllegalStateException but got " + failure.get(),
					failure.get() instanceof IllegalStateException);
			assertEquals(List.of(), events);
			assertTrue(executor.isActive());
		} finally {
			executor.destroy();
		}
	}

	/**
	 * {@link ConfinedExecutor#requireOffConfinedThread()} rejects a call from the confined thread
	 * and accepts one from any other thread, without changing the executor's state either way.
	 */
	@Test(timeout = 10000)
	public void requireOffConfinedThreadRejectsOnlyTheConfinedThread() {
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicReference<RuntimeException> failure = new AtomicReference<>();

		try {
			executor.requireOffConfinedThread();
			executor.run(() -> {
				try {
					executor.requireOffConfinedThread();
				} catch (RuntimeException e) {
					failure.set(e);
				}
			});

			assertTrue("Expected IllegalStateException but got " + failure.get(),
					failure.get() instanceof IllegalStateException);
			assertTrue(executor.isActive());

			List<String> events = Collections.synchronizedList(new ArrayList<>());
			executor.run(() -> events.add("after"));
			assertEquals(List.of("after"), events);
		} finally {
			executor.destroy();
		}
	}

	/**
	 * Destroying without a final task waits for nothing, so it is allowed from the confined
	 * thread: the executor stops accepting work and the calling task completes normally.
	 */
	@Test(timeout = 10000)
	public void destroyWithoutFinalTaskFromConfinedThreadSucceeds() {
		ConfinedExecutor executor = new ConfinedExecutor();
		AtomicBoolean completed = new AtomicBoolean();

		executor.run(() -> {
			executor.destroy();
			completed.set(true);
		});

		assertTrue(completed.get());
		assertFalse(executor.isActive());

		try {
			executor.run(() -> { });
			Assert.fail("A destroyed executor must refuse work");
		} catch (IllegalStateException expected) {
			assertFalse(executor.isActive());
		}
	}

	/**
	 * Waits for a latch, failing the calling task if interrupted.
	 *
	 * @param latch the latch to wait for
	 */
	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for the test to release the task");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
