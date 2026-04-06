/*
 * Copyright 2019 Michael Murray
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

package io.flowtree.airflow;

import io.flowtree.job.Job;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link Job} implementation that executes a shell command on the local
 * operating system. {@link AirflowJob} instances are created by
 * {@link AirflowJobFactory} in response to HTTP requests arriving on the
 * Airflow integration endpoint, allowing Apache Airflow (or any HTTP client)
 * to submit shell-based work items into the FlowTree job queue.
 *
 * <p>The encoded form of an {@link AirflowJob} is:
 * {@code <classname>:id=<taskId>:cmd=<command>}, which allows the job to be
 * transmitted to peer nodes and reconstructed there via
 * {@link #set(String, String)}.
 *
 * @author  Michael Murray
 */
public class AirflowJob implements Job {

	/** Unique identifier for this job within the FlowTree task graph. */
	private String taskId;

	/** The shell command string to execute when this job runs. */
	private String command;

	/** Future that is completed when the command finishes or fails. */
	private final CompletableFuture<Void> future;

	/**
	 * Constructs a new {@link AirflowJob} with the specified task identifier
	 * and shell command. Prints a diagnostic message to standard output.
	 *
	 * @param taskId  unique identifier for this job
	 * @param command shell command string to execute at runtime
	 */
	public AirflowJob(String taskId, String command) {
		this.taskId = taskId;
		this.command = command;
		this.future = new CompletableFuture<>();
		System.out.println("Constructing " + getTaskString());
	}

	/**
	 * Returns the unique task identifier for this job.
	 *
	 * @return the task id string
	 */
	@Override
	public String getTaskId() { return taskId; }

	/**
	 * Returns the shell command string associated with this job.
	 *
	 * @return the command string
	 */
	@Override
	public String getTaskString() { return command; }

	/**
	 * Returns the {@link CompletableFuture} that tracks completion of this job.
	 * The future is completed normally when the command process is started, or
	 * completed exceptionally if the process cannot be started.
	 *
	 * @return the completion future
	 */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	/**
	 * Returns a string encoding of this job suitable for transmission to a
	 * remote FlowTree node. The format is:
	 * {@code <classname>:id=<taskId>:cmd=<command>}.
	 *
	 * @return encoded representation of this job
	 */
	@Override
	public String encode() {

		String b = this.getClass().getName() +
				":id=" +
				this.taskId +
				":cmd=" +
				command;

		return b;
	}

	/**
	 * Restores the state of this job from a key/value pair decoded from an
	 * encoded job string. Recognized keys are {@code "id"} (task identifier)
	 * and {@code "cmd"} (shell command).
	 *
	 * @param key   the field name
	 * @param value the field value
	 */
	@Override
	public void set(String key, String value) {
		if (key.equals("id")) {
			this.taskId = value;
		} else if (key.equals("cmd")) {
			this.command = value;
		}
	}

	/**
	 * Executes the shell command via {@link Runtime#exec(String)}. If the
	 * command launches successfully the completion future is resolved normally;
	 * if an {@link IOException} occurs the future is completed exceptionally
	 * and the stack trace is printed to standard error.
	 */
	@Override
	public void run() {
		Runtime r = Runtime.getRuntime();

		try {
			System.out.println("Running " + command);
			r.exec(command);
			future.complete(null);
		} catch (IOException e) {
			e.printStackTrace();
			future.completeExceptionally(e);
		}
	}

	/**
	 * Returns a human-readable description of this job, showing the command
	 * string enclosed in single quotes.
	 *
	 * @return string representation
	 */
	@Override
	public String toString() { return "'" + getTaskString() + "'"; }
}
