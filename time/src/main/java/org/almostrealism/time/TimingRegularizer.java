/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.time;

import java.util.LinkedList;
import java.util.Queue;

/**
 * A timing utility that tracks recent execution durations and calculates differences
 * from a standard target duration, enabling synchronization and tempo adjustment for
 * real-time systems.
 *
 * <p>{@link TimingRegularizer} maintains a rolling window of the most recent execution
 * durations and computes their average, allowing systems to adjust timing to compensate
 * for drift, jitter, or external process variability.</p>
 *
 * <h2>Purpose</h2>
 * <p>This class is designed to help real-time systems maintain consistent timing by:</p>
 * <ul>
 *   <li><strong>Measuring drift:</strong> Compare actual execution time vs target</li>
 *   <li><strong>Smoothing jitter:</strong> Average recent measurements to reduce variance</li>
 *   <li><strong>Calculating compensation:</strong> Provide adjustment values for timing loops</li>
 *   <li><strong>Synchronizing processes:</strong> Align internal tempo with external clocks</li>
 * </ul>
 *
 * <h2>Rolling Window Approach</h2>
 * <p>The regularizer maintains a queue of the 3 most recent duration measurements.
 * This provides:</p>
 * <ul>
 *   <li>Responsiveness to tempo changes (small window)</li>
 *   <li>Smoothing of transient spikes/dips (averaging)</li>
 *   <li>Minimal memory overhead (fixed size)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Audio Synchronization</h3>
 * <pre>{@code
 * // Target: 512 samples at 44.1kHz = 11.6ms = 11,600,000ns
 * long targetDuration = 11_600_000L;  // nanoseconds
 * TimingRegularizer regularizer = new TimingRegularizer(targetDuration);
 *
 * while (running) {
 *     long start = System.nanoTime();
 *
 *     // Process audio buffer
 *     processAudio();
 *
 *     long elapsed = System.nanoTime() - start;
 *     regularizer.addMeasuredDuration(elapsed);
 *
 *     // Calculate how much to adjust timing
 *     long difference = regularizer.getTimingDifference();
 *     if (difference > 0) {
 *         // Running fast, sleep a bit
 *         Thread.sleep(difference / 1_000_000);  // Convert to ms
 *     }
 * }
 * }</pre>
 *
 * <h3>Tempo Synchronization</h3>
 * <pre>{@code
 * // Sync with external MIDI clock
 * Frequency bpm = Frequency.forBPM(120.0);  // 120 BPM
 * long beatDuration = (long) (bpm.getWaveLength() * 1e9);  // In nanoseconds
 *
 * TimingRegularizer regularizer = new TimingRegularizer(beatDuration);
 *
 * while (receiving) {
 *     long beatStart = System.nanoTime();
 *
 *     // Wait for external MIDI clock tick
 *     waitForMidiClock();
 *
 *     long actualBeatDuration = System.nanoTime() - beatStart;
 *     regularizer.addMeasuredDuration(actualBeatDuration);
 *
 *     // Check if tempo is drifting
 *     long drift = regularizer.getTimingDifference();
 *     if (Math.abs(drift) > 1_000_000) {  // > 1ms
 *         System.out.println("Tempo drift: " + (drift / 1e6) + "ms");
 *     }
 * }
 * }</pre>
 *
 * <h3>Frame Rate Stabilization</h3>
 * <pre>{@code
 * // Target 60 FPS = 16.67ms per frame
 * TimingRegularizer regularizer = new TimingRegularizer(16_666_667L);
 *
 * while (rendering) {
 *     long frameStart = System.nanoTime();
 *
 *     renderFrame();
 *
 *     long frameTime = System.nanoTime() - frameStart;
 *     regularizer.addMeasuredDuration(frameTime);
 *
 *     long avgFrameTime = regularizer.getAverageDuration();
 *     double actualFPS = 1e9 / avgFrameTime;
 *     System.out.println("FPS: " + actualFPS);
 * }
 * }</pre>
 *
 * <h2>Timing Calculations</h2>
 *
 * <h3>Average Duration</h3>
 * <pre>
 * avgDuration = (duration1 + duration2 + duration3) / 3
 *
 * Special case (no measurements yet):
 * avgDuration = standardDuration / 2
 * </pre>
 *
 * <h3>Timing Difference</h3>
 * <pre>
 * difference = standardDuration - avgDuration
 *
 * Interpretation:
 * - Positive: Running faster than target (sleep/delay needed)
 * - Negative: Running slower than target (speedup needed)
 * - Zero: Perfect synchronization
 * </pre>
 *
 * <h2>Common Use Cases</h2>
 *
 * <h3>Audio Processing</h3>
 * <ul>
 *   <li>Synchronize buffer generation with playback rate</li>
 *   <li>Compensate for OS scheduling jitter</li>
 *   <li>Match external audio hardware timing</li>
 * </ul>
 *
 * <h3>MIDI Synchronization</h3>
 * <ul>
 *   <li>Track external MIDI clock tempo</li>
 *   <li>Detect tempo drift in real-time</li>
 *   <li>Adjust internal sequencer timing</li>
 * </ul>
 *
 * <h3>Animation and Rendering</h3>
 * <ul>
 *   <li>Maintain consistent frame rates</li>
 *   <li>Measure rendering performance</li>
 *   <li>Adapt quality based on timing</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>addMeasuredDuration():</strong> O(1) - Simple queue operation</li>
 *   <li><strong>getAverageDuration():</strong> O(1) - Iterates fixed 3 elements</li>
 *   <li><strong>getTimingDifference():</strong> O(1) - Simple arithmetic</li>
 *   <li><strong>Memory:</strong> Fixed overhead (3 long values + queue overhead)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link TimingRegularizer} is NOT thread-safe. If multiple threads need to measure
 * timing, either:</p>
 * <ul>
 *   <li>Use external synchronization</li>
 *   <li>Create separate regularizer instances per thread</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Small window (3 samples) may not smooth very noisy timing</li>
 *   <li>Cannot detect systematic bias in standard duration</li>
 *   <li>No outlier rejection for anomalous measurements</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class TimingRegularizer {
	private final long standardDuration;
	private final Queue<Long> recentDurations;
	private final int maxRecentDurations = 3;

	/**
	 * Initializes the {@link TimingRegularizer} with the standard duration.
	 *
	 * @param standardDuration the standard duration in nanoseconds
	 */
	public TimingRegularizer(long standardDuration) {
		this.standardDuration = standardDuration;
		this.recentDurations = new LinkedList<>();
	}

	/**
	 * Adds a measured duration to the record.
	 *
	 * @param measuredDuration the duration of the external process in nanoseconds
	 */
	public void addMeasuredDuration(long measuredDuration) {
		if (recentDurations.size() == maxRecentDurations) {
			recentDurations.poll(); // Remove the oldest duration
		}
		recentDurations.add(measuredDuration);
	}

	/**
	 * Returns the average of recent measured durations.
	 *
	 * <p>This method calculates the arithmetic mean of the most recent durations
	 * (up to 3). If no measurements have been recorded yet, returns half the
	 * standard duration as a conservative estimate.</p>
	 *
	 * <h3>Calculation</h3>
	 * <pre>
	 * With measurements: avg = sum(recent durations) / count
	 * Without measurements: avg = standardDuration / 2
	 * </pre>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * TimingRegularizer reg = new TimingRegularizer(10_000_000L);  // 10ms
	 *
	 * // No measurements yet
	 * System.out.println(reg.getAverageDuration());  // 5,000,000ns (half)
	 *
	 * // Add measurements
	 * reg.addMeasuredDuration(9_000_000L);   // 9ms
	 * reg.addMeasuredDuration(11_000_000L);  // 11ms
	 * reg.addMeasuredDuration(10_000_000L);  // 10ms
	 *
	 * System.out.println(reg.getAverageDuration());  // 10,000,000ns (average)
	 * }</pre>
	 *
	 * @return The average duration in nanoseconds, or half the standard if no data
	 */
	public long getAverageDuration() {
		if (recentDurations.isEmpty()) {
			// No measurements yet, assume the midpoint
			// between zero and the standard
			return standardDuration / 2;
		}

		// Calculate the average of recent durations
		long sum = 0;
		for (long duration : recentDurations) {
			sum += duration;
		}

		return sum / recentDurations.size();
	}

	/**
	 * Calculates the difference between the standard duration and the average
	 * of the 3 most recent measured durations.
	 *
	 * @return the difference in nanoseconds
	 */
	public long getTimingDifference() {
		return standardDuration - getAverageDuration();
	}
}
