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
import org.almostrealism.io.ConsoleFeatures;

import java.util.concurrent.CompletableFuture;

/**
 * A simple test implementation of {@link JobFactory} that produces jobs
 * which sleep for a configurable duration. Useful for testing the
 * distributed job framework without real computation.
 *
 * @author Mike Murray
 */
public class TestJobFactory implements JobFactory, ConsoleFeatures {
	/** The priority of this factory's jobs. */
	private double pri;

	/** The count of jobs created so far. */
	private int jobs;

	/** The sleep duration in milliseconds for each job. */
	private int sleep = 5000;

	/** The task identifier for this factory. */
	private String id = "-1";

	/** The future that completes when the factory's work is done. */
	private CompletableFuture<Void> future = new CompletableFuture<>();

	/**
	 * A simple test job that sleeps for a configurable duration.
	 */
	public static class TestJob implements Job, ConsoleFeatures {
		/** The task identifier for this job. */
		private String id = "-1";

		/** The index of this job within its factory sequence. */
		private int i = -1;

		/** The sleep duration in milliseconds for this job. */
		private int sleep = 5000;

		/** The future that completes when this job finishes. */
		private CompletableFuture<Void> future = new CompletableFuture<>();

		/** Creates a default TestJob with uninitialized index and default sleep duration. */
		public TestJob() {}

		/**
		 * Creates a TestJob with the given index, sleep duration, and task identifier.
		 */
		public TestJob(int i, int sleep, String id) {
			this.i = i;
			this.sleep = sleep;
			this.id = id;
		}

		/** Sleeps for the configured duration and completes the future upon success. */
		@Override
		public void run() {
			try {
				Thread.sleep(this.sleep);
				log("TestJob: Slept " + this.sleep + " (i = " + this.i + ")");
				future.complete(null);
			} catch (InterruptedException ie) {
				log("TestJob: Sleep interrupted.");
				future.completeExceptionally(ie);
			}
		}

		/** Returns a string encoding of this job's class name and parameters. */
		@Override
		public String encode() {
			return this.getClass().getName() + ":i=" + this.i + ":s=" + this.sleep + ":id=" + this.id;
		}

		/** Sets a parameter on this job by key name. */
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

		/** Returns true if the given object is a TestJob with the same task id and index. */
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof TestJob)) return false;

			TestJob t = (TestJob) o;
			return (t.getTaskId().equals(this.getTaskId()) && t.i == this.i);
		}

		/** Returns a hash code based on the task id and job index. */
		@Override
		public int hashCode() { return this.getTaskId().hashCode() + this.i; }

		/** Returns the task identifier for this job. */
		@Override
		public String getTaskId() { return this.id; }

		/** Returns the encoded string representation of this job. */
		@Override
		public String toString() { return this.encode(); }

		/** Returns a human-readable description of the task including the task id. */
		@Override
		public String getTaskString() { return "System Test (" + this.id + ")"; }

		/** Returns the future that completes when this job finishes execution. */
		@Override
		public CompletableFuture<Void> getCompletableFuture() { return future; }
	}

	/** Creates a TestJobFactory with the default sleep duration. */
	public TestJobFactory() { }

	/** Creates a TestJobFactory with the specified sleep duration. */
	public TestJobFactory(int sleep) { this.sleep = sleep; }

	/** Creates and returns the next test job in sequence. */
	@Override
	public Job nextJob() { return new TestJob(this.jobs++, this.sleep, this.id); }

	/** Creates a job by deserializing the given encoded data string. */
	@Override
	public Job createJob(String data) { return Server.instantiateJobClass(data); }

	/** Returns a string encoding of this factory's class name and parameters. */
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

	/** Sets a parameter on this factory by key name. */
	@Override
	public void set(String key, String value) {
		if (key.equals("id"))
			this.id = value;
		else
			this.sleep = Integer.parseInt(value);
	}

	/** Returns false since this factory always produces more jobs. */
	@Override
	public boolean isComplete() { return false; }

	/** Returns the task identifier for this factory. */
	@Override
	public String getTaskId() { return this.id; }

	/** Returns a human-readable name for this factory including the task id. */
	@Override
	public String getName() { return "System Test (" + this.id + ")"; }

	/** Returns zero since completeness is not tracked by this test factory. */
	@Override
	public double getCompleteness() { return 0; }

	/** Sets the priority of this factory's jobs. */
	@Override
	public void setPriority(double p) { this.pri = p; }

	/** Returns the priority of this factory's jobs. */
	@Override
	public double getPriority() { return this.pri; }

	/** Returns the future associated with this factory. */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }
}
