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

import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Map;

public class TimingMetric extends DistributionMetric {
	private DecimalFormat format;

	public TimingMetric(String name) {
		super(name, 1e9);
		this.format = new DecimalFormat("##0.00#");
	}

	@Override
	public String summary(String displayName) {
		StringBuilder builder = new StringBuilder();

		double all = getTotal();
		// getEntries().values().stream().mapToDouble(Double::doubleValue).sum();

		if (all > 90) {
			builder.append(displayName + " - " + format.format(all / 60.0) + " minutes");
		} else {
			builder.append(displayName + " - " + format.format(all) + " seconds");
		}

		if (getEntries().isEmpty()) return builder.toString();

		builder.append(":\n");

		String form = "\t%s: %d [%ss tot | %sms avg] %d%%\n";

		getEntries().entrySet().stream()
				.sorted(Comparator.comparing((Map.Entry<String, Double> ent) -> ent.getValue()).reversed())
				.forEachOrdered(entry -> {
					builder.append(String.format(form, entry.getKey(), getCounts().get(entry.getKey()),
							format.format(entry.getValue()),
							format.format(1000 * entry.getValue() / getCounts().get(entry.getKey())),
							(int) (100 * entry.getValue() / all)));
				});

		return builder.toString();
	}
}
