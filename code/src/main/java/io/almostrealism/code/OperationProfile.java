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

package io.almostrealism.code;

import io.almostrealism.uml.Named;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

import java.util.function.Function;

public class OperationProfile implements Named, ConsoleFeatures {
	public static long id = 0;

	private String name;
	private TimingMetric metric;
	private Function<OperationMetadata, String> key;

	public OperationProfile() {
		this("default");
	}

	public OperationProfile(String name) {
		this(name, OperationProfile::defaultKey);
	}

	public OperationProfile(String name, Function<OperationMetadata, String> key) {
		this.name = name;
		this.metric = console().timing(name + "_prof" + id++);
		setKey(key);
	}

	@Override
	public String getName() { return name; }

	public TimingMetric getMetric() { return metric; }

	public double getTotalDuration() { return metric.getTotal(); }

	public Function<OperationMetadata, String> getKey() { return key; }

	public void setKey(Function<OperationMetadata, String> key) { this.key = key; }

	public void print() { log(summary()); }

	public String summary() { return metric.summary(getName()); }

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

	public void recordDuration(OperationMetadata metadata, long nanos) {
		metric.addEntry(getKey().apply(metadata), nanos);
	}

	public void clear() { metric.clear(); }

	@Override
	public Console console() { return Computation.console; }

	public static String defaultKey(OperationMetadata metadata) {
		String key = metadata.getShortDescription();
		if (key == null) key = "<unknown>";
		if (metadata.getShape() != null) key += " " + metadata.getShape().toStringDetail();
		if (metadata.getContextName() != null) key += " | [" + metadata.getContextName() + "]";
		return key;
	}

	public static Function<OperationMetadata, String> appendShape(Function<OperationMetadata, String> key) {
		return metadata -> {
			String result = key.apply(metadata);
			if (metadata.getShape() != null) result += " " + metadata.getShape().toStringDetail();
			return result;
		};
	}

	public static Function<OperationMetadata, String> appendContext(Function<OperationMetadata, String> key) {
		return metadata -> {
			String result = key.apply(metadata);
			if (metadata.getContextName() != null) result += " | [" + metadata.getContextName() + "]";
			return result;
		};
	}
}
