/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated profiling data for multiple execution runs of a hardware operation.
 *
 * <p>Collects timing measurements from repeated executions to calculate average runtime,
 * total runtime, and execution counts. Used by {@link org.almostrealism.hardware.OperationProfile}
 * to track performance of {@link org.almostrealism.hardware.HardwareOperator} instances.</p>
 *
 * <h2>Metrics Tracked</h2>
 *
 * <ul>
 *   <li><strong>Total Runs:</strong> Number of executions recorded</li>
 *   <li><strong>Total Runtime:</strong> Sum of all execution durations (nanoseconds)</li>
 *   <li><strong>Average Runtime:</strong> Mean execution time per run (nanoseconds)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * ProfileData profile = new ProfileData();
 *
 * // Record multiple runs
 * for (int i = 0; i < 1000; i++) {
 *     long start = System.nanoTime();
 *     operation.run();
 *     long duration = System.nanoTime() - start;
 *     profile.addRun(new RunData(duration));
 * }
 *
 * // Analyze performance
 * System.out.println(profile.getSummaryString());
 * // Output: "5234.5 nanoseconds average - 1000 executions for 5.2345 seconds total"
 * }</pre>
 *
 * <h2>Typical Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Performance testing:</strong> Measure operation throughput over time</li>
 *   <li><strong>Warmup detection:</strong> Identify JIT compilation effects</li>
 *   <li><strong>Comparison:</strong> Compare GPU vs CPU execution times</li>
 *   <li><strong>Regression testing:</strong> Detect performance degradation</li>
 * </ul>
 *
 * @see RunData
 * @see org.almostrealism.hardware.OperationProfile
 * @see org.almostrealism.hardware.HardwareOperator
 */
public class ProfileData {
	private List<RunData> runs;

	/**
	 * Creates a new profile data collector with no recorded runs.
	 */
	public ProfileData() {
		this.runs = new ArrayList<>();
	}

	/**
	 * Adds a single run measurement to the profile.
	 *
	 * @param run The run data containing execution duration
	 */
	public void addRun(RunData run) { runs.add(run); }

	/**
	 * Returns the total number of recorded execution runs.
	 *
	 * @return Count of runs added to this profile
	 */
	public int getTotalRuns() { return runs.size(); }

	/**
	 * Returns the average execution time across all runs.
	 *
	 * @return Average runtime in nanoseconds
	 */
	public double getAverageRuntimeNanos() {
		return getTotalRuntimeNanos() / runs.size();
	}

	/**
	 * Returns the sum of all execution times.
	 *
	 * @return Total runtime in nanoseconds across all runs
	 */
	public double getTotalRuntimeNanos() {
		return runs.stream().mapToDouble(RunData::getDurationNanos).sum();
	}

	/**
	 * Returns a human-readable summary of the profiling data.
	 *
	 * <p>Format: "{average} nanoseconds average - {count} executions for {total} seconds total"</p>
	 *
	 * @return Summary string with average time, execution count, and total time
	 */
	public String getSummaryString() {
		int count = runs.size();
		double totalSeconds = getTotalRuntimeNanos() * Math.pow(10, -6);
		double averageNanos = getAverageRuntimeNanos();
		return averageNanos + " nanoseconds average - " + count + " executions for " + totalSeconds + " seconds total";
	}
}
