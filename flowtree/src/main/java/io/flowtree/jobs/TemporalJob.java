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

package io.flowtree.jobs;

import io.flowtree.job.Job;
import org.almostrealism.time.Temporal;

import java.util.concurrent.CompletableFuture;

public class TemporalJob implements Job {
	private Temporal temporal;
	private int iterations;
	private final CompletableFuture<Void> future = new CompletableFuture<>();
	private boolean stopped;

	public TemporalJob() { }

	public TemporalJob(Temporal t) {
		this(t, 0);
	}

	public TemporalJob(Temporal t, int iterations) {
		setTemporal(t);
		setIterations(iterations);
	}

	public Temporal getTemporal() { return temporal; }
	public void setTemporal(Temporal temporal) { this.temporal = temporal; }

	protected int getIterations() { return iterations; }
	protected void setIterations(int iterations) { this.iterations = iterations; }

	@Override
	public String getTaskId() {
		return "";
	}

	@Override
	public String getTaskString() {
		return null;
	}

	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	@Override
	public void run() {
		int index = 0;

		while (!stopped) {
			if (iterations > 0) {
				index++;

				if (index >= iterations) {
					stopped = true;
				}
			}

			temporal.tick().get().run();
		}

		future.complete(null);
	}

	@Override
	public String encode() {
		return null;
	}

	@Override
	public void set(String k, String v) {

	}
}
