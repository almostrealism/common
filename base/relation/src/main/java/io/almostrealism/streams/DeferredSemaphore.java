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
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The completion of work that may only start once another {@link Semaphore} has
 * completed, started without holding up the thread that asked for it.
 *
 * <p>{@link Semaphore#then(Supplier)} returns one of these at once. When the dependency
 * completes (see {@link Semaphore#onSettled(Runnable)}, which holds no thread for a
 * dependency that is told of its own settlement), the work runs on
 * {@link Semaphore#CALLBACK_EXECUTOR} and yields the
 * semaphore of whatever it started (or {@code null} if it finished synchronously);
 * {@link #waitFor()} waits for the work to start and then for that semaphore. A
 * failure of the dependency or of the work is not swallowed: it is rethrown from
 * {@link #waitFor()}, and the work does not run after a failed dependency.</p>
 *
 * <p>This is what lets a backend whose own ordering mechanism cannot express a
 * dependency from elsewhere (an OpenCL command queue given a completion that is not
 * an OpenCL event, for example) honour that dependency without a host wait on the
 * submitting thread.</p>
 */
public class DeferredSemaphore implements Semaphore {
	/** The semaphore of the started work, available once it has been started. */
	private final CompletableFuture<Semaphore> started;

	/**
	 * Starts {@code work} once {@code dependsOn} has completed, without waiting for it here.
	 * Obtained through {@link Semaphore#then(Supplier)}.
	 *
	 * @param dependsOn the completion the work must follow
	 * @param work      starts the work and returns its completion, or {@code null} if it
	 *                  has already finished
	 */
	DeferredSemaphore(Semaphore dependsOn, Supplier<Semaphore> work) {
		this.started = new CompletableFuture<>();

		dependsOn.onSettled(() -> {
			try {
				dependsOn.waitFor();
				started.complete(work.get());
			} catch (Throwable t) {
				started.completeExceptionally(t);
			}
		});
	}

	/**
	 * Runs {@code r} once the work has settled, without holding a thread meanwhile: the
	 * callback is attached to the work's start and then to the completion of what it started,
	 * so a chain of deferred work is a chain of registrations. It runs at once, on a callback
	 * thread, for work that has already settled or that finished synchronously.
	 *
	 * @param r the callback to invoke once the work has settled
	 */
	@Override
	public void onSettled(Runnable r) {
		afterStart(r, completion -> completion.onSettled(r));
	}

	/**
	 * Runs {@code r} once the work has settled, as {@link #onSettled(Runnable)} does, but
	 * attached passively to the completion of what the work started (see
	 * {@link Semaphore#whenSettled(Runnable)}), so registering it never drives that completion.
	 *
	 * @param r the callback to invoke once the work has settled
	 */
	@Override
	public void whenSettled(Runnable r) {
		afterStart(r, completion -> completion.whenSettled(r));
	}

	/**
	 * Once the work has been started (or has failed to start), attaches {@code r} to the
	 * completion of what it started with {@code attach}, or runs it on a callback thread when
	 * there is nothing left to settle.
	 *
	 * @param r      the callback to invoke once the work has settled
	 * @param attach registers {@code r} with the completion of the started work
	 */
	private void afterStart(Runnable r, Consumer<Semaphore> attach) {
		started.whenComplete((completion, failure) -> {
			if (completion == null) {
				CALLBACK_EXECUTOR.execute(r);
			} else {
				attach.accept(completion);
			}
		});
	}

	/**
	 * Waits for the work to start and then for its completion.
	 *
	 * <p>The wait for the work to start is interruptible: an interrupt returns at once with
	 * the interrupt status set, as an interrupted wait on a latch does, so a waiter (such as
	 * a composite settling its members) is never pinned by a dependency that has not
	 * completed.</p>
	 *
	 * @throws RuntimeException if the dependency or the work failed
	 */
	@Override
	public void waitFor() {
		Semaphore completion;

		try {
			completion = started.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			if (cause instanceof Error) throw (Error) cause;
			throw new RuntimeException(cause);
		}

		if (completion != null) completion.waitFor();
	}
}
