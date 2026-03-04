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

package io.almostrealism.profile;

import io.almostrealism.code.Computation;
import io.almostrealism.uml.Nameable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

import java.util.Map;
import java.util.function.Function;

/**
 * Base class for operation profiling, providing a keyed, named container for
 * a {@link TimingMetric} that records operation durations.
 *
 * <p>{@code OperationProfile} serves as the foundation of the profiling system.
 * It associates a unique key (typically derived from {@link OperationMetadata#getId()})
 * with a display name and a timing metric that accumulates duration entries for
 * individual operations. Each entry is identified by a string produced by the
 * configurable {@link #getIdentifier() identifier function}.</p>
 *
 * <p>This class also provides factory methods for the three profiling listener
 * interfaces ({@link OperationTimingListener}, {@link ScopeTimingListener},
 * {@link CompilationTimingListener}), with default implementations that either
 * record into this profile's metric or do nothing. Subclasses like
 * {@link OperationProfileNode} override these to provide hierarchical recording.</p>
 *
 * <h2>Static Utilities</h2>
 * <ul>
 *   <li>{@link #metadataKey(OperationMetadata)} &mdash; derives the canonical
 *       string key from metadata (ID-based by default, or display-name-based
 *       when {@link #enableMetadataId} is {@code false})</li>
 *   <li>{@link #defaultIdentifier(OperationMetadata)} &mdash; produces a
 *       human-readable identifier from metadata including short description,
 *       shape, and context name</li>
 *   <li>{@link #appendShape(Function)} and {@link #appendContext(Function)} &mdash;
 *       decorator functions for enriching identifier strings with shape and
 *       context information</li>
 * </ul>
 *
 * @see OperationProfileNode
 * @see OperationMetadata
 * @see TimingMetric
 *
 * @author Michael Murray
 */
public class OperationProfile implements Nameable, ConsoleFeatures {
	/**
	 * When {@code true} (default), metadata keys are derived from
	 * {@link OperationMetadata#getId()}. When {@code false}, the
	 * display name is used instead.
	 */
	public static boolean enableMetadataId = true;

	/** Counter used to generate unique names for timing metrics. */
	public static long id = 0;

	private String key;
	protected String name;
	private TimingMetric metric;
	private Function<OperationMetadata, String> identifier;

	/** Creates a default profile with no key and the name "default". */
	public OperationProfile() {
		this(null, "default");
	}

	/**
	 * Creates a profile with the given name and no key.
	 *
	 * @param name the display name for this profile
	 */
	public OperationProfile(String name) {
		this(null, name);
	}

	/**
	 * Creates a profile with the given key and name, using the default identifier.
	 *
	 * @param key  the unique key (may be {@code null})
	 * @param name the display name
	 */
	public OperationProfile(String key, String name) {
		this(key, name, OperationProfile::defaultIdentifier);
	}

	/**
	 * Creates a profile from the given metadata using the default identifier.
	 *
	 * @param metadata the operation metadata to derive key and name from
	 */
	public OperationProfile(OperationMetadata metadata) {
		this(metadata, OperationProfile::defaultIdentifier);
	}

	/**
	 * Creates a profile from the given metadata with a custom identifier function.
	 *
	 * @param metadata   the operation metadata to derive key and name from
	 * @param identifier the function used to derive display identifiers from metadata
	 */
	public OperationProfile(OperationMetadata metadata, Function<OperationMetadata, String> identifier) {
		this(metadataKey(metadata), metadata.getDisplayName(), identifier);
	}

	/**
	 * Creates a profile with the given key, name, and identifier function.
	 *
	 * @param key        the unique key (may be {@code null})
	 * @param name       the display name
	 * @param identifier the function used to derive display identifiers from metadata
	 */
	public OperationProfile(String key, String name,
							Function<OperationMetadata, String> identifier) {
		setKey(key);
		setName(name);
		setIdentifier(identifier);
	}

	/**
	 * Returns the unique key for this profile, typically derived from
	 * {@link OperationMetadata#getId()}.
	 *
	 * @return the profile key, or {@code null}
	 */
	public String getKey() { return key; }

	/**
	 * Sets the unique key for this profile.
	 *
	 * @param key the key to set
	 */
	public void setKey(String key) { this.key = key; }

	@Override
	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String getName() { return name; }

	/**
	 * Lazily initializes the timing metric if not already present, using a
	 * unique name derived from the profile name and a global counter.
	 */
	protected void initMetric() {
		if (metric == null) {
			metric = console().timing(name + "_prof" + id++);
		}
	}

	/**
	 * Sets the metric's accumulated duration entries. Initializes the metric
	 * if necessary. Primarily used during deserialization.
	 *
	 * @param entries the entries map (key to total duration in seconds)
	 */
	public void setMetricEntries(Map<String, Double> entries) {
		initMetric();
		metric.setEntries(entries);
	}

	/**
	 * Returns the metric's accumulated duration entries, or {@code null}
	 * if no metric is initialized.
	 *
	 * @return the entries map, or {@code null}
	 */
	public Map<String, Double> getMetricEntries() { return metric == null ? null : metric.getEntries(); }

	/**
	 * Sets the metric's invocation counts. Initializes the metric if necessary.
	 * Primarily used during deserialization.
	 *
	 * @param counts the counts map (key to invocation count)
	 */
	public void setMetricCounts(Map<String, Integer> counts) {
		initMetric();
		metric.setCounts(counts);
	}

	/**
	 * Returns the metric's invocation counts, or {@code null} if no metric
	 * is initialized.
	 *
	 * @return the counts map, or {@code null}
	 */
	public Map<String, Integer> getMetricCounts() { return metric == null ? null : metric.getCounts(); }

	/**
	 * Sets the timing metric directly.
	 *
	 * @param metric the timing metric to set
	 */
	public void setMetric(TimingMetric metric) { this.metric = metric; }

	/**
	 * Returns the timing metric for this profile.
	 *
	 * @return the timing metric, or {@code null} if not initialized
	 */
	public TimingMetric getMetric() { return metric; }

	/**
	 * Returns the total duration accumulated in this profile's metric, in seconds.
	 *
	 * @return the total duration, or 0.0 if no metric is initialized
	 */
	public double getTotalDuration() { return metric == null ? 0.0 : metric.getTotal(); }

	/**
	 * Returns the identifier function used to derive display strings from
	 * {@link OperationMetadata}.
	 *
	 * @return the identifier function
	 */
	public Function<OperationMetadata, String> getIdentifier() { return identifier; }

	/**
	 * Sets the identifier function.
	 *
	 * @param key the identifier function
	 */
	public void setIdentifier(Function<OperationMetadata, String> key) { this.identifier = key; }

	/**
	 * Returns a timing listener that delegates to {@link #recordDuration}.
	 *
	 * @return a general-purpose timing listener
	 */
	public OperationTimingListener getTimingListener() {
		return this::recordDuration;
	}

	/**
	 * Returns a runtime timing listener. The default implementation delegates
	 * to {@link #getTimingListener()}; subclasses may override to provide
	 * hierarchical recording.
	 *
	 * @return a runtime timing listener
	 */
	public OperationTimingListener getRuntimeListener() {
		return getTimingListener();
	}

	/**
	 * Returns a scope timing listener. The default implementation is a no-op;
	 * subclasses like {@link OperationProfileNode} override to record
	 * compilation stage durations.
	 *
	 * @param exclusive whether stage times should be mutually exclusive entries
	 * @return a scope timing listener
	 */
	public ScopeTimingListener getScopeListener(boolean exclusive) {
		return (root, metadata, stage, nanos) -> { };
	}

	/**
	 * Returns a compilation timing listener. The default implementation is a
	 * no-op; subclasses override to capture compilation source and timing data.
	 *
	 * @return a compilation timing listener
	 */
	public CompilationTimingListener getCompilationListener() {
		return (metadata, arguments, code, nanos) -> { };
	}

	/** Logs the {@link #summary()} to the console. */
	public void print() { log(summary()); }

	/**
	 * Returns a human-readable summary of this profile's timing data.
	 *
	 * @return the summary string, or "No metric data" if no metric is initialized
	 */
	public String summary() { return metric == null ? "No metric data" : metric.summary(getName()); }

	/**
	 * Records a duration entry for the given operation. The entry key is
	 * derived from the operation metadata via the identifier function.
	 *
	 * @param requesterMetadata  the metadata of the requesting operation (unused in base class)
	 * @param operationMetadata  the metadata of the timed operation
	 * @param nanos              the duration in nanoseconds
	 */
	public void recordDuration(OperationMetadata requesterMetadata, OperationMetadata operationMetadata, long nanos) {
		initMetric();
		metric.addEntry(getIdentifier().apply(operationMetadata), nanos);
	}

	/** Clears all accumulated timing data from the metric. */
	public void clear() {
		if (metric != null) metric.clear();
	}

	@Override
	public Console console() { return Computation.console; }

	/**
	 * Derives the canonical string key for the given metadata. When
	 * {@link #enableMetadataId} is {@code true}, returns the string
	 * representation of the metadata's ID. Otherwise, returns the display name.
	 *
	 * @param metadata the metadata to derive a key from
	 * @return the key string, or {@code null} if metadata is {@code null}
	 */
	public static String metadataKey(OperationMetadata metadata) {
		if (metadata == null) return null;
		return enableMetadataId ? String.valueOf(metadata.getId()) : metadata.getDisplayName();
	}

	/**
	 * Produces a default human-readable identifier from the given metadata,
	 * including the short description, shape details, and context name.
	 *
	 * @param metadata the metadata to derive an identifier from
	 * @return a formatted identifier string
	 */
	public static String defaultIdentifier(OperationMetadata metadata) {
		String key = metadata.getShortDescription();
		if (key == null) key = "<unknown>";
		if (metadata.getShape() != null) key += " " + metadata.getShape().toStringDetail();
		if (metadata.getContextName() != null) key += " | [" + metadata.getContextName() + "]";
		return key;
	}

	/**
	 * Returns a decorator function that appends shape detail information to the
	 * result of the given identifier function.
	 *
	 * @param key the base identifier function to decorate
	 * @return a new function that appends shape details when available
	 */
	public static Function<OperationMetadata, String> appendShape(Function<OperationMetadata, String> key) {
		return metadata -> {
			String result = key.apply(metadata);
			if (metadata.getShape() != null) result += " " + metadata.getShape().toStringDetail();
			return result;
		};
	}

	/**
	 * Returns a decorator function that appends context name information to the
	 * result of the given identifier function.
	 *
	 * @param key the base identifier function to decorate
	 * @return a new function that appends context name when available
	 */
	public static Function<OperationMetadata, String> appendContext(Function<OperationMetadata, String> key) {
		return metadata -> {
			String result = key.apply(metadata);
			if (metadata.getContextName() != null) result += " | [" + metadata.getContextName() + "]";
			return result;
		};
	}
}
