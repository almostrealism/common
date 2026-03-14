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
import org.almostrealism.lifecycle.ThreadLocalSuppliedValue;

import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Abstract base class for metric collection and tracking.
 *
 * <p>MetricBase provides the foundation for collecting numeric metrics across named categories.
 * It supports:</p>
 * <ul>
 *   <li>Aggregation of values by named entries</li>
 *   <li>Running totals and counts</li>
 *   <li>Time-based interval tracking for periodic reporting</li>
 *   <li>Listeners for interval-based callbacks</li>
 * </ul>
 *
 * <h2>Interval Tracking</h2>
 * <p>Metrics are automatically grouped into time intervals (default: 10 minutes).
 * Interval listeners are notified when an interval completes, enabling periodic
 * reporting or aggregation.</p>
 *
 * <pre>{@code
 * TimingMetric metric = Console.root().timing("myOperation");
 * metric.addIntervalListener((total, count) -> {
 *     System.out.println("Interval average: " + (total / count) + " seconds");
 * });
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All internal maps are synchronized for thread-safe access. However, compound
 * operations (read-modify-write) should be externally synchronized if needed.</p>
 *
 * @see DistributionMetric
 * @see TimingMetric
 * @see Console#timing(String)
 * @see Console#distribution(String)
 */
public abstract class MetricBase implements Named, ConsoleFeatures {
	/**
	 * The epoch second when metrics started being collected (application startup time).
	 * Used as the reference point for interval calculations.
	 */
	public static long startingTime = Instant.now().getEpochSecond();

	/**
	 * Thread-local decimal formatter for consistent number formatting.
	 * Uses pattern "##0.00#" (minimum 2 decimal places, maximum 3).
	 */
	public static final ThreadLocalSuppliedValue<DecimalFormat> format =
			new ThreadLocalSuppliedValue<>(() -> new DecimalFormat("##0.00#"));

	/**
	 * The duration of each time interval in seconds.
	 * Default is 600 seconds (10 minutes).
	 */
	public static long intervalRate = 600;

	private final String name;
	private Console console;

	/** Map of entry names to their accumulated values. */
	protected Map<String, Double> entries;
	/** Map of entry names to their occurrence counts. */
	protected Map<String, Integer> counts;
	/** Map of interval indices to their accumulated totals. */
	protected Map<Long, Double> intervalTotals;
	/** Map of interval indices to their occurrence counts. */
	protected Map<Long, Integer> intervalCounts;

	private List<BiConsumer<Double, Integer>> intervalListeners;
	private long lastReportedInterval;

	private long count = 0;
	private double total = 0.0;

	/**
	 * Creates a metric with the specified name.
	 *
	 * @param name the metric name, used for display and identification
	 */
	public MetricBase(String name) {
		this.name = name;
		this.entries = Collections.synchronizedMap(new HashMap<>());
		this.counts = Collections.synchronizedMap(new HashMap<>());
		this.intervalTotals = Collections.synchronizedMap(new HashMap<>());
		this.intervalCounts = Collections.synchronizedMap(new HashMap<>());

		this.intervalListeners = new ArrayList<>();
		this.lastReportedInterval = -1;
	}

	/**
	 * Returns the metric name.
	 *
	 * @return the name
	 */
	@Override
	public String getName() { return name; }

	/**
	 * Returns the total number of entries added to this metric.
	 *
	 * @return the entry count
	 */
	public long getCount() { return count; }

	/**
	 * Returns the sum of all values added to this metric.
	 *
	 * @return the total value
	 */
	public double getTotal() { return total; }

	/**
	 * Sets the console to use for logging output.
	 *
	 * @param console the console instance
	 */
	public void setConsole(Console console) { this.console = console; }

	/**
	 * Adds a listener that will be notified when a time interval completes.
	 * The listener receives the interval's total value and count.
	 *
	 * @param listener the listener to add
	 */
	public void addIntervalListener(BiConsumer<Double, Integer> listener) {
		intervalListeners.add(listener);
	}

	/**
	 * Removes a previously registered interval listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeIntervalListener(BiConsumer<Double, Integer> listener) {
		intervalListeners.remove(listener);
	}

	/**
	 * Adds an unnamed entry with the specified value.
	 *
	 * @param value the value to add
	 */
	public void addEntry(double value) {
		addEntry(null, value);
	}

	/**
	 * Adds a named entry with the specified value.
	 * The entry is aggregated with any existing entry of the same name.
	 *
	 * @param <T> the entry type (must be a String or implement Named)
	 * @param entry the entry key, or null for an unnamed entry
	 * @param value the value to add
	 */
	public <T> void addEntry(T entry, double value) {
		long interval = getCurrentInterval();

		String name = entry instanceof String ? (String) entry : Named.nameOf(entry);
		entries.merge(name, value, Double::sum);
		counts.merge(name, 1, Integer::sum);

		total += value;
		count++;

		intervalTotals.merge(interval, value, Double::sum);
		intervalCounts.merge(interval, 1, Integer::sum);

		if (interval == lastReportedInterval + 2) {
			reportInterval(interval - 1);
		}
	}

	/**
	 * Replaces the entries map with the specified map.
	 * The total is recalculated from the new entries.
	 *
	 * @param entries the new entries map
	 */
	public void setEntries(Map<String, Double> entries) {
		this.entries = entries;
		this.total = entries.values().stream().mapToDouble(Double::doubleValue).sum();
	}

	/**
	 * Returns the map of entry names to accumulated values.
	 *
	 * @return the entries map
	 */
	public Map<String, Double> getEntries() { return entries; }

	/**
	 * Replaces the counts map with the specified map.
	 * The total count is recalculated from the new counts.
	 *
	 * @param counts the new counts map
	 */
	public void setCounts(Map<String, Integer> counts) {
		this.counts = counts;
		this.count = counts.values().stream().mapToInt(Integer::intValue).sum();
	}

	/**
	 * Returns the map of entry names to occurrence counts.
	 *
	 * @return the counts map
	 */
	public Map<String, Integer> getCounts() { return counts; }

	/**
	 * Computes the average value for each time interval.
	 *
	 * @return a map of interval indices to average values
	 */
	public Map<Long, Double> getIntervalAverages() {
		Map<Long, Double> averages = new HashMap<>();
		intervalTotals.forEach((interval, total) -> {
			int intervalCount = intervalCounts.getOrDefault(interval, 0);
			averages.put(interval, intervalCount == 0 ? 0 : total / intervalCount);
		});
		return averages;
	}

	/**
	 * Returns the current time interval index based on elapsed time since startup.
	 *
	 * @return the current interval index
	 */
	protected long getCurrentInterval() {
		long currentTime = Instant.now().getEpochSecond() - startingTime;
		return currentTime / intervalRate;
	}

	/**
	 * Reports the specified interval to all registered listeners.
	 *
	 * @param interval the interval index to report
	 */
	protected void reportInterval(long interval) {
		lastReportedInterval = interval;
		if (intervalListeners.isEmpty()) return;

		intervalListeners.forEach(l ->
				l.accept(intervalTotals.get(interval), intervalCounts.get(interval)));
	}

	/**
	 * Prints all entries to the console.
	 */
	public void print() {
		getEntries().forEach((k, v) ->
				log(k + " - " + v.longValue()));
	}

	/**
	 * Returns the console associated with this metric.
	 *
	 * @return the console, or null if not set
	 */
	@Override
	public Console console() { return console; }

	/**
	 * Clears all accumulated data from this metric.
	 * Resets counts, totals, entries, and interval data to initial state.
	 */
	public void clear() {
		count = 0;
		total = 0.0;
		entries.clear();
		counts.clear();
		intervalTotals.clear();
		intervalCounts.clear();
	}
}
