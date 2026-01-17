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
 * An {@link OutputLine} implementation that captures audio output to a
 * {@link PackedCollection} buffer for later analysis and verification.
 * This is useful for:
 * <ul>
 *   <li>Testing audio processing correctness by examining actual output samples</li>
 *   <li>Verifying waveform characteristics (frequency, amplitude, phase)</li>
 *   <li>Recording synthesizer output for offline analysis</li>
 *   <li>A/B comparison testing between implementations</li>
 * </ul>
 * <p>
 * The buffer operates in circular mode by default, wrapping around when full.
 * Use {@link #setCircular(boolean)} to disable wrapping if you want writes
 * to stop when the buffer is full.
 * </p>
 * <p>
 * Like {@link MockOutputLine}, this class simulates read position advancement
 * based on elapsed time to support realistic testing with {@link BufferedOutputScheduler}.
 * </p>
 *
 * @see MockOutputLine for testing without data capture
 * @see BufferedOutputScheduler for scheduled output management
 */
public class BufferOutputLine implements OutputLine {
	private final PackedCollection buffer;
	private final int bufferSize;
	private final int sampleRate;

	private int writePosition;
	private long totalFramesWritten;
	private long startTimeNanos;
	private boolean active;
	private boolean circular;

	/**
	 * Creates a BufferOutputLine with the specified capacity.
	 *
	 * @param capacityFrames The maximum number of frames the buffer can hold
	 */
	public BufferOutputLine(int capacityFrames) {
		this(capacityFrames, OutputLine.sampleRate);
	}

	/**
	 * Creates a BufferOutputLine with the specified capacity and sample rate.
	 *
	 * @param capacityFrames The maximum number of frames the buffer can hold
	 * @param sampleRate The sample rate in Hz
	 */
	public BufferOutputLine(int capacityFrames, int sampleRate) {
		this.buffer = new PackedCollection(capacityFrames);
		this.bufferSize = capacityFrames;
		this.sampleRate = sampleRate;
		this.writePosition = 0;
		this.totalFramesWritten = 0;
		this.startTimeNanos = 0;
		this.active = false;
		this.circular = true;
	}

	@Override
	public void write(PackedCollection sample) {
		int frameCount = LineUtilities.frameCount(sample);

		for (int i = 0; i < frameCount; i++) {
			if (writePosition >= bufferSize) {
				if (circular) {
					writePosition = 0;
				} else {
					break;
				}
			}

			buffer.setMem(writePosition, sample.toDouble(i));
			writePosition++;
			totalFramesWritten++;
		}
	}

	/**
	 * Returns a simulated read position based on elapsed time since {@link #start()}.
	 * This allows testing of buffer management logic without real hardware.
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
		writePosition = 0;
		totalFramesWritten = 0;
		startTimeNanos = System.nanoTime();
		buffer.fill(0.0);
	}

	@Override
	public void destroy() {
		active = false;
		writePosition = 0;
		totalFramesWritten = 0;
		startTimeNanos = 0;
	}

	/**
	 * Returns the captured audio buffer.
	 *
	 * @return The PackedCollection containing captured audio samples
	 */
	public PackedCollection getBuffer() {
		return buffer;
	}

	/**
	 * Returns a copy of the buffer containing only the written samples.
	 * If more frames have been written than the buffer size (due to circular
	 * wrapping), returns the full buffer.
	 *
	 * @return A PackedCollection containing the captured audio
	 */
	public PackedCollection getCapturedAudio() {
		int framesToCopy = (int) Math.min(totalFramesWritten, bufferSize);
		PackedCollection result = new PackedCollection(framesToCopy);

		for (int i = 0; i < framesToCopy; i++) {
			result.setMem(i, buffer.toDouble(i));
		}

		return result;
	}

	/**
	 * Returns the current write position in the buffer.
	 *
	 * @return The write position in frames
	 */
	public int getWritePosition() {
		return writePosition;
	}

	/**
	 * Returns the total number of frames written, which may exceed buffer size
	 * if circular mode is enabled.
	 *
	 * @return The total frames written
	 */
	public long getTotalFramesWritten() {
		return totalFramesWritten;
	}

	/**
	 * Returns the total duration of audio written in seconds.
	 *
	 * @return The duration in seconds
	 */
	public double getDurationWritten() {
		return totalFramesWritten / (double) sampleRate;
	}

	/**
	 * Returns whether circular buffer mode is enabled.
	 *
	 * @return true if writes wrap around when buffer is full
	 */
	public boolean isCircular() {
		return circular;
	}

	/**
	 * Sets whether the buffer operates in circular mode.
	 * In circular mode, writes wrap around to the beginning when the buffer
	 * is full. In non-circular mode, writes stop when the buffer is full.
	 *
	 * @param circular true to enable circular mode
	 */
	public void setCircular(boolean circular) {
		this.circular = circular;
	}

	/**
	 * Returns the sample value at the specified frame index.
	 *
	 * @param frame The frame index
	 * @return The sample value at that frame
	 */
	public double getSample(int frame) {
		return buffer.toDouble(frame);
	}

	/**
	 * Returns the peak absolute amplitude in the captured audio.
	 *
	 * @return The peak amplitude (0.0 to 1.0+ range)
	 */
	public double getPeakAmplitude() {
		double peak = 0.0;
		int framesToCheck = (int) Math.min(totalFramesWritten, bufferSize);

		for (int i = 0; i < framesToCheck; i++) {
			double abs = Math.abs(buffer.toDouble(i));
			if (abs > peak) {
				peak = abs;
			}
		}

		return peak;
	}

	/**
	 * Returns the RMS (root mean square) amplitude of the captured audio.
	 *
	 * @return The RMS amplitude
	 */
	public double getRmsAmplitude() {
		int framesToCheck = (int) Math.min(totalFramesWritten, bufferSize);
		if (framesToCheck == 0) return 0.0;

		double sumSquares = 0.0;
		for (int i = 0; i < framesToCheck; i++) {
			double sample = buffer.toDouble(i);
			sumSquares += sample * sample;
		}

		return Math.sqrt(sumSquares / framesToCheck);
	}

	/**
	 * Checks if the captured audio contains any non-zero samples.
	 *
	 * @return true if any sample is non-zero
	 */
	public boolean hasAudio() {
		int framesToCheck = (int) Math.min(totalFramesWritten, bufferSize);
		for (int i = 0; i < framesToCheck; i++) {
			if (buffer.toDouble(i) != 0.0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Counts zero crossings in the captured audio, which can be used
	 * to estimate frequency content.
	 *
	 * @return The number of zero crossings
	 */
	public int countZeroCrossings() {
		int framesToCheck = (int) Math.min(totalFramesWritten, bufferSize);
		if (framesToCheck < 2) return 0;

		int crossings = 0;
		double prevSample = buffer.toDouble(0);

		for (int i = 1; i < framesToCheck; i++) {
			double sample = buffer.toDouble(i);
			if ((prevSample >= 0 && sample < 0) || (prevSample < 0 && sample >= 0)) {
				crossings++;
			}
			prevSample = sample;
		}

		return crossings;
	}

	/**
	 * Estimates the fundamental frequency of the captured audio using
	 * zero-crossing analysis. This is a simple approximation suitable
	 * for testing periodic waveforms.
	 *
	 * @return The estimated frequency in Hz, or 0 if insufficient data
	 */
	public double estimateFrequency() {
		int crossings = countZeroCrossings();
		int framesToCheck = (int) Math.min(totalFramesWritten, bufferSize);

		if (crossings < 2 || framesToCheck < 2) return 0.0;

		double durationSeconds = framesToCheck / (double) sampleRate;
		double cyclesPerSecond = crossings / (2.0 * durationSeconds);

		return cyclesPerSecond;
	}
}
