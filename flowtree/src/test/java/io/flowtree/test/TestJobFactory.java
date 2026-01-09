/*
 * Copyright 2018 Michael Murray
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

package io.flowtree.test;

import io.flowtree.Server;
import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;

import java.util.concurrent.CompletableFuture;

/**
 * A simple test implementation of {@link JobFactory} that produces jobs
 * which sleep for a configurable duration. Useful for testing the
 * distributed job framework without real computation.
 *
 * @author Mike Murray
 */
public class TestJobFactory implements JobFactory {
	private double pri;
	private int jobs;
	private int sleep = 5000;
	private String id = "-1";
	private CompletableFuture<Void> future = new CompletableFuture<>();

	/**
	 * A simple test job that sleeps for a configurable duration.
	 */
	public static class TestJob implements Job {
		private String id = "-1";
		private int i = -1;
		private int sleep = 5000;
		private CompletableFuture<Void> future = new CompletableFuture<>();

		public TestJob() {}

		public TestJob(int i, int sleep, String id) {
			this.i = i;
			this.sleep = sleep;
			this.id = id;
		}

		@Override
		public void run() {
			try {
				Thread.sleep(this.sleep);
				System.out.println("TestJob: Slept " + this.sleep + " (i = " + this.i + ")");
				future.complete(null);
			} catch (InterruptedException ie) {
				System.out.println("TestJob: Sleep interrupted.");
				future.completeExceptionally(ie);
			}
		}

		@Override
		public String encode() {
			return this.getClass().getName() + ":i=" + this.i + ":s=" + this.sleep + ":id=" + this.id;
		}

		@Override
		public void set(String key, String value) {
			if (key.equals("i")) {
				this.i = Integer.parseInt(value);
			} else if (key.equals("s")) {
				this.sleep = Integer.parseInt(value);
			} else if (key.equals("id")) {
				this.id = value;
			}
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof TestJob)) return false;

			TestJob t = (TestJob) o;
			return (t.getTaskId().equals(this.getTaskId()) && t.i == this.i);
		}

		@Override
		public int hashCode() { return this.getTaskId().hashCode() + this.i; }

		@Override
		public String getTaskId() { return this.id; }

		@Override
		public String toString() { return this.encode(); }

		@Override
		public String getTaskString() { return "System Test (" + this.id + ")"; }

		@Override
		public CompletableFuture<Void> getCompletableFuture() { return future; }
	}

	public TestJobFactory() { }
	public TestJobFactory(int sleep) { this.sleep = sleep; }

	@Override
	public Job nextJob() { return new TestJob(this.jobs++, this.sleep, this.id); }

	@Override
	public Job createJob(String data) { return Server.instantiateJobClass(data); }

	@Override
	public String encode() {
		StringBuilder buf = new StringBuilder();
		buf.append(this.getClass().getName());
		buf.append(":s=");
		buf.append(this.sleep);
		buf.append(":id=");
		buf.append(this.id);
		return buf.toString();
	}

	@Override
	public void set(String key, String value) {
		if (key.equals("id"))
			this.id = value;
		else
			this.sleep = Integer.parseInt(value);
	}

	@Override
	public boolean isComplete() { return false; }

	@Override
	public String getTaskId() { return this.id; }

	@Override
	public String getName() { return "System Test (" + this.id + ")"; }

	@Override
	public double getCompleteness() { return 0; }

	@Override
	public void setPriority(double p) { this.pri = p; }

	@Override
	public double getPriority() { return this.pri; }

	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }
}
