/*
 * Copyright 2026 Michael Murray
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

/**
 * An {@link OutputLine} that has no underlying hardware to query and instead models
 * playback advancing in real time. The read position advances in proportion to the
 * wall-clock time elapsed since {@link #start()}, assuming playback at the configured
 * sample rate, and wraps within the buffer. This supports realistic testing of
 * {@link BufferedOutputScheduler} timing logic without a real audio device.
 * <p>
 * Subclasses supply the destination for written audio (and any analysis of it); this
 * class owns the buffer sizing, sample rate, and the active/started lifecycle that the
 * simulated read position is computed from.
 *
 * @see MockOutputLine for testing without data capture
 * @see BufferOutputLine for capturing actual audio data
 */
public abstract class SimulatedOutputLine implements OutputLine {
	/** Size of the simulated circular buffer in frames. */
	private final int bufferSize;

	/** Audio sample rate in Hz used for read position timing simulation. */
	private final int sampleRate;

	/** System nanosecond timestamp recorded when the line starts, or {@code 0} if not started. */
	private long startTimeNanos;

	/** True while the line is active (between {@link #start()} and {@link #stop()}). */
	private boolean active;

	/**
	 * Creates a simulated output line with the given buffer size and sample rate.
	 *
	 * @param bufferSize The buffer size in frames used to wrap the read position
	 * @param sampleRate The sample rate in Hz used for read position timing simulation
	 */
	protected SimulatedOutputLine(int bufferSize, int sampleRate) {
		this.bufferSize = bufferSize;
		this.sampleRate = sampleRate;
		this.startTimeNanos = 0;
		this.active = false;
	}

	/**
	 * Returns a simulated read position based on elapsed time since {@link #start()}.
	 * The simulation assumes audio plays back at the configured sample rate, so the
	 * read position advances proportionally to elapsed wall-clock time and wraps within
	 * the buffer.
	 * <p>
	 * Returns {@code 0} when the line is not active or has not been started, matching the
	 * "not tracking" convention of {@link OutputLine#getReadPosition()}.
	 *
	 * @return The simulated read position in frames, modulo buffer size, or {@code 0}
	 *         when the line is inactive or not started
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

	/**
	 * Restarts the read-position clock. Subclasses that override this to clear their own
	 * written data must call {@code super.reset()} to keep the simulated position consistent.
	 */
	@Override
	public void reset() {
		startTimeNanos = System.nanoTime();
	}

	/**
	 * Deactivates the line and clears the read-position clock. Subclasses that override this
	 * to release their own resources must call {@code super.destroy()}.
	 */
	@Override
	public void destroy() {
		active = false;
		startTimeNanos = 0;
	}
}
