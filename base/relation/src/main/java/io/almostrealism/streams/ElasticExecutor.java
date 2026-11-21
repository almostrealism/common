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

package io.almostrealism.streams;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link Executor} with no bound on its threads that adds a thread only when queued work
 * has waited for one longer than a short starvation interval.
 *
 * <p>A cached thread pool starts a thread for every task submitted while no thread is idle, so a
 * burst of short tasks (every callback of a command buffer that has just completed, handed over in
 * one loop) creates a thread for nearly every task, and they all then idle for the keep-alive
 * period. Here a burst queues instead: the first task starts a thread if none exists, an idle
 * thread takes the next, and the queue is drained by however few threads keep up with it.</p>
 *
 * <p>A task may still block for as long as it needs, including on work that only another queued
 * task will start. Whenever the oldest queued task has waited {@link #getStarvationNanos() the
 * starvation interval} without a thread taking it, and no thread is idle or still starting, one
 * new thread is started, and another each interval for as long as that remains so. No dependency
 * between tasks can therefore leave one waiting forever: a chain of N tasks each blocked on the
 * next is given its threads within about N intervals. The thread count grows with the number of
 * tasks that are blocked at once, never with the number submitted at once, even when the first
 * thread of a burst takes longer than the interval to start.</p>
 *
 * <p>A task that throws ends its thread, and the throwable reaches that thread's
 * uncaught-exception handler, as on a thread pool. Threads are daemons and end once idle for the
 * keep-alive period.</p>
 */
public final class ElasticExecutor implements Executor {
	/** The name of every thread this executor starts. */
	private final String threadName;
	/** How long a queued task may wait for a thread before one is started for it. */
	private final long starvationNanos;
	/** How long an idle thread waits for work before it ends. */
	private final long keepAliveMillis;

	/** Tasks not yet taken by a thread, oldest first. Guarded by this executor's monitor. */
	private final Deque<Queued> queue = new ArrayDeque<>();
	/** Threads currently waiting for a task. Guarded by this executor's monitor. */
	private int idle;
	/** Threads started and not yet ended. Guarded by this executor's monitor. */
	private int threads;
	/**
	 * Threads started that have not yet begun to take tasks, and so will take a queued task
	 * shortly. Guarded by this executor's monitor.
	 */
	private int starting;
	/** Whether a starvation check is scheduled. Guarded by this executor's monitor. */
	private boolean checkScheduled;

	/** Runs the starvation checks; its single thread ends while no check is due. */
	private final ScheduledThreadPoolExecutor checks;

	/**
	 * Creates an executor.
	 *
	 * @param threadName      the name of the threads it starts
	 * @param starvationNanos how long a queued task may wait before a thread is started for it
	 * @param keepAliveMillis how long an idle thread waits for work before it ends
	 */
	public ElasticExecutor(String threadName, long starvationNanos, long keepAliveMillis) {
		this.threadName = threadName;
		this.starvationNanos = starvationNanos;
		this.keepAliveMillis = keepAliveMillis;

		AtomicInteger count = new AtomicInteger();
		this.checks = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, threadName + " starvation check " + count.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		this.checks.setKeepAliveTime(keepAliveMillis, TimeUnit.MILLISECONDS);
		this.checks.allowCoreThreadTimeOut(true);
	}

	/** Returns how long a queued task may wait for a thread before one is started for it. */
	public long getStarvationNanos() { return starvationNanos; }

	/**
	 * Returns the number of threads this executor has started that have not ended.
	 *
	 * @return the live thread count
	 */
	public synchronized int getThreadCount() { return threads; }

	/**
	 * Queues {@code task} to run on one of this executor's threads. An idle thread takes it, or,
	 * when there is no thread at all, one is started for it at once; otherwise a thread is started
	 * only if it is still queued after the starvation interval (see {@link #checkStarvation()}).
	 *
	 * @param task the work to run
	 */
	@Override
	public synchronized void execute(Runnable task) {
		queue.addLast(new Queued(task, System.nanoTime()));

		if (idle > 0) {
			notify();
		} else if (threads == 0) {
			startThread();
		}

		scheduleCheck(starvationNanos);
	}

	/**
	 * Schedules a starvation check after the given delay unless one is already scheduled.
	 * Called with this executor's monitor held.
	 *
	 * @param delayNanos how long to wait before the check
	 */
	private void scheduleCheck(long delayNanos) {
		if (checkScheduled) return;

		checkScheduled = true;
		checks.schedule(this::checkStarvation, Math.max(delayNanos, 0), TimeUnit.NANOSECONDS);
	}

	/**
	 * Starts one thread if the oldest queued task has waited at least the starvation interval and
	 * no thread is idle or still starting, either of which is about to take it, then schedules the
	 * next check while any task remains queued: after the starvation interval if the oldest task is
	 * starving, otherwise when it will be.
	 *
	 * <p>At most one thread is started per check. Every queued task can be starving only because
	 * the threads that would run it have not started yet, or are working through the tasks ahead
	 * of it; starting a thread for each of them would turn a burst of short tasks into a thread per
	 * task. A thread started per interval while the oldest task still starves gives a chain of
	 * blocked tasks a thread each, one interval apart.</p>
	 */
	private synchronized void checkStarvation() {
		checkScheduled = false;
		if (queue.isEmpty()) return;

		long waited = System.nanoTime() - queue.peekFirst().getQueuedAt();
		if (waited >= starvationNanos) {
			if (idle == 0 && starting == 0) startThread();
			scheduleCheck(starvationNanos);
		} else {
			scheduleCheck(starvationNanos - waited);
		}
	}

	// TODO(review): if t.start() throws, threads/starting stay incremented and starvation checks never start another thread
	/** Starts one thread. Called with this executor's monitor held. */
	private void startThread() {
		threads++;
		starting++;

		Thread t = new Thread(this::work, threadName);
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Runs queued tasks until none arrives within the keep-alive period. A task that throws ends
	 * the thread, which is then no longer counted.
	 */
	private void work() {
		boolean ended = false;

		synchronized (this) {
			starting--;
		}

		try {
			Runnable task;
			while ((task = take()) != null) {
				task.run();
			}

			ended = true;
		} finally {
			if (!ended) {
				synchronized (this) {
					threads--;
				}
			}
		}
	}

	/**
	 * Takes the oldest queued task, waiting for one for up to the keep-alive period, or returns
	 * {@code null} and stops counting the calling thread when none arrives in that time.
	 *
	 * @return the task to run next, or {@code null} if the calling thread should end
	 */
	private synchronized Runnable take() {
		long deadline = System.currentTimeMillis() + keepAliveMillis;

		while (queue.isEmpty()) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				threads--;
				return null;
			}

			idle++;

			try {
				wait(remaining);
			} catch (InterruptedException e) {
				// This executor's threads are never interrupted by it; keep waiting for work
			} finally {
				idle--;
			}
		}

		return queue.removeFirst().getTask();
	}

	/** A task waiting in the queue, with the time it was queued. */
	private static final class Queued {
		/** The task to run. */
		private final Runnable task;
		/** When the task was queued, by {@link System#nanoTime()}. */
		private final long queuedAt;

		/**
		 * Records a queued task.
		 *
		 * @param task     the task to run
		 * @param queuedAt when it was queued, by {@link System#nanoTime()}
		 */
		private Queued(Runnable task, long queuedAt) {
			this.task = task;
			this.queuedAt = queuedAt;
		}

		/** Returns the task to run. */
		private Runnable getTask() { return task; }

		/** Returns when the task was queued, by {@link System#nanoTime()}. */
		private long getQueuedAt() { return queuedAt; }
	}
}
