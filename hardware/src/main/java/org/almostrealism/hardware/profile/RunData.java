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

/**
 * Single execution timing measurement for profiling hardware operations.
 *
 * <p>Captures the duration of a single operation execution in nanoseconds.
 * Multiple {@link RunData} instances are aggregated in {@link ProfileData}
 * to calculate statistical metrics like average runtime.</p>
 *
 * <h2>Precision</h2>
 *
 * <ul>
 *   <li><strong>Timing source:</strong> {@code System.nanoTime()}</li>
 *   <li><strong>Resolution:</strong> Platform-dependent (typically 1-100 nanoseconds)</li>
 *   <li><strong>Accuracy:</strong> Affected by JIT compilation, GC, system load</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * long start = System.nanoTime();
 * operation.run();
 * long duration = System.nanoTime() - start;
 * RunData run = new RunData(duration);
 * }</pre>
 *
 * <h2>Typical Values</h2>
 *
 * <ul>
 *   <li><strong>Simple GPU kernel:</strong> 10-100 microseconds (10,000-100,000 nanoseconds)</li>
 *   <li><strong>Complex GPU operation:</strong> 0.1-10 milliseconds (100,000-10,000,000 nanoseconds)</li>
 *   <li><strong>CPU operation:</strong> 1-100 microseconds (1,000-100,000 nanoseconds)</li>
 *   <li><strong>First call (compilation):</strong> 100-1000 milliseconds (100,000,000-1,000,000,000 nanoseconds)</li>
 * </ul>
 *
 * @see ProfileData
 * @see org.almostrealism.hardware.OperationProfile
 */
public class RunData {
	private long durationNanos;

	public RunData(long durationNanos) {
		setDurationNanos(durationNanos);
	}

	public long getDurationNanos() {
		return durationNanos;
	}

	public void setDurationNanos(long durationNanos) {
		this.durationNanos = durationNanos;
	}
}
