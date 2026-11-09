/*
 * Copyright 2026 Michael Murray
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

package io.flowtree.node;

import io.flowtree.job.Job;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A minimal {@link Job} for node-level tests: it carries a task id and an optional
 * set of required labels, and does nothing when run. It exists so that the label
 * routing and queue behaviour of {@link Node} and {@link NodeGroupMetrics} can be
 * driven with jobs whose requirements are known in advance.
 *
 * @author Michael Murray
 */
class LabeledTestJob implements Job {

	/** The task identifier returned by {@link #getTaskId()}. */
	private String taskId;

	/** The labels a node must carry to run this job; never {@code null}. */
	private final Map<String, String> requiredLabels;

	/** Completed by {@link #run()}. */
	private final CompletableFuture<Void> future = new CompletableFuture<>();

	/**
	 * Creates a job with no label requirements.
	 *
	 * @param taskId the task identifier
	 */
	LabeledTestJob(String taskId) {
		this(taskId, Collections.emptyMap());
	}

	/**
	 * Creates a job that only nodes carrying the given labels may run. The labels are
	 * defensively copied, so a later mutation of the caller's map does not change this
	 * job's requirements.
	 *
	 * @param taskId         the task identifier
	 * @param requiredLabels the labels a node must carry; a {@code null} value is treated as none
	 */
	LabeledTestJob(String taskId, Map<String, String> requiredLabels) {
		this.taskId = taskId;
		this.requiredLabels = requiredLabels == null
				? Collections.emptyMap()
				: Collections.unmodifiableMap(new HashMap<>(requiredLabels));
	}

	/** Completes the job's future without performing any work. */
	@Override
	public void run() {
		future.complete(null);
	}

	@Override
	public Map<String, String> getRequiredLabels() {
		return requiredLabels;
	}

	@Override
	public void set(String key, String value) {
		if ("id".equals(key)) {
			this.taskId = value;
		}
	}

	@Override
	public String encode() {
		return LabeledTestJob.class.getName() + "::id:=" + taskId;
	}

	@Override
	public String getTaskId() {
		return taskId;
	}

	@Override
	public String getTaskString() {
		return "LabeledTestJob[" + taskId + "]";
	}

	@Override
	public CompletableFuture<Void> getCompletableFuture() {
		return future;
	}
}
