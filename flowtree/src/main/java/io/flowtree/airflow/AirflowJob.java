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

public class AirflowJob implements Job {
	private String taskId;
	private String command;
	private final CompletableFuture<Void> future;

	public AirflowJob(String taskId, String command) {
		this.taskId = taskId;
		this.command = command;
		this.future = new CompletableFuture<>();
		System.out.println("Constructing " + getTaskString());
	}

	@Override
	public String getTaskId() { return taskId; }

	@Override
	public String getTaskString() { return command; }

	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	@Override
	public String encode() {

		String b = this.getClass().getName() +
				":id=" +
				this.taskId +
				":cmd=" +
				command;

		return b;
	}

	@Override
	public void set(String key, String value) {
		if (key.equals("id")) {
			this.taskId = value;
		} else if (key.equals("cmd")) {
			this.command = value;
		}
	}

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

	public String toString() { return "'" + getTaskString() + "'"; }
}
