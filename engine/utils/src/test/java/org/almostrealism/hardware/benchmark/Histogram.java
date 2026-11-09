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

package org.almostrealism.hardware.benchmark;

import java.util.Map;
import java.util.TreeMap;

/** A count of how often each integer value was observed, in ascending value order. */
public class Histogram {
	/** The number of observations of each value. */
	private final TreeMap<Integer, Integer> counts = new TreeMap<>();

	/**
	 * Records one observation of a value.
	 *
	 * @param value the observed value
	 */
	public void add(int value) { counts.merge(value, 1, Integer::sum); }

	/** Returns the number of observations. */
	public int size() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }

	/** Returns the mean of the observed values, or 0 when there are none. */
	public double mean() {
		long total = 0;
		for (Map.Entry<Integer, Integer> e : counts.entrySet()) total += (long) e.getKey() * e.getValue();
		int size = size();
		return size == 0 ? 0.0 : total / (double) size;
	}

	/** Returns the largest observed value, or 0 when there are none. */
	public int max() { return counts.isEmpty() ? 0 : counts.lastKey(); }

	/**
	 * Returns the number of observations of values at least {@code threshold}.
	 *
	 * @param threshold the smallest value counted
	 * @return the number of observations
	 */
	public int atLeast(int threshold) {
		return counts.tailMap(threshold, true).values().stream().mapToInt(Integer::intValue).sum();
	}

	/** Returns the counts as a JSON object keyed by value. */
	public String toJson() {
		StringBuilder b = new StringBuilder("{");
		counts.forEach((k, v) -> b.append(b.length() > 1 ? ", " : "").append('"').append(k).append("\": ").append(v));
		return b.append('}').toString();
	}
}
