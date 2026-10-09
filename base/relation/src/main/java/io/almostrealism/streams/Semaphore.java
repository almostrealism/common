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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.function.Supplier;

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
	 * Starts {@code work} once this has completed, without waiting for it on the calling
	 * thread, and returns the completion of that work. Unlike {@link #onComplete(Runnable)},
	 * a failure of this semaphore or of the work is not lost: the work does not run after a
	 * failure here, and either failure is rethrown by the returned semaphore's
	 * {@link #waitFor()}.
	 *
	 * @param work starts the work and returns its completion, or {@code null} if it has
	 *             already finished
	 * @return the completion of the work
	 */
	default Semaphore then(Supplier<Semaphore> work) {
		return new DeferredSemaphore(this, work);
	}

	/**
	 * Registers a callback to run once the guarded operation has completed, without
	 * driving the operation toward completion. The default delegates to
	 * {@link #onComplete(Runnable)}, whose callback thread actively waits; an
	 * implementation whose {@link #waitFor()} has side effects beyond blocking — such
	 * as forcing a command-buffer commit that breaks dispatch batching — overrides
	 * this to attach the callback passively instead. The callback may consequently
	 * run arbitrarily late, only when completion is reached for other reasons; use
	 * {@link #onComplete(Runnable)} when the callback must run promptly.
	 *
	 * @param r the callback to invoke once the guarded operation has completed
	 */
	default void whenComplete(Runnable r) {
		onComplete(r);
	}

	/**
	 * Registers a callback to be invoked on a background thread once the guarded
	 * operation has either completed or failed. Unlike {@link #onComplete(Runnable)},
	 * the callback runs on the failure path as well, which is what releasing a resource
	 * held for the operation requires. The failure itself is not handled here: it is
	 * reported to whoever waits on this semaphore.
	 *
	 * @param r the callback to invoke once {@link #waitFor()} returns or throws
	 */
	default void whenSettled(Runnable r) {
		CALLBACK_EXECUTOR.execute(() -> {
			try {
				waitFor();
			} catch (RuntimeException e) {
				// Reported to the operation's own waiters; this callback only observes settlement
			} finally {
				r.run();
			}
		});
	}

	/**
	 * Returns a single completion that is equivalent to waiting for both this and
	 * {@code other}, when the provider behind this semaphore can express that without a
	 * host-side composite, or {@code null} when it cannot.
	 *
	 * <p>{@link #all(List, IntFunction)} merges completions this way before it builds a
	 * composite. A composite waits for each member on a callback thread, which, for a
	 * provider whose {@link #waitFor()} has side effects (forcing a command-buffer commit,
	 * for example), defeats the batching that chaining the completions was meant to keep;
	 * and a dispatch that depends on the composite cannot chain on it inside the provider.
	 * A provider that orders its own work (a single in-order device queue, for example) can
	 * often represent two of its completions by the later of them, which a dependent
	 * dispatch can chain on directly. The default merges nothing.</p>
	 *
	 * @param other another completion, never {@code null}
	 * @return a completion that completes only once both have, or {@code null} if this
	 *         semaphore cannot merge with {@code other}
	 */
	default Semaphore merge(Semaphore other) {
		return null;
	}

	/**
	 * Runs {@code r} once {@code dependsOn} has completed, or immediately on the calling
	 * thread when there is no dependency. This is the non-blocking way for work that cannot
	 * chain a completion into a dispatch (a host evaluation that reads memory a prior
	 * dispatch is still writing, for example) to be ordered after it: the caller never
	 * waits, and the wait happens on the completion's callback thread instead, exactly as
	 * {@link #onComplete(Runnable)} arranges.
	 *
	 * @param dependsOn the completion to order {@code r} after, or {@code null} when there
	 *                  is nothing to wait for
	 * @param r         the work to run
	 */
	static void onComplete(Semaphore dependsOn, Runnable r) {
		if (dependsOn == null) {
			r.run();
		} else {
			dependsOn.onComplete(r);
		}
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
	 * remaining semaphore is returned directly. Members that {@link #merge(Semaphore) merge}
	 * are replaced by their merged completion first, so the combiner is only invoked when
	 * there is genuinely more than one completion left that the providers could not express
	 * as one.</p>
	 *
	 * <p>Every member is settled rather than merely awaited: a member whose {@link #waitFor()}
	 * throws still counts the composite down, so a single failure can never pin the latch and
	 * leave a waiter blocked forever. The first such failure is retained and rethrown from the
	 * composite's {@link #waitFor()}, so a member failure reaches the group's waiter instead of
	 * being swallowed the way {@link #onComplete(Runnable)} would swallow it.</p>
	 *
	 * <p>Each member is waited for on a {@link #CALLBACK_EXECUTOR} thread as soon as the
	 * composite exists, which is what drives a member whose completion has to be requested.
	 * The composite also records its members, so its own {@link #waitFor()} settles them
	 * directly and is released by the last member's completion rather than by the thread that
	 * counts the composite down (see {@link LatchSemaphore#waitFor()}).</p>
	 *
	 * @param semaphores the completions to merge; may contain nulls
	 * @param combiner   produces the composite latch for a given number of members
	 * @return a semaphore completing after all of the given semaphores, or {@code null}
	 *         when there is nothing to wait for
	 */
	static Semaphore all(List<Semaphore> semaphores, IntFunction<? extends LatchSemaphore> combiner) {
		if (semaphores == null) return null;

		// A single pass avoids the stream and intermediate list this per-dispatch merge would
		// otherwise allocate for its common zero- or one-member outcome.
		int count = 0;
		Semaphore single = null;
		for (int i = 0; i < semaphores.size(); i++) {
			Semaphore s = semaphores.get(i);
			if (s != null) {
				count++;
				single = s;
			}
		}

		if (count == 0) return null;
		if (count == 1) return single;

		List<Semaphore> members = merged(semaphores, count);
		if (members.size() == 1) return members.get(0);

		LatchSemaphore combined = combiner.apply(members.size());
		combined.compose(members);
		for (Semaphore s : members) {
			CALLBACK_EXECUTOR.execute(() -> {
				try {
					s.waitFor();
				} catch (Throwable t) {
					combined.fail(t);
				} finally {
					combined.countDown();
				}
			});
		}
		return combined;
	}

	/**
	 * Returns the non-null members of {@code semaphores}, with every member that
	 * {@link #merge(Semaphore) merges} with an earlier one folded into it, in either
	 * direction.
	 *
	 * @param semaphores the completions to merge; may contain nulls
	 * @param count      the number of non-null entries, used to size the result
	 * @return the remaining completions, at least one
	 */
	private static List<Semaphore> merged(List<Semaphore> semaphores, int count) {
		List<Semaphore> members = new ArrayList<>(count);

		for (int i = 0; i < semaphores.size(); i++) {
			Semaphore s = semaphores.get(i);
			if (s == null) continue;

			boolean folded = false;
			for (int j = 0; j < members.size() && !folded; j++) {
				Semaphore m = members.get(j).merge(s);
				if (m == null) m = s.merge(members.get(j));

				if (m != null) {
					members.set(j, m);
					folded = true;
				}
			}

			if (!folded) members.add(s);
		}

		return members;
	}
}
