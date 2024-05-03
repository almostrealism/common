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
import io.almostrealism.uml.ThreadLocalSuppliedValue;

import java.text.DecimalFormat;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public abstract class MetricBase implements Named, ConsoleFeatures {
	public static long startingTime = Instant.now().getEpochSecond();

	public static final ThreadLocalSuppliedValue<DecimalFormat> format =
			new ThreadLocalSuppliedValue<>(() -> new DecimalFormat("##0.00#"));

	/** 10 minutes */
	public static long intervalRate = 600;

	private final String name;
	private Console console;

	protected Map<String, Double> entries;
	protected Map<String, Integer> counts;
	protected Map<Long, Double> intervalTotals;
	protected Map<Long, Integer> intervalCounts;

	private long count = 0;
	private double total = 0.0;

	public MetricBase(String name) {
		this.name = name;
		this.entries = Collections.synchronizedMap(new HashMap<>());
		this.counts = Collections.synchronizedMap(new HashMap<>());
		this.intervalTotals = Collections.synchronizedMap(new HashMap<>());
		this.intervalCounts = Collections.synchronizedMap(new HashMap<>());
	}

	@Override
	public String getName() { return name; }
	public long getCount() { return count; }
	public double getTotal() { return total; }

	public void setConsole(Console console) { this.console = console; }

	public void addEntry(double value) {
		addEntry(null, value);
	}

	public <T> void addEntry(T entry, double value) {
		long interval = getCurrentInterval();

		String name = entry instanceof String ? (String) entry : Named.nameOf(entry);
		entries.merge(name, value, Double::sum);
		counts.merge(name, 1, Integer::sum);

		total += value;
		count++;

		intervalTotals.merge(interval, value, Double::sum);
		intervalCounts.merge(interval, 1, Integer::sum);
	}

	public void setEntries(Map<String, Double> entries) {
		this.entries = entries;
		this.total = entries.values().stream().mapToDouble(Double::doubleValue).sum();
	}

	public Map<String, Double> getEntries() { return entries; }

	public void setCounts(Map<String, Integer> counts) {
		this.counts = counts;
		this.count = counts.values().stream().mapToInt(Integer::intValue).sum();
	}

	public Map<String, Integer> getCounts() { return counts; }

	public Map<Long, Double> getIntervalAverages() {
		Map<Long, Double> averages = new HashMap<>();
		intervalTotals.forEach((interval, total) -> {
			int intervalCount = intervalCounts.getOrDefault(interval, 0);
			averages.put(interval, intervalCount == 0 ? 0 : total / intervalCount);
		});
		return averages;
	}

	protected long getCurrentInterval() {
		long currentTime = Instant.now().getEpochSecond() - startingTime;
		return currentTime / intervalRate;
	}

	public void print() {
		getEntries().forEach((k, v) ->
				log(k + " - " + v.longValue()));
	}

	@Override
	public Console console() { return console; }

	public void clear() {
		count = 0;
		total = 0.0;
		entries.clear();
		counts.clear();
		intervalTotals.clear();
		intervalCounts.clear();
	}
}
