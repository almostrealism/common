/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.uml.Named;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TimingMetric implements Named {
	public static long startingTime = Instant.now().getEpochSecond();

	/** 10 minutes */
	public static long intervalRate = 600;

	private final String name;
	private double totalSeconds;
	private long count;
	private Map<String, Double> entries;
	private Map<String, Integer> counts;
	private Map<Long, Double> intervalTotals;
	private Map<Long, Integer> intervalCounts;

	public TimingMetric(String name) {
		this.name = name;
		this.entries = Collections.synchronizedMap(new HashMap<>());
		this.counts = Collections.synchronizedMap(new HashMap<>());
		this.intervalTotals = Collections.synchronizedMap(new HashMap<>());
		this.intervalCounts = Collections.synchronizedMap(new HashMap<>());
	}

	public void addEntry(long nanos) {
		addEntry(null, nanos);
	}

	public <T> void addEntry(T entry, long nanos) {
		long interval = getCurrentInterval();
		double seconds = nanos / 1e9;

		if (entry instanceof String) {
			entries.merge((String) entry, seconds, Double::sum);
			counts.merge((String) entry, 1, Integer::sum);
		} else if (entry != null) {
			String className = entry.getClass().getName();
			entries.merge(className, seconds, Double::sum);
			counts.merge(className, 1, Integer::sum);
		}

		totalSeconds += seconds;
		count++;

		intervalTotals.merge(interval, seconds, Double::sum);
		intervalCounts.merge(interval, 1, Integer::sum);
	}

	protected long getCurrentInterval() {
		long currentTime = Instant.now().getEpochSecond() - startingTime;
		return currentTime / intervalRate;
	}

	@Override
	public String getName() { return name; }
	public double getTotalSeconds() { return totalSeconds; }
	public long getCount() { return count; }

	public Map<String, Double> getEntries() { return entries; }
	public Map<String, Integer> getCounts() { return counts; }

	public Map<Long, Double> getIntervalAverages() {
		Map<Long, Double> averages = new HashMap<>();
		intervalTotals.forEach((interval, total) -> {
			int intervalCount = intervalCounts.getOrDefault(interval, 0);
			averages.put(interval, intervalCount == 0 ? 0 : total / intervalCount);
		});
		return averages;
	}

	public void clear() {
		totalSeconds = 0;
		count = 0;
		entries.clear();
		counts.clear();
		intervalTotals.clear();
		intervalCounts.clear();
	}
}
