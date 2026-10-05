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
import java.util.concurrent.TimeUnit;
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
 * <p>{@link #destroy(Runnable)} stops accepting work, queues one last task on the confined thread
 * (typically draining whatever work is still outstanding) behind any work already submitted, and
 * shuts the thread down once that work has run. Submission and destruction are coordinated, so no
 * work can ever be queued behind the final task. The thread is shut down even if that last task
 * fails, so a failing drain can never leave the thread alive.</p>
 */
public class ConfinedExecutor implements Destroyable, ConsoleFeatures {
	/** How every task is run on the confined thread. */
	private final Consumer<Runnable> taskScope;

	/** The single thread all work is confined to. */
	private final ExecutorService executor;

	/**
	 * Guards submission against destruction: work is only queued while {@link #destroyed} is
	 * false, and the final task is queued in the same critical section that sets it, so nothing
	 * can be queued after the final task.
	 */
	private final Object submission = new Object();

	/** The confined thread, once the executor has started it. */
	private volatile Thread confinedThread;

	/**
	 * Whether {@link #destroy(Runnable)} has been called. Written only while holding
	 * {@link #submission}; volatile so {@link #isActive()} can be read without it.
	 */
	private volatile boolean destroyed;

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
		this.executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = Executors.defaultThreadFactory().newThread(r);
			confinedThread = t;
			return t;
		});
	}

	/**
	 * Runs a task on the confined thread and waits for it to finish.
	 *
	 * <p>A failure of the task is rethrown to the caller. An interrupt abandons the wait while
	 * the task may still be queued or running; the caller then proceeds as though it finished,
	 * so the abandonment is logged rather than silently swallowed.</p>
	 *
	 * @param task the work to run
	 * @throws IllegalStateException if this executor has been destroyed, or if called from a
	 *                               task already running on the confined thread, which could
	 *                               never finish waiting for work queued behind itself
	 */
	public void run(Runnable task) {
		await(submit(task), true);
	}

	/**
	 * Runs a task on the confined thread and waits for it to finish, or, if this executor is
	 * destroyed before the task can be submitted, runs {@code refused} on the calling thread
	 * instead, inside the same task scope.
	 *
	 * <p>This is for cleanup a caller owes the confined state whether or not the executor
	 * still exists — for example withdrawing a registration that the executor's final task has
	 * already finished with. Before running {@code refused}, the caller waits for the confined
	 * thread to finish every task submitted before destruction, including the final task, so
	 * {@code refused} never races confined work and observes everything that work did. The one
	 * exception is a call made on the confined thread itself, which cannot wait for its own
	 * termination and runs {@code refused} immediately. A failure of {@code task} itself is
	 * rethrown as by {@link #run(Runnable)}.</p>
	 *
	 * @param task    the work to run on the confined thread
	 * @param refused the work to run on the calling thread if this executor refuses {@code task}
	 */
	public void runOrElse(Runnable task, Runnable refused) {
		runOrElse(task, refused, true);
	}

	/**
	 * Behaves as {@link #runOrElse(Runnable, Runnable)}, except that an interrupt never abandons
	 * the wait for {@code task}: the caller always waits until the task has finished, and its
	 * interrupt status, whether it was set before the call or arrived during it, is restored
	 * afterwards.
	 *
	 * <p>This is for coordination whose next step depends on what the task did — reading state
	 * the task recorded, or deciding from it whether the executor can be shut down — and which
	 * would act on a stale view if an interrupt let it proceed before the task ran.</p>
	 *
	 * @param task    the work to run on the confined thread
	 * @param refused the work to run on the calling thread if this executor refuses {@code task}
	 */
	public void runOrElseUninterruptibly(Runnable task, Runnable refused) {
		runOrElse(task, refused, false);
	}

	/**
	 * Runs {@code task} on the confined thread, or {@code refused} on the calling thread if this
	 * executor is destroyed, as described by {@link #runOrElse(Runnable, Runnable)}.
	 *
	 * @param task          the work to run on the confined thread
	 * @param refused       the work to run on the calling thread if this executor refuses {@code task}
	 * @param interruptible whether an interrupt abandons the wait for an accepted {@code task}
	 */
	private void runOrElse(Runnable task, Runnable refused, boolean interruptible) {
		Future<?> future = submitIfActive(task);

		if (future != null) {
			await(future, interruptible);
			return;
		}

		if (Thread.currentThread() != confinedThread) awaitTermination();
		taskScope.accept(refused);
	}

	/**
	 * Returns whether this executor still accepts work.
	 *
	 * @return true until {@link #destroy()} has been called
	 */
	public boolean isActive() { return !destroyed; }

	/**
	 * Stops accepting work, runs a final task on the confined thread after all work already
	 * submitted, then shuts the thread down.
	 *
	 * <p>The thread is shut down whether or not the final task succeeds; a failure of the task
	 * is rethrown afterwards. Destroying an executor that has already been destroyed does
	 * nothing.</p>
	 *
	 * <p>An interrupt never abandons the wait for the final task, since destruction is cleanup
	 * the caller owes regardless and a failure of that cleanup must not be lost: the caller
	 * waits until the final task has finished, and its interrupt status, whether set before the
	 * call or received during it, is restored afterwards, as by
	 * {@link #runOrElseUninterruptibly}.</p>
	 *
	 * <p>A final task cannot be run by a call made from the confined thread itself: the final
	 * task would be queued behind the task making the call, which would then wait for it
	 * forever. Such a call is rejected before anything changes, so the executor stays active.
	 * Destroying without a final task waits for nothing and is allowed from any thread.</p>
	 *
	 * @param finalTask the last work to run on the confined thread, or {@code null} for none
	 * @throws IllegalStateException if {@code finalTask} is given and this is called from the
	 *                               confined thread while the executor is still active
	 */
	public void destroy(Runnable finalTask) {
		Future<?> last;

		synchronized (submission) {
			if (destroyed) return;
			if (finalTask != null) requireOffConfinedThread();
			destroyed = true;

			try {
				last = finalTask == null ? null : executor.submit(() -> taskScope.accept(finalTask));
			} finally {
				executor.shutdown();
			}
		}

		if (last != null) await(last, false);
	}

	/**
	 * Shuts the confined thread down without running any further work.
	 */
	@Override
	public void destroy() {
		destroy(null);
	}

	/**
	 * Queues a task on the confined thread, inside the task scope.
	 *
	 * @param task the work to queue
	 * @return the queued task
	 * @throws IllegalStateException if this executor has been destroyed, or if called from the
	 *                               confined thread
	 */
	private Future<?> submit(Runnable task) {
		Future<?> future = submitIfActive(task);

		if (future == null) {
			throw new IllegalStateException("The executor has been destroyed");
		}

		return future;
	}

	/**
	 * Queues a task on the confined thread, inside the task scope, unless this executor has been
	 * destroyed. Refusal is decided here, at submission, so a caller never has to infer it from
	 * how the task later failed.
	 *
	 * @param task the work to queue
	 * @return the queued task, or {@code null} if this executor has been destroyed
	 * @throws IllegalStateException if called from the confined thread while this executor is
	 *                               still active
	 */
	private Future<?> submitIfActive(Runnable task) {
		synchronized (submission) {
			if (destroyed) return null;

			requireOffConfinedThread();
			return executor.submit(() -> taskScope.accept(task));
		}
	}

	/**
	 * Rejects a call that would wait on the confined thread for work queued behind the task
	 * that is making the call. The single confined thread can never run that work, so the wait
	 * would never end.
	 *
	 * <p>{@link #run} and {@link #destroy(Runnable)} perform this check themselves. A caller
	 * that must take a lock of its own before submitting work calls it first, so a call from the
	 * confined thread is rejected instead of blocking on a lock held by a thread that is waiting
	 * for the confined thread.</p>
	 *
	 * @throws IllegalStateException if called from the confined thread
	 */
	public void requireOffConfinedThread() {
		if (Thread.currentThread() == confinedThread) {
			throw new IllegalStateException(
					"A task on the confined thread cannot wait for work queued behind itself");
		}
	}

	/**
	 * Waits, without giving up on an interrupt, for the confined thread to finish every task
	 * queued before this executor was destroyed. The caller's interrupt status is restored
	 * afterwards.
	 */
	private void awaitTermination() {
		boolean interrupted = false;

		while (true) {
			try {
				if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}

		if (interrupted) Thread.currentThread().interrupt();
	}

	/**
	 * Waits for a submitted task, rethrowing its failure.
	 *
	 * <p>When {@code interruptible}, an interrupt abandons the wait with a warning, leaving the
	 * caller's interrupt status set. Otherwise the wait continues until the task has finished,
	 * and an interrupt received meanwhile is restored once it has.</p>
	 *
	 * @param future        the submitted task
	 * @param interruptible whether an interrupt abandons the wait
	 */
	private void await(Future<?> future, boolean interruptible) {
		boolean interrupted = false;

		try {
			while (true) {
				try {
					future.get();
					return;
				} catch (InterruptedException e) {
					interrupted = true;

					if (interruptible) {
						warn("Interrupted while awaiting a confined task; the work it performs may not have completed");
						return;
					}
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof RuntimeException) throw (RuntimeException) cause;
					if (cause instanceof Error) throw (Error) cause;
					throw new RuntimeException(cause);
				}
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}
}
