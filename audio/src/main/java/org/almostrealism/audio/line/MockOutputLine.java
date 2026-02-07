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

package org.almostrealism.audio.line;

import org.almostrealism.collect.PackedCollection;

/**
 * A mock implementation of {@link OutputLine} for testing audio pipelines
 * without requiring hardware audio output. This is useful for:
 * <ul>
 *   <li>Unit testing audio processing chains</li>
 *   <li>Running tests in headless environments (CI/CD, Docker containers)</li>
 *   <li>Benchmarking DSP performance without I/O overhead</li>
 * </ul>
 * <p>
 * The mock tracks the number of frames written and simulates read position
 * advancement based on elapsed time since start, allowing realistic testing
 * of {@link BufferedOutputScheduler} timing logic.
 * </p>
 *
 * @see BufferOutputLine for capturing actual audio data
 * @see BufferedOutputScheduler for scheduled output management
 */
public class MockOutputLine implements OutputLine {
	private final int bufferSize;
	private final int sampleRate;

	private long framesWritten;
	private long startTimeNanos;
	private boolean active;

	/**
	 * Creates a MockOutputLine with default buffer size and sample rate.
	 */
	public MockOutputLine() {
		this(BufferDefaults.defaultBufferSize, OutputLine.sampleRate);
	}

	/**
	 * Creates a MockOutputLine with the specified buffer size and default sample rate.
	 *
	 * @param bufferSize The buffer size in frames
	 */
	public MockOutputLine(int bufferSize) {
		this(bufferSize, OutputLine.sampleRate);
	}

	/**
	 * Creates a MockOutputLine with the specified buffer size and sample rate.
	 *
	 * @param bufferSize The buffer size in frames
	 * @param sampleRate The sample rate in Hz
	 */
	public MockOutputLine(int bufferSize, int sampleRate) {
		this.bufferSize = bufferSize;
		this.sampleRate = sampleRate;
		this.framesWritten = 0;
		this.startTimeNanos = 0;
		this.active = false;
	}

	@Override
	public void write(PackedCollection sample) {
		int frameCount = LineUtilities.frameCount(sample);
		framesWritten += frameCount;
	}

	/**
	 * Returns a simulated read position based on elapsed time since {@link #start()}.
	 * This allows testing of buffer management logic without real hardware.
	 * <p>
	 * The simulation assumes audio plays back at the configured sample rate,
	 * so the read position advances proportionally to elapsed wall-clock time.
	 * </p>
	 *
	 * @return The simulated read position in frames, modulo buffer size
	 */
	@Override
	public int getReadPosition() {
		if (!active || startTimeNanos == 0) {
			return 0;
		}

		long elapsedNanos = System.nanoTime() - startTimeNanos;
		double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
		long simulatedFramesRead = (long) (elapsedSeconds * sampleRate);

		return (int) (simulatedFramesRead % bufferSize);
	}

	@Override
	public int getBufferSize() {
		return bufferSize;
	}

	@Override
	public int getSampleRate() {
		return sampleRate;
	}

	@Override
	public void start() {
		if (!active) {
			startTimeNanos = System.nanoTime();
			active = true;
		}
	}

	@Override
	public void stop() {
		active = false;
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public void reset() {
		framesWritten = 0;
		startTimeNanos = System.nanoTime();
	}

	@Override
	public void destroy() {
		active = false;
		framesWritten = 0;
		startTimeNanos = 0;
	}

	/**
	 * Returns the total number of frames written to this mock output.
	 *
	 * @return The total frames written
	 */
	public long getFramesWritten() {
		return framesWritten;
	}

	/**
	 * Returns the total duration of audio written in seconds.
	 *
	 * @return The duration in seconds
	 */
	public double getDurationWritten() {
		return framesWritten / (double) sampleRate;
	}

	/**
	 * Resets the frame counter to zero without affecting the active state.
	 */
	public void resetFrameCount() {
		framesWritten = 0;
	}
}
