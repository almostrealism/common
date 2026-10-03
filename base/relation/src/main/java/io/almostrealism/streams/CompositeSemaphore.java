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

/**
 * The completion of a group of semaphores, which completes once every member has.
 *
 * <p>{@link #waitFor()} waits for each member in turn, so a member that only completes when
 * someone waits for it (a batched backend that commits its work on demand) is driven to
 * completion. Every member is waited for even after one has failed, so nothing is left in
 * flight; the first failure is then rethrown.</p>
 *
 * <p>{@link #whenSettled(Runnable)} does not wait for anything: each member reports its own
 * settlement, and the callback runs when the last of them has. No thread is occupied while
 * the members are pending.</p>
 */
public class CompositeSemaphore implements Semaphore {
	/** The members, all of which must complete. */
	private final List<Semaphore> members;

	/** Counts the members still unsettled. */
	private final LatchState settlement;

	/**
	 * Creates the completion of the given members.
	 *
	 * @param members the semaphores to combine; none may be {@code null}
	 */
	public CompositeSemaphore(List<Semaphore> members) {
		this(members, new LatchState(members.size()));
		members.forEach(member -> member.whenSettled(settlement::countDown));
	}

	/**
	 * Creates a view of an existing composite, sharing its members and settlement, so that a
	 * subclass can attribute the same completion differently.
	 *
	 * @param members    the members of the existing composite
	 * @param settlement the settlement state of the existing composite
	 */
	protected CompositeSemaphore(List<Semaphore> members, LatchState settlement) {
		this.members = List.copyOf(members);
		this.settlement = settlement;
	}

	/**
	 * Returns the members of this composite.
	 *
	 * @return the members, in the order given
	 */
	protected List<Semaphore> getMembers() { return members; }

	/**
	 * Returns the settlement state shared by every view of this composite.
	 *
	 * @return the settlement state
	 */
	protected LatchState getSettlement() { return settlement; }

	/**
	 * Waits for every member, rethrowing the first failure once all of them have been waited for.
	 */
	@Override
	public void waitFor() {
		Throwable failure = null;

		for (Semaphore member : members) {
			try {
				member.waitFor();
			} catch (RuntimeException | Error e) {
				if (failure == null) {
					failure = e;
				} else if (failure != e) {
					failure.addSuppressed(e);
				}
			}
		}

		if (failure instanceof RuntimeException) throw (RuntimeException) failure;
		if (failure instanceof Error) throw (Error) failure;
	}

	/**
	 * Runs the callback once every member has settled, whether or not any failed.
	 *
	 * @param r the callback to run on settlement
	 */
	@Override
	public void whenSettled(Runnable r) { settlement.whenSettled(r); }
}
