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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * A synchronization primitive used to coordinate completion of asynchronous hardware
 * executions with their waiting callers.
 *
 * <p>A {@link Semaphore} is the completion handle a provider returns for a dispatch: a
 * caller (or a dependent operation) can wait on it, or register a callback to run once
 * the guarded work has finished. It lives alongside {@link StreamingEvaluable} so that a
 * request may be ordered after a prior completion without any dependency on the
 * operation-metadata model.</p>
 *
 * <p>Attribution of a completion to the {@code OperationMetadata} that requested it &mdash;
 * and the metadata-aware merge that builds on it &mdash; live on the
 * {@code io.almostrealism.concurrent.OperationSemaphore} sub-interface, for callers that
 * have metadata to attribute. When no metadata is relevant, {@link #all(List)} provides the
 * same merge with an unattributed completion.</p>
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
	 * Blocks the calling thread until the guarded operation has completed.
	 */
	void waitFor();

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
	 * Returns a {@link Semaphore} that completes once every one of the given semaphores has
	 * completed &mdash; the merge primitive for an operation that depends on several prior
	 * completions (multiple asynchronously evaluated arguments, a group of copies, a join
	 * across parallel work), for the case where no requester metadata is relevant.
	 *
	 * <p>Null entries (fully synchronous work that published no completion handle) are
	 * ignored. When nothing remains, {@code null} is returned &mdash; everything already
	 * completed. A single remaining semaphore is returned directly, so the composite costs
	 * nothing in the common one-dependency case.</p>
	 *
	 * @param semaphores the completions to merge; may contain nulls
	 * @return a semaphore completing after all of the given semaphores, or {@code null}
	 *         when there is nothing to wait for
	 */
	static Semaphore all(List<Semaphore> semaphores) {
		return all(semaphores, LatchSemaphore::new);
	}

	/**
	 * Returns a {@link Semaphore} that completes once every one of the given semaphores has
	 * completed, constructing the composite from the given combiner. The combiner produces a
	 * {@link LatchSemaphore} counting down once per merged completion; a subclass (for
	 * example a metadata-bearing one) may be supplied so the composite carries additional
	 * attribution.
	 *
	 * <p>Null entries are ignored; an empty selection yields {@code null} and a single
	 * remaining semaphore is returned directly, so the combiner is only invoked when there
	 * is genuinely more than one completion to merge.</p>
	 *
	 * @param semaphores the completions to merge; may contain nulls
	 * @param combiner   produces the composite latch for a given number of members
	 * @return a semaphore completing after all of the given semaphores, or {@code null}
	 *         when there is nothing to wait for
	 */
	static Semaphore all(List<Semaphore> semaphores, IntFunction<? extends LatchSemaphore> combiner) {
		List<Semaphore> pending = semaphores == null ? List.of() :
				semaphores.stream().filter(Objects::nonNull).collect(Collectors.toList());

		if (pending.isEmpty()) return null;
		if (pending.size() == 1) return pending.get(0);

		LatchSemaphore combined = combiner.apply(pending.size());
		pending.forEach(s -> s.onComplete(combined::countDown));
		return combined;
	}
}
