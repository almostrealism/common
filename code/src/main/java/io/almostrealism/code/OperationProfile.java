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

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class OperationProfile implements Named {
	private String name;
	private Map<String, Long> totalTime;
	private Map<String, Integer> count;

	public OperationProfile() {
		this("default");
	}

	public OperationProfile(String name) {
		this.name = name;
		this.totalTime = new HashMap<>();
		this.count = new HashMap<>();
	}

	@Override
	public String getName() { return name; }

	public void print() { System.out.println(summary()); }

	public String summary() {
		StringBuilder builder = new StringBuilder();

		double all = totalTime.values().stream().mapToLong(Long::longValue).sum();
		builder.append("Operation Profile (" + getName() + " - " + (all / 1000) + " seconds):\n");

		totalTime.entrySet().stream()
				.sorted(Comparator.comparing((Map.Entry<String, Long> ent) -> ent.getValue()).reversed())
				.forEachOrdered(entry -> {
			builder.append("\t" + entry.getKey() + ": " + count.get(entry.getKey()) + " - " +
					entry.getValue() + "ms (" + (int) (100 * entry.getValue() / all) + "%)\n");
		});

		return builder.toString();
	}

	public void recordDuration(Runnable r) {
		long start = System.currentTimeMillis(); // System.nanoTime();
		r.run();
		long end = System.currentTimeMillis(); // System.nanoTime();

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
	}

	public void recordDuration(OperationMetadata metadata, long duration) {
		String key = metadata.getShortDescription();
		if (key == null) key = "<unknown>";

		totalTime.put(key, totalTime.getOrDefault(metadata.getShortDescription(), 0L) + duration);
		count.put(key, count.getOrDefault(metadata.getShortDescription(), 0) + 1);
	}

	public void clear() {
		totalTime.clear();
		count.clear();
	}
}
