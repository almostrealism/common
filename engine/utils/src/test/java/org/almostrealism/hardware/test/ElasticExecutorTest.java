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

import io.almostrealism.streams.ElasticExecutor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the two properties {@link io.almostrealism.streams.Semaphore#CALLBACK_EXECUTOR} relies
 * on {@link ElasticExecutor} for: a burst of short tasks shares a few threads, and a task that
 * blocks on work only a later task performs can never be left without a thread.
 */
public class ElasticExecutorTest extends TestSuiteBase {
	/**
	 * A burst of short tasks submitted in one loop runs on far fewer threads than tasks.
	 */
	@Test(timeout = 30000)
	public void burstSharesThreads() throws InterruptedException {
		ElasticExecutor executor = new ElasticExecutor("burst", TimeUnit.MILLISECONDS.toNanos(1), 5000);
		int tasks = 1000;
		CountDownLatch done = new CountDownLatch(tasks);

		for (int i = 0; i < tasks; i++) {
			executor.execute(done::countDown);
		}

		assertTrue(done.await(20, TimeUnit.SECONDS));
		log("tasks=" + tasks + " threads=" + executor.getThreadCount());
		assertTrue("A burst of " + tasks + " short tasks used " + executor.getThreadCount() + " threads",
				executor.getThreadCount() < tasks / 20);
	}

	/**
	 * Every task blocks until the task submitted after it has run, so the chain can only finish if
	 * each blocked task's successor gets a thread of its own.
	 */
	@Test(timeout = 30000)
	public void blockedTasksCannotStarveLaterOnes() throws InterruptedException {
		ElasticExecutor executor = new ElasticExecutor("blocking", TimeUnit.MILLISECONDS.toNanos(1), 5000);
		int tasks = 50;
		CountDownLatch[] ran = new CountDownLatch[tasks + 1];
		for (int i = 0; i <= tasks; i++) ran[i] = new CountDownLatch(1);

		CountDownLatch done = new CountDownLatch(tasks);
		for (int i = 0; i < tasks; i++) {
			int index = i;
			executor.execute(() -> {
				ran[index].countDown();

				try {
					if (index + 1 < tasks) ran[index + 1].await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				done.countDown();
			});
		}

		assertTrue("Every blocked task must be released by the task after it",
				done.await(20, TimeUnit.SECONDS));
	}
}
