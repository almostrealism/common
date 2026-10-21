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

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * How long one side of a hand-off between two threads keeps polling for the other before it
 * parks.
 *
 * <p>When one thread hands work to another and waits for the result, each side at some point
 * has to wait for the other: the receiving thread for the work to arrive, the handing thread for
 * the work to finish. A side that parks to wait costs a scheduler wake-up once the other side is
 * done, and that wake-up can take longer than a short piece of work itself. A side that polls
 * instead notices at once, but occupies a core while it polls. A hand-off window bounds the
 * polling: each side polls for up to the window, which covers the short gaps between work
 * issued back to back, and parks only once a gap outlasts it, so polling never occupies a core
 * for longer than the window at a time.</p>
 *
 * <p>Polling ({@link #pollUntil(BooleanSupplier)}) only decides how soon a waiting side notices
 * that the state it waits for has arrived; the side still falls back to its ordinary blocking
 * wait whenever polling ends without that state. An interrupt ends polling at once and is left
 * pending, so the blocking wait that follows sees it exactly as it would have without polling.
 * {@link ConfinedExecutor} uses a window for both sides of every task it runs.</p>
 */
public final class HandOffWindow {
	/** A window that never polls: each side of a hand-off parks as soon as it has to wait. */
	public static final HandOffWindow NONE = new HandOffWindow(0, TimeUnit.NANOSECONDS);

	/** How long, in nanoseconds, a waiting side polls before parking; zero never polls. */
	private final long nanos;

	/**
	 * Creates a hand-off window of the given length.
	 *
	 * @param duration how long a waiting side polls before parking, in {@code unit}; zero never
	 *                 polls
	 * @param unit     the unit of {@code duration}
	 * @throws IllegalArgumentException if {@code duration} is negative
	 */
	public HandOffWindow(long duration, TimeUnit unit) {
		if (duration < 0) {
			throw new IllegalArgumentException("The hand-off window cannot be negative: " + duration + " " + unit);
		}

		this.nanos = unit.toNanos(duration);
	}

	/**
	 * Polls {@code condition} until it holds, the calling thread is interrupted, or this window
	 * has elapsed, whichever comes first. The interrupt status is not cleared, so whatever
	 * blocking wait follows observes it as it would have without polling.
	 *
	 * @param condition the state being waited for, cheap enough to evaluate repeatedly
	 * @return true if {@code condition} held before polling stopped; always false for a window
	 *         of zero, which does not evaluate {@code condition} at all
	 */
	public boolean pollUntil(BooleanSupplier condition) {
		if (nanos == 0) return false;

		long deadline = System.nanoTime() + nanos;
		Thread current = Thread.currentThread();

		while (!current.isInterrupted()) {
			if (condition.getAsBoolean()) return true;
			if (System.nanoTime() - deadline >= 0) return false;
			Thread.onSpinWait();
		}

		return false;
	}
}
