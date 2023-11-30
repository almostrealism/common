/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.uml.Named;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class OperationProfile implements Named, ConsoleFeatures {
	public static long id = 0;

	private String name;
	private TimingMetric metric;
	private DecimalFormat format;

	public OperationProfile() {
		this("default");
	}

	public OperationProfile(String name) {
		this.name = name;
		this.metric = console().metric(name + "_prof" + id++);
		this.format = new DecimalFormat("##0.00#");
	}

	@Override
	public String getName() { return name; }

	public TimingMetric getMetric() { return metric; }

	public void print() { log(summary()); }

	public String summary() {
		StringBuilder builder = new StringBuilder();

		double all = metric.getEntries().values().stream().mapToDouble(Double::doubleValue).sum();
		builder.append("Operation Profile (" + getName() + " - " + format.format(all) + " seconds):\n");

		String form = "\t%s: %d [%ss tot | %ss avg] %d%%\n";

		metric.getEntries().entrySet().stream()
				.sorted(Comparator.comparing((Map.Entry<String, Double> ent) -> ent.getValue()).reversed())
				.forEachOrdered(entry -> {
					builder.append(String.format(form, entry.getKey(), metric.getCounts().get(entry.getKey()),
							format.format(entry.getValue()),
							format.format(entry.getValue() / metric.getCounts().get(entry.getKey())),
							(int) (100 * entry.getValue() / all)));
		});

		return builder.toString();
	}

	public long recordDuration(Runnable r) {
		long start = System.nanoTime();
		r.run();
		long end = System.nanoTime();

		OperationMetadata metadata = null;
		if (r instanceof OperationInfo) {
			metadata = ((OperationInfo) r).getMetadata();

			if (metadata == null) {
				System.out.println("Warning: " + r.getClass().getSimpleName() + " has no metadata");
			}
		}

		if (metadata == null) {
			metadata = new OperationMetadata(r.getClass().getSimpleName(), r.getClass().getSimpleName());
		}

		recordDuration(metadata, end - start);
		return end - start;
	}

	protected void recordDuration(OperationMetadata metadata, long nanos) {
		String key = metadata.getShortDescription();
		if (key == null) key = "<unknown>";
		metric.addEntry(key, nanos);
	}

	public void clear() { metric.clear(); }

	@Override
	public Console console() { return Computation.console; }
}
