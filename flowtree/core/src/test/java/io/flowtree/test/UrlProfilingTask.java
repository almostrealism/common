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
		String nextURL();
		int nextSize();
		void set(String key, String value);
		String encode();
	}

	private String id;
	private double pri = 1.0;
	private Producer producer;
	private CompletableFuture<Void> future = new CompletableFuture<>();

	@Override
	public String getTaskId() { return this.id; }

	@Override
	public Job nextJob() {
		if (this.producer == null) return null;

		return new UrlProfilingJob(this.id,
									this.producer.nextURL(),
									this.producer.nextSize());
	}

	@Override
	public Job createJob(String data) { return Server.instantiateJobClass(data); }

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

	@Override
	public String getName() { return "UrlProfilingTask (" + this.id + ")"; }

	@Override
	public double getCompleteness() { return 0; }

	@Override
	public boolean isComplete() { return false; }

	@Override
	public void setPriority(double p) { this.pri = p; }

	@Override
	public double getPriority() { return this.pri; }

	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }
}
