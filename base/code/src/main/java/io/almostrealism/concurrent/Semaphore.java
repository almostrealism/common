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

import io.almostrealism.profile.OperationMetadata;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A synchronization primitive used to coordinate completion of asynchronous hardware
 * executions with their waiting callers.
 *
 * <p>A semaphore carries an optional {@link OperationMetadata} that identifies the
 * operation waiting on it, enabling diagnostic attribution of blocked threads.</p>
 */
public interface Semaphore {
	/**
	 * Shared executor for {@link #onComplete(Runnable)} callbacks. Each callback occupies
	 * a thread only while it waits for its semaphore and runs, and idle threads are
	 * reclaimed, so registering many callbacks does not accumulate threads the way a
	 * thread-per-callback approach would. Callbacks must not block on work that can only
	 * progress on this same pool's caller (the pool is unbounded, so ordinary waits on
	 * device completions are safe).
	 */
	ExecutorService CALLBACK_EXECUTOR = Executors.newCachedThreadPool(r ->
			new Thread(r, "Semaphore onComplete"));

	/**
	 * Returns the metadata describing the operation that is waiting on this semaphore,
	 * or {@code null} if no requester metadata is available.
	 *
	 * @return the requester metadata, or {@code null}
	 */
	OperationMetadata getRequester();

	/**
	 * Blocks the calling thread until the guarded operation has completed.
	 */
	void waitFor();

	/**
	 * Returns a new semaphore backed by the same synchronization state as this one
	 * but attributed to the given requester.
	 *
	 * @param requester the metadata of the new requester
	 * @return a semaphore with the updated requester
	 */
	Semaphore withRequester(OperationMetadata requester);

	/**
	 * Registers a callback to be invoked on a background thread once the guarded
	 * operation has completed.
	 *
	 * @param r the callback to invoke after {@link #waitFor()} returns
	 */
	default void onComplete(Runnable r) {
		CALLBACK_EXECUTOR.execute(() -> {
			waitFor();
			r.run();
		});
	}

	/**
	 * Returns a {@link Semaphore} that completes once every one of the given semaphores
	 * has completed &mdash; the merge primitive for an operation that depends on several
	 * prior completions (multiple asynchronously evaluated arguments, a group of copies,
	 * a join across parallel work).
	 *
	 * <p>Null entries (fully synchronous work that published no completion handle) are
	 * ignored. When nothing remains, {@code null} is returned &mdash; everything already
	 * completed. A single remaining semaphore is returned directly, so the composite
	 * costs nothing in the common one-dependency case.</p>
	 *
	 * @param requester  the metadata of the operation that will wait on the composite
	 * @param semaphores the completions to merge; may contain nulls
	 * @return a semaphore completing after all of the given semaphores, or {@code null}
	 *         when there is nothing to wait for
	 */
	static Semaphore all(OperationMetadata requester, List<Semaphore> semaphores) {
		List<Semaphore> pending = semaphores == null ? List.of() :
				semaphores.stream().filter(Objects::nonNull).collect(Collectors.toList());

		if (pending.isEmpty()) return null;
		if (pending.size() == 1) return pending.get(0);

		DefaultLatchSemaphore combined = new DefaultLatchSemaphore(requester, pending.size());
		pending.forEach(s -> s.onComplete(combined::countDown));
		return combined;
	}
}
