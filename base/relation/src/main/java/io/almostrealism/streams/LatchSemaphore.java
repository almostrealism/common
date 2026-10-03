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

/**
 * A {@link Semaphore} implementation backed by a count of outstanding parts, held in a
 * {@link LatchState}.
 *
 * <p>The latch counts down to zero when the guarded operation (or a set of parallel
 * sub-operations) completes. Callers blocked in {@link #waitFor()} are released once the
 * count reaches zero, and settlement callbacks run then without occupying a thread while
 * they wait. A subclass may attach requester attribution (see
 * {@code io.almostrealism.concurrent.DefaultLatchSemaphore}).</p>
 *
 * <p>A merged member that fails is recorded with {@link #fail(Throwable)} and still counts
 * down, so the latch is never pinned by a failure; the first such failure is rethrown from
 * {@link #waitFor()} once the count reaches zero, so a group failure reaches the waiter
 * rather than being lost.</p>
 */
public class LatchSemaphore implements Semaphore {
	/** The completion state, possibly shared with other semaphores attributed differently. */
	private final LatchState state;

	/**
	 * Constructs a semaphore whose {@link #waitFor()} returns after {@code count}
	 * {@link #countDown()} calls.
	 *
	 * @param count the number of {@link #countDown()} calls required before
	 *              {@link #waitFor()} returns
	 */
	public LatchSemaphore(int count) {
		this(new LatchState(count));
	}

	/**
	 * Constructs a semaphore sharing an existing completion state, so that a re-attributed
	 * view counts down, fails and settles together with the semaphore it was derived from.
	 *
	 * @param state the completion state to share
	 */
	protected LatchSemaphore(LatchState state) {
		this.state = state;
	}

	/**
	 * Returns the completion state, so a subclass can share it with a re-attributed view.
	 *
	 * @return the completion state
	 */
	protected LatchState getState() { return state; }

	/**
	 * Decrements the latch count, releasing waiters and running settlement callbacks once it
	 * reaches zero.
	 */
	public void countDown() { state.countDown(); }

	/**
	 * Records a failure observed while merging a member's completion, so it can be rethrown
	 * from {@link #waitFor()} once the latch releases. Only the first failure is retained;
	 * later ones are ignored. A {@code null} argument is a no-op, so this may be called
	 * unconditionally from a settlement callback.
	 *
	 * @param t the failure to record, or {@code null} if the member completed normally
	 */
	public void fail(Throwable t) { state.fail(t); }

	@Override
	public void waitFor() { state.await(); }

	/**
	 * Runs the callback once the count reaches zero, on the thread that counts it down, without
	 * occupying a thread in the meantime.
	 *
	 * @param r the callback to run on settlement
	 */
	@Override
	public void whenSettled(Runnable r) { state.whenSettled(r); }
}
