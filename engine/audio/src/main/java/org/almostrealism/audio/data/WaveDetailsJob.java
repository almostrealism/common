/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio.data;

import org.almostrealism.io.ConsoleFeatures;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A prioritized job for computing WaveDetails asynchronously.
 *
 * <p>WaveDetailsJob wraps the computation of {@link WaveDetails} for a
 * {@link WaveDataProvider} with priority-based scheduling. Jobs are used
 * by {@link org.almostrealism.audio.AudioLibrary} to manage background
 * analysis with configurable priority levels.</p>
 *
 * @see WaveDetails
 * @see org.almostrealism.audio.AudioLibrary
 */
public class WaveDetailsJob implements Runnable, ConsoleFeatures {
	/** Function that computes the WaveDetails when this job is run. */
	private final Function<WaveDetailsJob, WaveDetails> runner;

	/** The audio provider whose details are to be computed. */
	private final WaveDataProvider target;

	/** When true, the result is stored persistently across application runs. */
	private final boolean persistent;

	/** Scheduling priority; higher values cause earlier execution. */
	private double priority;

	/** Future that completes with the computed WaveDetails when the job finishes. */
	private final CompletableFuture<WaveDetails> future;

	/**
	 * Creates a WaveDetailsJob for the given provider.
	 *
	 * @param runner     function that performs the analysis and returns the WaveDetails
	 * @param target     the audio provider to analyze
	 * @param persistent whether the result should be persisted
	 * @param priority   scheduling priority (higher = earlier)
	 */
	public WaveDetailsJob(Function<WaveDetailsJob, WaveDetails> runner,
						  WaveDataProvider target, boolean persistent,
						  double priority) {
		this.runner = runner;
		this.target = target;
		this.persistent = persistent;
		this.priority = priority;
		this.future = new CompletableFuture<>();
	}

	public WaveDataProvider getTarget() { return target; }
	public boolean isPersistent() { return persistent; }

	public void setPriority(double priority) { this.priority = priority;}
	public double getPriority() { return priority; }

	@Override
	public void run() {
		WaveDetails details = null;

		try {
			if (getTarget() != null) {
				log("Processing " + getTarget().getKey());
			}

			details = runner.apply(this);
		} catch (Exception e) {
			warn("Failed to process " + this, e);
		} finally {
			complete(details);
		}
	}

	/**
	 * Completes the future with the given WaveDetails result.
	 * This method is called internally by {@link #run()} and may also be
	 * used externally to cancel or override job results.
	 *
	 * @param details the computed details, or {@code null} if computation failed
	 */
	public void complete(WaveDetails details) {
		future.complete(details);
	}

	public CompletableFuture<WaveDetails> getFuture() { return future; }

	public void await() { future.join(); }

	@Override
	public String toString() {
		String target = Optional.ofNullable(getTarget())
				.map(WaveDataProvider::getKey).orElse("<empty>");
		return "WaveDetailsJob[" + target + "]";
	}
}
