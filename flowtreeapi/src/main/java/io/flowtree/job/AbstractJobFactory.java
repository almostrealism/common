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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public abstract class AbstractJobFactory implements JobFactory {
	private String taskId;
	private String name;
	private double priority;

	private final CompletableFuture<Void> future;
	private final Map<String, String> properties;

	public AbstractJobFactory() {
		future = new CompletableFuture<>();
		properties = new HashMap<>();
		priority = 1.0;
	}

	public AbstractJobFactory(String taskId) {
		this();
		this.taskId = taskId;
	}

	public AbstractJobFactory(String taskId, String name) {
		this(taskId);
		this.name = name;
	}

	@Override
	public String getTaskId() { return taskId; }

	/**
	 * Default implementation returns null
	 */
	@Override
	public Job createJob(String data) { return null; }

	/**
	 * Sets a property of this JobFactory object. Any JobFactory object that is to be
	 * transmitted between network nodes will have this method called when it arrives at
	 * a new host to initialize its variables based on the string returned by the encode
	 * method.
	 *
	 * @param key  Property name.
	 * @param value  Property value.
	 */
	@Override
	public void set(String key, String value) {
		properties.put(key, value);
	}

	protected String get(String key) { return properties.get(key); }

	/**
	 * The encode method must return a string of the form:
	 * "classname:key0=value0:key1=value1:key2=value2..."
	 * Where classname is the name of the class that is implementing JobFactory, and the
	 * key=value pairs are pairs of keys and values that will be passed to the set method
	 * of the class to initialize the state of the object after it has been transmitted
	 * from one node to another.
	 *
	 * @return  A String representation of this JobFactory object.
	 */
	@Override
	public String encode() {
		return properties.entrySet().stream()
				.map(ent -> ENTRY_SEPARATOR + ent.getKey() + KEY_VALUE_SEPARATOR + ent.getValue())
				.collect(Collectors.joining("", getClass().getName(), ""));
	}

	/**
	 * @return  A name for the task represented by this JobFactory.
	 */
	@Override
	public String getName() { return name; }

	/**
	 * @return  True if this JobFactory will not be producing any more jobs, false otherwise.
	 */
	@Override
	public boolean isComplete() { return getCompleteness() >= 1.0; }

	/**
	 * Sets the priority of this task.
	 */
	@Override
	public void setPriority(double p) { priority = p; }

	/**
	 * @return  The priority of this task.
	 */
	@Override
	public double getPriority() { return priority; }

	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }
}
