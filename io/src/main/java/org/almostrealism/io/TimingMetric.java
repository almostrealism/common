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

import java.util.Comparator;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A metric for measuring and tracking execution times of operations.
 *
 * <p>TimingMetric extends {@link DistributionMetric} to provide convenient
 * methods for timing code execution. Times are measured in nanoseconds but
 * reported in seconds for human readability.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TimingMetric timing = Console.root().timing("myOperation");
 *
 * // Time operations
 * timing.measure("database", () -> queryDatabase());
 * timing.measure("processing", () -> processData());
 * timing.measure("io", () -> writeResults());
 *
 * // Print summary
 * Console.root().println(timing.summary());
 * // Output shows:
 * // myOperation - 5.2 seconds:
 * //   database: 12 [3.1s tot | 258ms avg] 60%
 * //   processing: 5 [1.5s tot | 300ms avg] 29%
 * //   io: 3 [0.6s tot | 200ms avg] 11%
 * }</pre>
 *
 * @see DistributionMetric
 * @see Console#timing(String)
 */
public class TimingMetric extends DistributionMetric {

	/**
	 * Creates an unnamed timing metric.
	 */
	public TimingMetric() {
		super(null, 1e9);
	}

	/**
	 * Creates a timing metric with the specified name.
	 *
	 * @param name the metric name
	 */
	public TimingMetric(String name) {
		super(name, 1e9);
	}

	/**
	 * Measures the execution time of a supplier and returns its result.
	 * The time is recorded under the specified key.
	 *
	 * @param <T> the result type
	 * @param key the key for this measurement
	 * @param supplier the code to time
	 * @return the result from the supplier
	 */
	public <T> T measure(String key, Supplier<T> supplier) {
		long start = System.nanoTime();
		T result = supplier.get();
		addEntry(key, System.nanoTime() - start);
		return result;
	}

	/**
	 * Measures the execution time of a runnable.
	 * The time is recorded under the specified key.
	 *
	 * @param key the key for this measurement
	 * @param runnable the code to time
	 */
	public void measure(String key, Runnable runnable) {
		long start = System.nanoTime();
		runnable.run();
		addEntry(key, System.nanoTime() - start);
	}

	@Override
	public String summary(String displayName, UnaryOperator<String> keyFormatter) {
		StringBuilder builder = new StringBuilder();

		double all = getTotal();
		// getEntries().values().stream().mapToDouble(Double::doubleValue).sum();

		if (all > 90) {
			builder.append(displayName + " - " + format.getValue().format(all / 60.0) + " minutes");
		} else {
			builder.append(displayName + " - " + format.getValue().format(all) + " seconds");
		}

		if (getEntries().isEmpty()) return builder.toString();

		builder.append(":\n");

		String form = "\t%s: %d [%ss tot | %sms avg] %d%%\n";

		entries(true).forEachOrdered(entry -> {
			builder.append(String.format(form, keyFormatter.apply(entry.getKey()), getCounts().get(entry.getKey()),
							format.getValue().format(entry.getValue()),
							format.getValue().format(1000 * entry.getValue() / getCounts().get(entry.getKey())),
							(int) (100 * entry.getValue() / all)));
		});

		return builder.toString();
	}
}
