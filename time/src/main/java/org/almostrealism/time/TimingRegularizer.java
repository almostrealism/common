/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.time;

import java.util.LinkedList;
import java.util.Queue;

public class TimingRegularizer {
	private final long standardDuration;
	private final Queue<Long> recentDurations;
	private final int maxRecentDurations = 3;

	/**
	 * Initializes the {@link TimingRegularizer} with the standard duration.
	 *
	 * @param standardDuration the standard duration in nanoseconds
	 */
	public TimingRegularizer(long standardDuration) {
		this.standardDuration = standardDuration;
		this.recentDurations = new LinkedList<>();
	}

	/**
	 * Adds a measured duration to the record.
	 *
	 * @param measuredDuration the duration of the external process in nanoseconds
	 */
	public void addMeasuredDuration(long measuredDuration) {
		if (recentDurations.size() == maxRecentDurations) {
			recentDurations.poll(); // Remove the oldest duration
		}
		recentDurations.add(measuredDuration);
	}

	public long getAverageDuration() {
		if (recentDurations.isEmpty()) {
			// No measurements yet, assume the midpoint
			// between zero and the standard
			return standardDuration / 2;
		}

		// Calculate the average of recent durations
		long sum = 0;
		for (long duration : recentDurations) {
			sum += duration;
		}

		return sum / recentDurations.size();
	}

	/**
	 * Calculates the difference between the standard duration and the average
	 * of the 3 most recent measured durations.
	 *
	 * @return the difference in nanoseconds
	 */
	public long getTimingDifference() {
		return standardDuration - getAverageDuration();
	}
}
