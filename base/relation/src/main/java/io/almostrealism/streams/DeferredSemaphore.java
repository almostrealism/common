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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The completion of work that may only start once another {@link Semaphore} has
 * completed, started without holding up the thread that asked for it.
 *
 * <p>{@link Semaphore#then(Supplier)} returns one of these at once. When the dependency
 * settles, the work runs on {@link Semaphore#CALLBACK_EXECUTOR} and yields the
 * semaphore of whatever it started (or {@code null} if it finished synchronously);
 * {@link #waitFor()} waits for the work to start and then for that semaphore. A
 * failure of the dependency or of the work is not swallowed: it is rethrown from
 * {@link #waitFor()}, and the work does not run after a failed dependency.</p>
 *
 * <p>The work is started from the dependency's own {@link Semaphore#whenSettled settlement},
 * so no thread is occupied while the dependency is pending, however many deferred operations
 * are queued behind it. A dependency that only settles once someone waits for it (a batched
 * backend that commits its work on demand) is driven by {@link #waitFor()}, which waits for
 * the dependency itself before waiting for the work.</p>
 *
 * <p>This is what lets a backend whose own ordering mechanism cannot express a
 * dependency from elsewhere (an OpenCL command queue given a completion that is not
 * an OpenCL event, for example) honour that dependency without a host wait on the
 * submitting thread.</p>
 */
public class DeferredSemaphore implements Semaphore {
	/** The completion the work must follow. */
	private final Semaphore dependsOn;

	/** Starts the work and returns its completion. */
	private final Supplier<Semaphore> work;

	/** Whether the work has been started, so that it starts exactly once. */
	private final AtomicBoolean starting;

	/** The semaphore of the started work, available once it has been started. */
	private final CompletableFuture<Semaphore> started;

	/**
	 * Starts {@code work} once {@code dependsOn} has settled, without waiting for it here.
	 * Obtained through {@link Semaphore#then(Supplier)}.
	 *
	 * @param dependsOn the completion the work must follow
	 * @param work      starts the work and returns its completion, or {@code null} if it
	 *                  has already finished
	 */
	DeferredSemaphore(Semaphore dependsOn, Supplier<Semaphore> work) {
		this.dependsOn = dependsOn;
		this.work = work;
		this.starting = new AtomicBoolean();
		this.started = new CompletableFuture<>();

		dependsOn.whenSettled(() -> CALLBACK_EXECUTOR.execute(this::start));
	}

	/**
	 * Starts the work, now that the dependency has settled, unless it failed. Runs once.
	 */
	private void start() {
		if (!starting.compareAndSet(false, true)) return;

		try {
			dependsOn.waitFor();
			started.complete(work.get());
		} catch (Throwable t) {
			started.completeExceptionally(t);
		}
	}

	/**
	 * Waits for the dependency, then for the work to start, and then for its completion.
	 *
	 * @throws RuntimeException if the dependency or the work failed
	 */
	@Override
	public void waitFor() {
		if (!started.isDone()) {
			try {
				dependsOn.waitFor();
			} catch (RuntimeException | Error e) {
				// Reported through the work's start, which does not run after a failed dependency
			}
		}

		Semaphore completion;

		try {
			completion = started.join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			if (cause instanceof Error) throw (Error) cause;
			throw new RuntimeException(cause);
		}

		if (completion != null) completion.waitFor();
	}

	/**
	 * Runs the callback once the work has settled: once the semaphore it started has settled,
	 * or at once if it finished synchronously, failed, or never ran after a failed dependency.
	 * No thread is occupied while waiting.
	 *
	 * @param r the callback to run on settlement
	 */
	@Override
	public void whenSettled(Runnable r) {
		started.whenComplete((completion, failure) -> {
			if (failure != null || completion == null) {
				r.run();
			} else {
				completion.whenSettled(r);
			}
		});
	}
}
