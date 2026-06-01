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

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.JobOutput;
import org.almostrealism.io.OutputHandler;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * A job that profiles URL download performance by measuring
 * download time and throughput for a specified URL.
 *
 * @author Michael Murray
 */
public class UrlProfilingJob implements Job, ConsoleFeatures {

	/**
	 * Output handler that writes URL profiling results to a timestamped file.
	 */
	public static class Handler implements OutputHandler {
		/** Writer used to persist profiling output to a file. */
		private PrintWriter out;

		/**
		 * Constructs a Handler that writes to a timestamped URL profiling output file.
		 */
		public Handler() throws IOException {
			this.out = new PrintWriter(new BufferedWriter(new FileWriter("htdocs/url-profile" +
							System.currentTimeMillis() + ".txt")));
		}

		/**
		 * Stores a single profiling result line, separating fields with tabs.
		 */
		@Override
		public void storeOutput(long time, int uid, JobOutput output) {
			String s[] = output.getOutput().split(JobFactory.ENTRY_SEPARATOR);

			StringBuilder b = new StringBuilder();
			b.append(time);
			b.append("\t");

			for (int i = 0; i < s.length; i++) {
				b.append(s[i]);
				b.append("\t");
			}

			String bs = b.toString();
			synchronized (this.out) { this.out.println(bs); this.out.flush(); }
		}
	}

	/** Unique task identifier for this job. */
	private String id;

	/** URI of the URL to be profiled. */
	private String uri;

	/** Number of download iterations to perform. */
	private int size;

	/** Future that completes when the job finishes or fails. */
	private CompletableFuture<Void> future = new CompletableFuture<>();

	/** Last output produced by this job after execution. */
	private JobOutput lastOutput;

	/**
	 * Constructs a default UrlProfilingJob with no initial parameters.
	 */
	public UrlProfilingJob() { }

	/**
	 * Constructs a UrlProfilingJob with the specified identifier, URI, and iteration count.
	 */
	public UrlProfilingJob(String id, String uri, int size) {
		this.id = id;
		this.uri = uri;
		this.size = size;
	}

	/**
	 * Returns the unique task identifier for this job.
	 */
	@Override
	public String getTaskId() { return this.id; }

	/**
	 * Returns a human-readable string describing this task and its identifier.
	 */
	@Override
	public String getTaskString() { return "UriProfilingTask (" + this.id + ")"; }

	/**
	 * Encodes this job's configuration as a colon-separated key-value string.
	 */
	@Override
	public String encode() {
		StringBuilder b = new StringBuilder();
		b.append(this.getClass().getName());
		b.append(":id=");
		b.append(this.id);
		b.append(":uri=");
		b.append(this.uri);
		b.append(":size=");
		b.append(this.size);
		return b.toString();
	}

	/**
	 * Sets a configuration property on this job by key name.
	 */
	@Override
	public void set(String key, String value) {
		if (key.equals("id")) {
			this.id = value;
		} else if (key.equals("uri")) {
			this.uri = value;
		} else if (key.equals("size")) {
			this.size = Integer.parseInt(value);
		}
	}

	/**
	 * Executes the URL profiling job, measuring download time and byte throughput
	 * across the configured number of iterations.
	 */
	@Override
	public void run() throws RuntimeException {
		try {
			long start, end, tot = 0, bs = 0;

			for (int i = 0; i < this.size; i++) {
				start = System.currentTimeMillis();

				String d[] = this.uri.split("\\\\");
				StringBuilder b = new StringBuilder();
				for (int j = 0; j < d.length; j++) b.append(d[j]);
				String processedUri = b.toString();

				try (InputStream in = new URL(processedUri).openStream()) {
					while (in.available() > 0) { in.read(); bs++; }
				} catch (MalformedURLException murl) {
					throw new RuntimeException("UrlProfilingJob -- " + murl.getMessage());
				} catch (IOException e) { }

				end = System.currentTimeMillis();
				tot += end - start;

				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
				}
			}

			if (this.size <= 0) {
				future.complete(null);
				return;
			}

			long avgTime = tot / this.size;
			long avgBs = bs / this.size;

			StringBuilder b = new StringBuilder();
			b.append(this.uri.substring(this.uri.lastIndexOf("/")));
			b.append(JobFactory.ENTRY_SEPARATOR);
			b.append(this.size);
			b.append(JobFactory.ENTRY_SEPARATOR);
			b.append(avgTime);
			b.append(JobFactory.ENTRY_SEPARATOR);
			b.append(avgBs);
			b.append(JobFactory.ENTRY_SEPARATOR);

			this.lastOutput = new JobOutput(this.id, "", "", b.toString());
			log("UrlProfilingJob result: " + b);
			future.complete(null);
		} catch (Exception e) {
			future.completeExceptionally(e);
			throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
		}
	}

	/**
	 * Returns the CompletableFuture that completes when this job finishes execution.
	 */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	/**
	 * Returns the last output produced by this job, or null if not yet run.
	 */
	public JobOutput getLastOutput() { return lastOutput; }

	/**
	 * Returns a string representation of this job including its URI and iteration count.
	 */
	@Override
	public String toString() {
		return "UrlProfilingJob (" + this.uri + ") size = " + this.size;
	}
}
