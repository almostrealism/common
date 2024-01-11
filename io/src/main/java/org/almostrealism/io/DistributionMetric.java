/*
 * Copyright 2024 Michael Murray
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

public class DistributionMetric extends MetricBase {
	private double scale;
	private double threshold;

	public DistributionMetric(String name, double scale) {
		super(name);
		this.scale = scale;
	}

	public double getThreshold() { return threshold; }

	public void setThreshold(double threshold) { this.threshold = threshold; }

	public void addEntry(long value) {
		addEntry(null, value);
	}

	public <T> void addEntry(T entry, long value) {
		double v = value / scale;

		if (threshold > 0 && v > threshold) {
			throw new RuntimeException();
		}

		addEntry(entry, value / scale);
	}

	@Override
	public void print() { log(summary()); }

	public String summary() { return summary(getName()); }

	public String summary(String displayName) {
		StringBuilder builder = new StringBuilder();

		double all = getTotal();

		builder.append(displayName + " - " + (long) all + " total");

		if (getEntries().isEmpty()) return builder.toString();

		builder.append(":\n");

		String form = "\t%s: %d | %d%%\n";

		getEntries().entrySet().stream()
				.sorted(Comparator.comparing((Map.Entry<String, Double> ent) -> ent.getValue()).reversed())
				.forEachOrdered(entry -> {
					builder.append(String.format(form, entry.getKey(), getCounts().get(entry.getKey()),
							(int) (100 * entry.getValue() / all)));
				});

		return builder.toString();
	}
}
