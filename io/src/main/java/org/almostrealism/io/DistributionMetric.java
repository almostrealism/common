/*
 * Copyright 2025 Michael Murray
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

import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * A metric for tracking distributions of numeric values across named categories.
 *
 * <p>DistributionMetric collects numeric values, optionally grouped by keys, and provides
 * summary statistics including totals, counts, and percentage breakdowns. Values are
 * automatically scaled using a configurable scale factor for display.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a distribution metric for memory usage (scale: bytes to megabytes)
 * DistributionMetric memory = Console.root().distribution("memory", 1e6);
 *
 * // Record values by category
 * memory.addEntry("heap", heapUsed);
 * memory.addEntry("offheap", offHeapUsed);
 * memory.addEntry("native", nativeUsed);
 *
 * // Print summary
 * Console.root().println(memory.summary());
 * // Output: memory - 150 total:
 * //   heap: 45 | 30%
 * //   offheap: 75 | 50%
 * //   native: 30 | 20%
 * }</pre>
 *
 * <h2>Thresholds</h2>
 * <p>A threshold can be set to throw an exception if any value exceeds it,
 * useful for detecting anomalies:</p>
 * <pre>{@code
 * metric.setThreshold(10.0);  // Throw if any value > 10.0 (after scaling)
 * }</pre>
 *
 * @see MetricBase
 * @see TimingMetric
 * @see Console#distribution(String)
 */
public class DistributionMetric extends MetricBase {
	/**
	 * Maximum number of entries to include in a summary output.
	 * Entries are sorted by value descending, and only the top entries are shown.
	 */
	public static int MAX_SUMMARY_ENTRIES = 30;

	public static double MIN_SUMMARY_PERCENTAGE = 0.01;

	private double scale;
	private double threshold;

	/**
	 * Creates a distribution metric with the specified name and scale.
	 *
	 * @param name the metric name, used in summary output
	 * @param scale the scale factor to divide raw values by (e.g., 1e9 to convert nanoseconds to seconds)
	 */
	public DistributionMetric(String name, double scale) {
		super(name);
		this.scale = scale;
	}

	/**
	 * Returns the scale factor used for value conversion.
	 *
	 * @return the scale factor
	 */
	public double getScale() {
		return scale;
	}

	/**
	 * Returns the threshold value. Values exceeding this threshold (after scaling)
	 * will cause a RuntimeException to be thrown.
	 *
	 * @return the threshold, or 0 if no threshold is set
	 */
	public double getThreshold() { return threshold; }

	/**
	 * Sets the threshold for anomaly detection. If any value (after scaling)
	 * exceeds this threshold, a RuntimeException is thrown.
	 *
	 * @param threshold the threshold value, or 0 to disable threshold checking
	 */
	public void setThreshold(double threshold) { this.threshold = threshold; }

	/**
	 * Adds an unnamed entry with the specified value.
	 * The value is divided by the scale factor before storage.
	 *
	 * @param value the raw value to add
	 */
	public void addEntry(long value) {
		addEntry(null, value);
	}

	/**
	 * Adds a named entry with the specified value.
	 * The value is divided by the scale factor before storage.
	 *
	 * @param <T> the entry type (must be a String or implement Named)
	 * @param entry the entry key, or null for an unnamed entry
	 * @param value the raw value to add
	 * @throws RuntimeException if the scaled value exceeds the threshold
	 */
	public <T> void addEntry(T entry, long value) {
		double v = value / scale;

		if (threshold > 0 && v > threshold) {
			throw new RuntimeException(String.valueOf(v));
		}

		addEntry(entry, value / scale);
	}

	/**
	 * Merges all entries from another distribution metric into this one.
	 * Both metrics must have the same scale factor.
	 *
	 * @param metric the metric to merge from
	 * @throws IllegalArgumentException if the metrics have different scales
	 */
	public void addAll(DistributionMetric metric) {
		if (getScale() != metric.getScale()) {
			throw new IllegalArgumentException();
		}

		metric.getEntries().forEach(this::addEntry);
	}

	/**
	 * Prints a summary of this metric to the console.
	 */
	@Override
	public void print() { log(summary()); }

	/**
	 * Returns a summary string using the metric's name.
	 *
	 * @return the summary string
	 */
	public String summary() { return summary(getName()); }

	/**
	 * Returns a summary string using the specified display name.
	 *
	 * @param displayName the name to display in the summary
	 * @return the summary string
	 */
	public String summary(String displayName) {
		return summary(displayName, s -> s);
	}

	public Stream<Map.Entry<String, Double>> entries(boolean summary) {
		double all = getTotal();

		Stream<Map.Entry<String, Double>> s = getEntries().entrySet().stream()
				.sorted(Map.Entry.<String, Double>comparingByValue().reversed());
		if (summary) {
			s = s.filter(entry -> (entry.getValue() / all) > MIN_SUMMARY_PERCENTAGE)
					.limit(MAX_SUMMARY_ENTRIES);
		}

		return s;
	}

	/**
	 * Returns a formatted summary string showing totals and percentage breakdown by entry.
	 * Entries are sorted by value in descending order.
	 *
	 * @param displayName the name to display in the summary
	 * @param keyFormatter a function to format entry keys for display
	 * @return the formatted summary string
	 */
	public String summary(String displayName, UnaryOperator<String> keyFormatter) {
		StringBuilder builder = new StringBuilder();

		double all = getTotal();

		builder.append(displayName + " - " + (long) all + " total");

		if (getEntries().isEmpty()) return builder.toString();

		builder.append(":\n");

		String form = "\t%s: %d | %d%%\n";

		entries(true).forEachOrdered(entry -> {
			builder.append(String.format(form, entry.getKey(), getCounts().get(entry.getKey()),
							(int) (100 * entry.getValue() / all)));
		});

		return builder.toString();
	}
}
