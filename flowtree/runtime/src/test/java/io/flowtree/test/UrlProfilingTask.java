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
 * A {@link JobFactory} that produces {@link UrlProfilingJob} instances
 * using a pluggable {@link Producer} to generate URLs.
 *
 * @author Michael Murray
 */
public class UrlProfilingTask implements JobFactory {

	/**
	 * Interface for producing URLs to profile.
	 */
	public interface Producer {
		/** Returns the next URL to be profiled. */
		String nextURL();

		/** Returns the size associated with the next profiling job. */
		int nextSize();

		/** Sets a configuration key-value pair on this producer. */
		void set(String key, String value);

		/** Encodes the producer state as a string for serialization. */
		String encode();
	}

	/** The task identifier. */
	private String id;

	/** The priority of this task. */
	private double pri = 1.0;

	/** The producer used to generate URLs for profiling jobs. */
	private Producer producer;

	/** The future that completes when this task is done. */
	private CompletableFuture<Void> future = new CompletableFuture<>();

	/** Returns the identifier of this task. */
	@Override
	public String getTaskId() { return this.id; }

	/** Creates and returns the next {@link UrlProfilingJob}, or null if no producer is set. */
	@Override
	public Job nextJob() {
		if (this.producer == null) return null;

		return new UrlProfilingJob(this.id,
									this.producer.nextURL(),
									this.producer.nextSize());
	}

	/** Creates a job by deserializing the given encoded data string. */
	@Override
	public Job createJob(String data) { return Server.instantiateJobClass(data); }

	/** Sets a configuration property, including task id and producer class, on this task. */
	@Override
	public void set(String key, String value) {
		if (key.equals("id")) {
			this.id = value;
		} else if (key.equals("producer")) {
			try {
				this.producer = (Producer) Class.forName(value).getDeclaredConstructor().newInstance();
			} catch (Exception e) {
				throw new RuntimeException("Could not instantiate producer (" + e.getMessage() + ")");
			}
		} else if (this.producer != null) {
			this.producer.set(key, value);
		} else if (this.producer == null) {
			this.producer = new DefaultProducer();
			this.producer.set(key, value);
		}
	}

	/** Encodes this task's configuration as a colon-separated key-value string. */
	@Override
	public String encode() {
		StringBuilder b = new StringBuilder();
		b.append(this.getClass().getName());
		b.append(":id=");
		b.append(this.id);

		if (this.producer != null) {
			b.append(":producer=");
			b.append(this.producer.getClass().getName());
			b.append(this.producer.encode());
		}

		return b.toString();
	}

	/** Returns the display name of this task including its identifier. */
	@Override
	public String getName() { return "UrlProfilingTask (" + this.id + ")"; }

	/** Returns the completeness fraction of this task, always 0. */
	@Override
	public double getCompleteness() { return 0; }

	/** Returns whether this task is complete, always false. */
	@Override
	public boolean isComplete() { return false; }

	/** Sets the scheduling priority for this task. */
	@Override
	public void setPriority(double p) { this.pri = p; }

	/** Returns the scheduling priority of this task. */
	@Override
	public double getPriority() { return this.pri; }

	/** Returns the {@link CompletableFuture} associated with this task's completion. */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }
}
