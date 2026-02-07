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

package org.almostrealism.audio.pattern.test;

import java.util.List;

/**
 * Timing statistics for real-time audio rendering performance analysis.
 *
 * <p>Captures per-buffer timing measurements to evaluate whether the
 * rendering pipeline can meet real-time constraints.</p>
 *
 * <h2>Real-Time Constraint</h2>
 *
 * <p>For real-time audio playback, each buffer must be rendered faster
 * than its playback duration. At 44.1kHz with a 1024-frame buffer:</p>
 * <ul>
 *   <li>Buffer duration = 1024 / 44100 = 23.2 ms</li>
 *   <li>Render time must be &lt; 23.2 ms per buffer</li>
 * </ul>
 *
 * <h2>Performance Metrics</h2>
 *
 * <table border="1">
 *   <tr><th>Metric</th><th>Meaning</th></tr>
 *   <tr><td>realTimeRatio</td><td>&gt;1 means faster than real-time</td></tr>
 *   <tr><td>overrunCount</td><td>Buffers that exceeded target time</td></tr>
 *   <tr><td>headroomMs</td><td>Spare time per buffer (target - actual)</td></tr>
 * </table>
 *
 * @see RealTimeTestHelper#renderRealTime
 */
public class TimingStats {

	private final List<Long> bufferTimingsNanos;
	private final double targetBufferMs;
	private final long totalTimeNanos;

	// Computed values
	private final double avgBufferMs;
	private final double minBufferMs;
	private final double maxBufferMs;
	private final long overrunCount;

	/**
	 * Creates timing statistics from per-buffer measurements.
	 *
	 * @param bufferTimingsNanos per-buffer render times in nanoseconds
	 * @param targetBufferMs     target buffer duration in milliseconds
	 * @param totalTimeNanos     total render time in nanoseconds
	 */
	public TimingStats(List<Long> bufferTimingsNanos, double targetBufferMs, long totalTimeNanos) {
		this.bufferTimingsNanos = bufferTimingsNanos;
		this.targetBufferMs = targetBufferMs;
		this.totalTimeNanos = totalTimeNanos;

		this.avgBufferMs = bufferTimingsNanos.stream()
				.mapToLong(Long::longValue)
				.average()
				.orElse(0) / 1_000_000.0;

		this.minBufferMs = bufferTimingsNanos.stream()
				.mapToLong(Long::longValue)
				.min()
				.orElse(0) / 1_000_000.0;

		this.maxBufferMs = bufferTimingsNanos.stream()
				.mapToLong(Long::longValue)
				.max()
				.orElse(0) / 1_000_000.0;

		this.overrunCount = bufferTimingsNanos.stream()
				.filter(t -> t / 1_000_000.0 > targetBufferMs)
				.count();
	}

	/**
	 * Returns the target buffer duration in milliseconds.
	 */
	public double targetBufferMs() {
		return targetBufferMs;
	}

	/**
	 * Returns the average buffer render time in milliseconds.
	 */
	public double avgBufferMs() {
		return avgBufferMs;
	}

	/**
	 * Returns the minimum buffer render time in milliseconds.
	 */
	public double minBufferMs() {
		return minBufferMs;
	}

	/**
	 * Returns the maximum buffer render time in milliseconds.
	 */
	public double maxBufferMs() {
		return maxBufferMs;
	}

	/**
	 * Returns the total render time in milliseconds.
	 */
	public double totalTimeMs() {
		return totalTimeNanos / 1_000_000.0;
	}

	/**
	 * Returns the real-time ratio (target time / actual time).
	 *
	 * <p>Values &gt; 1 indicate faster than real-time rendering.
	 * For example, 2.0 means rendering is twice as fast as needed.</p>
	 */
	public double realTimeRatio() {
		return avgBufferMs > 0 ? targetBufferMs / avgBufferMs : 0;
	}

	/**
	 * Returns the number of buffers that exceeded the target time.
	 */
	public long overrunCount() {
		return overrunCount;
	}

	/**
	 * Returns the overrun ratio (overruns / total buffers).
	 */
	public double overrunRatio() {
		return bufferTimingsNanos.isEmpty() ? 0 :
				(double) overrunCount / bufferTimingsNanos.size();
	}

	/**
	 * Returns the average headroom in milliseconds (target - actual).
	 *
	 * <p>Positive values indicate spare time; negative values indicate
	 * the rendering is too slow for real-time.</p>
	 */
	public double headroomMs() {
		return targetBufferMs - avgBufferMs;
	}

	/**
	 * Returns true if average render time is below target.
	 *
	 * <p><b>Performance property:</b> This is the primary indicator of
	 * whether the rendering can sustain real-time playback.</p>
	 */
	public boolean meetsRealTime() {
		return avgBufferMs < targetBufferMs;
	}

	/**
	 * Returns true if no buffers exceeded the target time.
	 *
	 * <p>This is a stricter requirement than {@link #meetsRealTime()}.
	 * Even if the average is below target, occasional overruns can
	 * cause audio glitches in real-time playback.</p>
	 */
	public boolean hasNoOverruns() {
		return overrunCount == 0;
	}

	/**
	 * Returns a human-readable summary of the timing statistics.
	 */
	@Override
	public String toString() {
		return String.format(
				"TimingStats[target=%.2fms, avg=%.2fms, min=%.2fms, max=%.2fms, " +
						"ratio=%.2fx, overruns=%d/%d, meetsRealTime=%s]",
				targetBufferMs, avgBufferMs, minBufferMs, maxBufferMs,
				realTimeRatio(), overrunCount, bufferTimingsNanos.size(),
				meetsRealTime() ? "YES" : "NO");
	}
}
