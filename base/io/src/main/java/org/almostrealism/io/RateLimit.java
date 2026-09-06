/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.io;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A sliding-window cap on how often something may happen.
 *
 * <p>Permits up to a fixed number of events per key within a moving time
 * window. Because the window slides rather than resetting on a boundary, a
 * caller that exhausts its budget recovers as the oldest events age out; a
 * lifetime counter, by contrast, goes permanently silent once it fills, which
 * is rarely what a long-running process wants.</p>
 *
 * <p>Reservations are taken up front — {@link #reserve(String)} both asks and
 * consumes — so concurrent callers cannot pass the same check together. An
 * event that subsequently fails still consumes its slot, which is correct when
 * the limit models something that counts attempts rather than successes, such
 * as a carrier's spam protection.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * RateLimit limit = new RateLimit(30, Duration.ofHours(1));
 *
 * if (limit.reserve("michael")) {
 *     send(message);
 * } else {
 *     // over budget; try again once the window slides
 * }
 * }</pre>
 */
public class RateLimit {

	/** The key used by the unkeyed {@link #reserve()} and {@link #remaining()}. */
	private static final String globalKey = "";

	/** Maximum number of events permitted per key within {@link #window}. */
	private final int max;

	/** Width of the sliding window. */
	private final Duration window;

	/** Event times per key, oldest first, trimmed to the window on access. */
	private final Map<String, Deque<Instant>> events = new HashMap<>();

	/**
	 * Creates a limit permitting {@code max} events per key per window.
	 *
	 * @param max    the maximum number of events; values below zero are
	 *               treated as zero, which permits nothing
	 * @param window the width of the sliding window
	 */
	public RateLimit(int max, Duration window) {
		this.max = Math.max(0, max);
		this.window = window;
	}

	/** Returns the maximum number of events permitted per key per window. */
	public int getMax() { return max; }

	/** Returns the width of the sliding window. */
	public Duration getWindow() { return window; }

	/**
	 * Reserves one event against the shared, unkeyed budget.
	 *
	 * @return {@code true} when the event may proceed
	 */
	public boolean reserve() {
		return reserve(globalKey);
	}

	/**
	 * Reserves one event against the given key's budget, consuming a slot
	 * when one is available.
	 *
	 * @param key the budget to draw from; {@code null} draws from the
	 *            unkeyed budget
	 * @return {@code true} when the event may proceed, {@code false} when the
	 *         key has exhausted its budget for the current window
	 */
	public synchronized boolean reserve(String key) {
		Deque<Instant> times = trimmed(key);
		if (times.size() >= max) return false;

		times.addLast(Instant.now());
		return true;
	}

	/**
	 * Returns how many events remain available to the unkeyed budget.
	 *
	 * @return the remaining budget, never negative
	 */
	public int remaining() {
		return remaining(globalKey);
	}

	/**
	 * Returns how many events remain available to the given key.
	 *
	 * @param key the budget to inspect
	 * @return the remaining budget, never negative
	 */
	public synchronized int remaining(String key) {
		return Math.max(0, max - trimmed(key).size());
	}

	/**
	 * Discards every reservation, restoring the full budget to every key.
	 */
	public synchronized void clear() {
		events.clear();
	}

	/**
	 * Returns the given key's event times with everything older than the
	 * window removed, creating the entry if absent.
	 *
	 * <p>Keys whose events have all aged out are dropped, so a limit keyed by
	 * something unbounded — a session identifier, say — does not accumulate
	 * an entry per key seen.</p>
	 *
	 * @param key the budget to trim
	 * @return the live deque of event times for that key
	 */
	private Deque<Instant> trimmed(String key) {
		Instant cutoff = Instant.now().minus(window);

		Iterator<Map.Entry<String, Deque<Instant>>> it = events.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Deque<Instant>> entry = it.next();
			Deque<Instant> times = entry.getValue();

			while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
				times.removeFirst();
			}

			if (times.isEmpty()) it.remove();
		}

		return events.computeIfAbsent(key == null ? globalKey : key,
				k -> new ArrayDeque<>());
	}
}
