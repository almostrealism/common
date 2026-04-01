/*
 * Copyright 2021 Michael Murray
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

package io.flowtree.job;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Base implementation of {@link JobFactory} that provides property storage,
 * label-based node targeting, priority management, and encode/decode infrastructure.
 *
 * <p>Subclasses must implement {@link #nextJob()} to supply the stream of
 * {@link Job} instances for their task, and {@link #getCompleteness()} to report
 * progress. All other {@link JobFactory} contract requirements — encoding,
 * property propagation, priority, and the completion future — are handled here.</p>
 *
 * <h2>Encoding</h2>
 * <p>{@link #encode()} serializes all properties stored via {@link #set(String, String)}
 * into the wire format {@code classname::key0:=value0::key1:=value1...}. Required
 * labels are stored with the prefix {@code "req."} so they survive the encode/decode
 * round-trip and are restored automatically on the remote node.</p>
 *
 * <h2>Required Labels</h2>
 * <p>Call {@link #setRequiredLabel(String, String)} to constrain which Nodes may
 * execute the jobs produced by this factory. The label restrictions are automatically
 * propagated to each {@link Job} through {@link #getRequiredLabels()}.</p>
 *
 * @author  Michael Murray
 * @see JobFactory
 * @see Job
 */
public abstract class AbstractJobFactory implements JobFactory {
	/** The network-wide unique identifier for the task represented by this factory. */
	private String taskId;

	/** The human-readable name of the task represented by this factory. */
	private String name;

	/** The scheduling priority of this task; defaults to {@code 1.0}. */
	private double priority;

	/**
	 * The future that is completed when all jobs produced by this factory have
	 * finished executing.
	 */
	private final CompletableFuture<Void> future;

	/**
	 * All properties set via {@link #set(String, String)}, including required-label
	 * entries (stored with the {@code "req."} prefix).
	 */
	private final Map<String, String> properties;

	/**
	 * The label key-value pairs that target Nodes must possess in order to execute
	 * jobs produced by this factory.
	 */
	private final Map<String, String> requiredLabels = new LinkedHashMap<>();

	/**
	 * Constructs an {@link AbstractJobFactory} with no task ID or name.
	 * Priority defaults to {@code 1.0}.
	 */
	public AbstractJobFactory() {
		future = new CompletableFuture<>();
		properties = new HashMap<>();
		priority = 1.0;
	}

	/**
	 * Constructs an {@link AbstractJobFactory} with the specified task ID.
	 * Priority defaults to {@code 1.0}.
	 *
	 * @param taskId the network-wide unique identifier for this task
	 */
	public AbstractJobFactory(String taskId) {
		this();
		this.taskId = taskId;
	}

	/**
	 * Constructs an {@link AbstractJobFactory} with the specified task ID and
	 * human-readable name. Priority defaults to {@code 1.0}.
	 *
	 * @param taskId the network-wide unique identifier for this task
	 * @param name   a human-readable name for this task
	 */
	public AbstractJobFactory(String taskId, String name) {
		this(taskId);
		this.name = name;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getTaskId() { return taskId; }

	/**
	 * Returns a {@link Job} decoded from the given encoded string, or {@code null}
	 * if this factory does not support job reconstruction from an encoded string.
	 *
	 * <p>The default implementation returns {@code null}. Subclasses that need to
	 * reconstruct jobs on remote nodes must override this method to parse the
	 * {@code data} string (as produced by {@link Job#encode()}) and return a
	 * fully initialized {@link Job} instance.</p>
	 *
	 * @param data the encoded job string, as produced by {@link Job#encode()}
	 * @return the reconstructed {@link Job}, or {@code null} if unsupported
	 */
	@Override
	public Job createJob(String data) { return null; }

	/**
	 * Sets a named property on this factory, restoring state after a network
	 * transmission or recording configuration provided by a caller.
	 *
	 * <p>This method is called automatically when a factory arrives at a remote
	 * node and is being reconstructed from the string returned by {@link #encode()}.
	 * Each key-value pair encoded in that string is delivered here in turn.</p>
	 *
	 * <p>Keys that begin with the prefix {@code "req."} are additionally stored in
	 * the required-labels map so that Node targeting constraints survive the
	 * encode/decode round-trip. For example, setting key {@code "req.platform"}
	 * with value {@code "macos"} is equivalent to calling
	 * {@link #setRequiredLabel(String, String) setRequiredLabel("platform", "macos")}.</p>
	 *
	 * @param key   the property name
	 * @param value the property value
	 */
	@Override
	public void set(String key, String value) {
		properties.put(key, value);
		if (key.startsWith("req.")) {
			requiredLabels.put(key.substring(4), value);
		}
	}

	/**
	 * Sets a required label that target Nodes must have to execute jobs
	 * produced by this factory.
	 *
	 * @param key   the label key (e.g. "platform")
	 * @param value the required value (e.g. "macos")
	 */
	public void setRequiredLabel(String key, String value) {
		requiredLabels.put(key, value);
		set("req." + key, value);
	}

	/**
	 * Returns the required labels for jobs created by this factory.
	 *
	 * @return unmodifiable map of required label key-value pairs
	 */
	public Map<String, String> getRequiredLabels() {
		return Collections.unmodifiableMap(requiredLabels);
	}

	/**
	 * Returns the value associated with the given property key, as previously stored
	 * via {@link #set(String, String)}.
	 *
	 * <p>This accessor is provided for subclasses to retrieve properties that were
	 * set during deserialization (i.e., transmitted via the encoded wire format) or
	 * by direct callers.</p>
	 *
	 * @param key the property key to look up
	 * @return the stored value, or {@code null} if the key has not been set
	 */
	protected String get(String key) { return properties.get(key); }

	/**
	 * Encodes all stored properties as a string suitable for transmission across
	 * the FlowTree cluster.
	 *
	 * <p>The returned string has the form:
	 * <pre>  classname::key0:=value0::key1:=value1...</pre>
	 * where {@code classname} is the fully-qualified name of the concrete subclass,
	 * {@code "::"} is {@link JobFactory#ENTRY_SEPARATOR}, and {@code ":="} is
	 * {@link JobFactory#KEY_VALUE_SEPARATOR}. All properties passed to
	 * {@link #set(String, String)} — including required-label entries with the
	 * {@code "req."} prefix — are included in the output so that the factory can
	 * be fully reconstructed on a remote node.</p>
	 *
	 * @return the encoded string representation of this factory
	 */
	@Override
	public String encode() {
		return properties.entrySet().stream()
				.map(ent -> ENTRY_SEPARATOR + ent.getKey() + KEY_VALUE_SEPARATOR + ent.getValue())
				.collect(Collectors.joining("", getClass().getName(), ""));
	}

	/**
	 * Returns the human-readable name of this task as supplied to the constructor.
	 *
	 * @return the task name, or {@code null} if none was provided at construction time
	 */
	@Override
	public String getName() { return name; }

	/**
	 * Returns {@code true} when {@link #getCompleteness()} is greater than or equal
	 * to {@code 1.0}, indicating that no more jobs will be produced.
	 *
	 * @return {@code true} if the task is fully complete; {@code false} otherwise
	 */
	@Override
	public boolean isComplete() { return getCompleteness() >= 1.0; }

	/**
	 * Sets the scheduling priority of this task.
	 *
	 * <p>The value is stored and returned by {@link #getPriority()}. Higher values
	 * indicate higher priority. The default value is {@code 1.0}.</p>
	 *
	 * @param p the new priority value
	 */
	@Override
	public void setPriority(double p) { priority = p; }

	/**
	 * Returns the scheduling priority of this task as set by
	 * {@link #setPriority(double)}.
	 *
	 * <p>Defaults to {@code 1.0} if never explicitly set.</p>
	 *
	 * @return the current priority value
	 */
	@Override
	public double getPriority() { return priority; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>The future is created at construction time and must be completed by
	 * subclasses (or the execution infrastructure) once all jobs produced by
	 * this factory have finished.</p>
	 */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }
}
