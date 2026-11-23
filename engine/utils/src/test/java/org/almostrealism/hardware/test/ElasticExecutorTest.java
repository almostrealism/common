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
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
	 * A burst queued behind a thread that is busy for many starvation intervals starts a few
	 * threads, not one per starving task: every task of the burst starves while the only thread
	 * is busy, and starting a thread for each of them is the thread-per-callback cost the executor
	 * exists to avoid.
	 */
	@Test(timeout = 30000)
	public void burstBehindBusyThreadSharesThreads() throws InterruptedException {
		ElasticExecutor executor = new ElasticExecutor("busy", TimeUnit.MILLISECONDS.toNanos(1), 5000);
		int tasks = 200;
		CountDownLatch busy = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(tasks);

		executor.execute(() -> {
			busy.countDown();

			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(busy.await(10, TimeUnit.SECONDS));

		for (int i = 0; i < tasks; i++) {
			executor.execute(done::countDown);
		}

		assertTrue(done.await(20, TimeUnit.SECONDS));
		log("tasks=" + tasks + " threads=" + executor.getThreadCount());
		assertTrue("A burst of " + tasks + " short tasks behind a busy thread used " +
						executor.getThreadCount() + " threads",
				executor.getThreadCount() < tasks / 4);
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

	/**
	 * With the default factory no task thread is a daemon, whichever path started it: the thread
	 * {@link ElasticExecutor#execute} starts on a daemon caller, and the thread a starvation check
	 * starts on the daemon starvation-check thread, from which it would otherwise inherit daemon
	 * status and leave queued callbacks to be abandoned when the JVM exits.
	 */
	@Test(timeout = 30000)
	public void defaultTaskThreadsAreNeverDaemons() throws InterruptedException {
		ElasticExecutor executor = new ElasticExecutor("non-daemon", TimeUnit.MILLISECONDS.toNanos(1), 5000);
		AtomicBoolean[] daemon = { new AtomicBoolean(true), new AtomicBoolean(true) };
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);

		Thread submitter = new Thread(() -> {
			executor.execute(() -> {
				daemon[0].set(Thread.currentThread().isDaemon());

				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				done.countDown();
			});

			executor.execute(() -> {
				daemon[1].set(Thread.currentThread().isDaemon());
				release.countDown();
				done.countDown();
			});
		}, "daemon submitter");
		submitter.setDaemon(true);
		submitter.start();
		submitter.join();

		assertTrue("The second task needs a thread started by a starvation check",
				done.await(20, TimeUnit.SECONDS));
		assertEquals("Both tasks ran on threads of their own", 2, executor.getThreadCount());
		assertFalse("A thread started by execute on a daemon caller is not a daemon", daemon[0].get());
		assertFalse("A thread started by a starvation check is not a daemon", daemon[1].get());
	}

	/**
	 * When the first threads cannot be created, the tasks that asked for them stay queued and run
	 * once a later starvation check succeeds in starting a thread, and the failed attempts are not
	 * counted as threads: a failed start counted as still starting would keep every later check
	 * from starting a replacement, leaving the queued tasks stuck forever.
	 */
	@Test(timeout = 30000)
	public void failedThreadStartIsRetried() throws InterruptedException {
		int failures = 3;
		AtomicInteger attempts = new AtomicInteger();
		ThreadFactory factory = r -> {
			if (attempts.incrementAndGet() <= failures) {
				throw new OutOfMemoryError("unable to create native thread (simulated)");
			}

			return new Thread(r, "failing-start");
		};

		ElasticExecutor executor = new ElasticExecutor("failing-start", factory,
				TimeUnit.MILLISECONDS.toNanos(1), 5000);
		CountDownLatch done = new CountDownLatch(1);

		try {
			executor.execute(done::countDown);
			Assert.fail("The failure to create the first thread must reach the caller");
		} catch (OutOfMemoryError expected) {
			assertEquals("A thread that was never started is not counted", 0, executor.getThreadCount());
		}

		assertTrue("A task queued while no thread could be created must run once one can be",
				done.await(20, TimeUnit.SECONDS));
		assertEquals("Each starvation check retries the start once until it succeeds",
				failures + 1, attempts.get());
		assertEquals("Only the thread that started is counted", 1, executor.getThreadCount());
	}

	/**
	 * Tasks that each block until every one of them has started need a thread apiece, and still
	 * get them when every other thread start fails, because each failed start is rolled back and
	 * retried by the next starvation check.
	 */
	@Test(timeout = 30000)
	public void blockedTasksSurviveFailedThreadStarts() throws InterruptedException {
		AtomicInteger attempts = new AtomicInteger();
		ThreadFactory factory = r -> {
			if (attempts.incrementAndGet() % 2 == 0) {
				throw new OutOfMemoryError("unable to create native thread (simulated)");
			}

			return new Thread(r, "blocked-failing-start");
		};

		ElasticExecutor executor = new ElasticExecutor("blocked-failing-start", factory,
				TimeUnit.MILLISECONDS.toNanos(1), 5000);
		int tasks = 10;
		CountDownLatch started = new CountDownLatch(tasks);
		CountDownLatch done = new CountDownLatch(tasks);

		for (int i = 0; i < tasks; i++) {
			executor.execute(() -> {
				started.countDown();

				try {
					started.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				done.countDown();
			});
		}

		assertTrue("Every task must get a thread despite the failed starts",
				done.await(20, TimeUnit.SECONDS));
		assertEquals("Each task ran on a thread of its own", tasks, executor.getThreadCount());
		assertEquals("Every failed start was retried exactly once before the next succeeded",
				2 * tasks - 1, attempts.get());
	}
}
