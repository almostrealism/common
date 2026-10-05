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

package io.almostrealism.concurrent;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.io.ConsoleFeatures;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Confines a set of work to one thread: every task runs on the same thread, one at a time,
 * and the caller waits for it.
 *
 * <p>This is for state that must only ever be touched from a single thread, such as a
 * native command queue whose objects are not thread-safe. Callers on any thread submit work
 * with {@link #run(Runnable)} and block until it has finished on the confined thread.</p>
 *
 * <p>Every task runs inside a <em>task scope</em> supplied at construction — for example an
 * Objective-C autorelease pool pushed before the task and popped after it — so per-task
 * setup and teardown cannot be forgotten by a caller.</p>
 *
 * <p>{@link #destroy(Runnable)} runs one last task on the confined thread (typically draining
 * whatever work is still outstanding) and then shuts the thread down. The thread is shut down
 * even if that last task fails, so a failing drain can never leave the thread alive.</p>
 */
public class ConfinedExecutor implements Destroyable, ConsoleFeatures {
	/** How every task is run on the confined thread. */
	private final Consumer<Runnable> taskScope;

	/**
	 * The single thread all work is confined to, or {@code null} once destroyed. Volatile so a
	 * caller on any thread that is refused work after {@link #destroy(Runnable)} also observes
	 * {@link #isActive()} as false.
	 */
	private volatile ExecutorService executor;

	/**
	 * Creates an executor that runs each task as is.
	 */
	public ConfinedExecutor() {
		this(Runnable::run);
	}

	/**
	 * Creates an executor that runs each task through the given scope.
	 *
	 * @param taskScope runs one task, with whatever setup and teardown each task needs
	 */
	public ConfinedExecutor(Consumer<Runnable> taskScope) {
		this.taskScope = taskScope;
		this.executor = Executors.newSingleThreadExecutor();
	}

	/**
	 * Runs a task on the confined thread and waits for it to finish.
	 *
	 * <p>A failure of the task is rethrown to the caller. An interrupt abandons the wait while
	 * the task may still be queued or running; the caller then proceeds as though it finished,
	 * so the abandonment is logged rather than silently swallowed.</p>
	 *
	 * @param task the work to run
	 * @throws IllegalStateException if this executor has been destroyed
	 * @throws java.util.concurrent.RejectedExecutionException if it is destroyed while the task
	 *         is being submitted
	 */
	public void run(Runnable task) {
		ExecutorService current = executor;
		if (current == null) {
			throw new IllegalStateException("The executor has been destroyed");
		}

		await(current.submit(() -> taskScope.accept(task)));
	}

	/**
	 * Runs a task on the confined thread and waits for it to finish, or, if this executor is
	 * destroyed before the task can start, runs {@code refused} on the calling thread instead,
	 * inside the same task scope.
	 *
	 * <p>This is for cleanup a caller owes the confined state whether or not the executor
	 * still exists — for example withdrawing a registration that the executor's final task has
	 * already finished with. Once {@link #destroy(Runnable)} has run its final task nothing
	 * else runs on the confined thread, so {@code refused} cannot race confined work. A failure
	 * of {@code task} itself is rethrown as by {@link #run(Runnable)}.</p>
	 *
	 * @param task    the work to run on the confined thread
	 * @param refused the work to run on the calling thread if this executor refuses {@code task}
	 */
	public void runOrElse(Runnable task, Runnable refused) {
		AtomicBoolean started = new AtomicBoolean();

		try {
			run(() -> {
				started.set(true);
				task.run();
			});
		} catch (RuntimeException e) {
			if (started.get() || isActive()) throw e;
			taskScope.accept(refused);
		}
	}

	/**
	 * Returns whether this executor still accepts work.
	 *
	 * @return true until {@link #destroy()} has been called
	 */
	public boolean isActive() { return executor != null; }

	/**
	 * Runs a final task on the confined thread, then shuts the thread down.
	 *
	 * <p>The thread is shut down whether or not the final task succeeds; a failure of the task
	 * is rethrown afterwards. Destroying an executor that has already been destroyed does
	 * nothing.</p>
	 *
	 * @param finalTask the last work to run on the confined thread, or {@code null} for none
	 */
	public void destroy(Runnable finalTask) {
		ExecutorService current = executor;
		if (current == null) return;

		try {
			if (finalTask != null) {
				await(current.submit(() -> taskScope.accept(finalTask)));
			}
		} finally {
			executor = null;
			current.shutdown();
		}
	}

	/**
	 * Shuts the confined thread down without running any further work.
	 */
	@Override
	public void destroy() {
		destroy(null);
	}

	/**
	 * Waits for a submitted task, rethrowing its failure.
	 *
	 * @param future the submitted task
	 */
	private void await(Future<?> future) {
		try {
			future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			warn("Interrupted while awaiting a confined task; the work it performs may not have completed");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			if (cause instanceof Error) throw (Error) cause;
			throw new RuntimeException(cause);
		}
	}
}
