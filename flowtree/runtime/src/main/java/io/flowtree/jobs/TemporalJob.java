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

/**
 * A {@link Job} implementation that drives a {@link Temporal} simulation by
 * repeatedly calling its {@code tick()} method on the FlowTree worker thread.
 *
 * <p>The job runs either indefinitely (when {@code iterations == 0}) or for a
 * fixed number of ticks (when {@code iterations > 0}). Each call to
 * {@link Temporal#tick()} returns a {@link Runnable} that is executed
 * synchronously before the next tick begins.</p>
 *
 * <p>This class does not support serialization ({@link #encode()} returns
 * {@code null}) and is therefore intended for in-process use only, where
 * the {@link Temporal} reference can be passed directly at construction time.</p>
 *
 * @author Michael Murray
 * @see org.almostrealism.time.Temporal
 */
public class TemporalJob implements Job {

	/** The temporal simulation to advance on each tick. */
	private Temporal temporal;

	/**
	 * Maximum number of ticks to run. A value of {@code 0} (or negative)
	 * means the job runs until stopped externally.
	 */
	private int iterations;

	/** Future completed when the tick loop exits. */
	private final CompletableFuture<Void> future = new CompletableFuture<>();

	/**
	 * Whether the tick loop should stop. Set to {@code true} when the
	 * configured iteration count is reached.
	 */
	private boolean stopped;

	/**
	 * No-argument constructor for subclasses or frameworks that set
	 * properties separately.
	 */
	public TemporalJob() { }

	/**
	 * Creates a {@code TemporalJob} that runs indefinitely.
	 *
	 * @param t the temporal simulation to drive
	 */
	public TemporalJob(Temporal t) {
		this(t, 0);
	}

	/**
	 * Creates a {@code TemporalJob} that runs for a fixed number of ticks.
	 *
	 * @param t          the temporal simulation to drive
	 * @param iterations the maximum number of ticks ({@code 0} for unlimited)
	 */
	public TemporalJob(Temporal t, int iterations) {
		setTemporal(t);
		setIterations(iterations);
	}

	/**
	 * Returns the temporal simulation this job drives.
	 *
	 * @return the {@link Temporal} instance
	 */
	public Temporal getTemporal() { return temporal; }

	/**
	 * Sets the temporal simulation this job drives.
	 *
	 * @param temporal the {@link Temporal} instance
	 */
	public void setTemporal(Temporal temporal) { this.temporal = temporal; }

	/**
	 * Returns the maximum number of ticks ({@code 0} for unlimited).
	 *
	 * @return the iteration limit
	 */
	protected int getIterations() { return iterations; }

	/**
	 * Sets the maximum number of ticks.
	 *
	 * @param iterations the iteration limit ({@code 0} for unlimited)
	 */
	protected void setIterations(int iterations) { this.iterations = iterations; }

	/**
	 * Returns an empty string because {@code TemporalJob} has no
	 * meaningful task identifier in the FlowTree messaging layer.
	 *
	 * @return an empty string
	 */
	@Override
	public String getTaskId() {
		return "";
	}

	/**
	 * Returns {@code null} because {@code TemporalJob} has no task string.
	 *
	 * @return {@code null}
	 */
	@Override
	public String getTaskString() {
		return null;
	}

	/**
	 * Returns the future that is completed when the tick loop exits.
	 *
	 * @return the completion future
	 */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	/**
	 * Drives the temporal simulation by calling {@link Temporal#tick()} in a
	 * loop until the iteration limit is reached or the job is stopped.
	 *
	 * <p>If {@code iterations} is positive, the loop counts ticks and sets
	 * {@code stopped} when the limit is reached. Otherwise the loop runs
	 * indefinitely. The future is completed after the loop exits.</p>
	 */
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

	/**
	 * Returns {@code null} because {@code TemporalJob} cannot be serialized
	 * for remote dispatch; it must be used in-process.
	 *
	 * @return {@code null}
	 */
	@Override
	public String encode() {
		return null;
	}

	/**
	 * No-op implementation of the property setter required by {@link Job}.
	 * {@code TemporalJob} does not support deserialization from key-value pairs.
	 *
	 * @param k ignored
	 * @param v ignored
	 */
	@Override
	public void set(String k, String v) {

	}
}
