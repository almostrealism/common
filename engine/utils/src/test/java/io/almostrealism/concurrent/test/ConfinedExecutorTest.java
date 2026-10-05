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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests {@link ConfinedExecutor#runOrElse(Runnable, Runnable)}: work runs on the confined
 * thread while the executor is active, falls back to the calling thread (inside the task
 * scope) once the executor has been destroyed, and a failure of the confined work itself is
 * never mistaken for a refusal.
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
}
