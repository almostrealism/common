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

package org.almostrealism.hardware.mem;

/**
 * What a {@link HardwareMemoryProvider}'s deferred-release sweep does with one release it is
 * holding back.
 *
 * @see HardwareMemoryProvider#deferredReleaseTimeoutMs
 */
public enum SweepAction {
	/** Keep the release held back unchanged; the memory is legitimately in use within its window. */
	HOLD,
	/** Keep the release held back and restart its timeout at a scheduling-lease handoff. */
	RESET,
	/** Release the memory now. */
	RELEASE;

	/**
	 * Decides what to do with one held-back release, from the reservations on its memory and
	 * the clock.
	 *
	 * <p>While a {@linkplain ReservationState#isLeased() scheduling lease} covers the memory the
	 * release is {@link #RESET held with its timeout restarted}, because a lease may legitimately
	 * outlast the backstop and, once it hands off to an execution guard, that guard must receive
	 * its own full window rather than one already consumed by the scheduling wait. With no lease,
	 * memory a kernel is executing against is {@link #HOLD held} until the timeout elapses, after
	 * which it is {@link #RELEASE released} as a backstop against a count that never came back;
	 * memory nothing reserves is released at once.</p>
	 *
	 * @param state      the reservations on the memory, read at a single moment
	 * @param deferredAt when the release was last (re)stamped
	 * @param now        the current time
	 * @param timeoutMs  how long a release may be held back before the backstop fires
	 * @return the action to take
	 */
	public static SweepAction forRelease(ReservationState state, long deferredAt, long now, long timeoutMs) {
		if (state.isLeased()) return RESET;

		boolean expired = now - deferredAt >= timeoutMs;
		if (!expired && state.isExecuting()) return HOLD;
		return RELEASE;
	}
}
