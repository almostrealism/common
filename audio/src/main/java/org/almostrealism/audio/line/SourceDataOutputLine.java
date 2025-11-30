/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.line;

import org.almostrealism.collect.PackedCollection;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

/**
 * An {@link OutputLine} implementation that wraps a Java Sound API {@link SourceDataLine}
 * for real-time audio playback through system audio devices (speakers, headphones).
 * <p>
 * This class bridges the Rings audio framework with the Java Sound API, converting
 * {@link PackedCollection} audio data to byte arrays in the appropriate format
 * and writing them to the hardware audio line.
 * <p>
 * Usage example:
 * <pre>
 * OutputLine line = LineUtilities.getLine(); // Creates a SourceDataOutputLine
 * PackedCollection&lt;?&gt; audioData = ... // Your audio data
 * line.write(audioData);
 * </pre>
 *
 * @see LineUtilities#getLine() for creating instances with default format
 */
public class SourceDataOutputLine implements OutputLine {
	private SourceDataLine line;
	private final int bufferSize;

	/**
	 * Creates a new SourceDataOutputLine wrapping the specified Java Sound API line.
	 * The line should already be opened and started before being passed to this constructor.
	 *
	 * @param line The underlying SourceDataLine for audio playback
	 */
	public SourceDataOutputLine(SourceDataLine line) {
		this(line, 1024);
	}

	/**
	 * Creates a new SourceDataOutputLine wrapping the specified Java Sound API line
	 * with a specified buffer size.
	 *
	 * @param line The underlying SourceDataLine for audio playback
	 * @param bufferSize The buffer size in frames
	 */
	public SourceDataOutputLine(SourceDataLine line, int bufferSize) {
		this.line = line;
		this.bufferSize = bufferSize;
	}

	/**
	 * Returns the underlying Java Sound API SourceDataLine.
	 *
	 * @return The wrapped SourceDataLine
	 */
	public SourceDataLine getDataLine() {
		return line;
	}

	/**
	 * Writes audio samples from a {@link PackedCollection} to the hardware line.
	 * The collection is converted to the appropriate byte format using
	 * {@link LineUtilities#toFrame(PackedCollection, AudioFormat)} and written
	 * directly to the underlying SourceDataLine.
	 * <p>
	 * This method is designed to work with {@link BufferedOutputScheduler} which
	 * provides the Producer&lt;PackedCollection&lt;?&gt;&gt; source for buffered,
	 * scheduled audio playback.
	 *
	 * @param sample The audio sample as a PackedCollection (interleaved channels)
	 */
	@Override
	public void write(PackedCollection sample) {
		byte[] bytes = LineUtilities.toFrame(sample, line.getFormat());
		line.write(bytes, 0, bytes.length);
	}

	/**
	 * Returns the current playback position by querying the hardware line.
	 * This is used by {@link BufferedOutputScheduler} to track playback progress
	 * and prevent buffer underruns.
	 *
	 * @return The read position in frames, modulo the buffer size
	 */
	@Override
	public int getReadPosition() {
		if (line == null) return 0;
		// Get the frame position from the hardware line and wrap to buffer size
		long framePosition = line.getLongFramePosition();
		return (int) (framePosition % bufferSize);
	}

	/**
	 * Returns the configured buffer size in frames.
	 *
	 * @return The buffer size in frames
	 */
	@Override
	public int getBufferSize() {
		return bufferSize;
	}

	/**
	 * Cleans up resources by draining any remaining audio, stopping and closing the line.
	 * This method should be called when the output line is no longer needed to prevent
	 * resource leaks.
	 */
	@Override
	public void destroy() {
		if (line != null) {
			if (line.isActive()) {
				line.drain(); // Let queued audio finish playing
				line.stop();
			}
			if (line.isOpen()) {
				line.close();
			}
			line = null;
		}
	}

	/**
	 * Starts the audio line if not already started.
	 * Audio data written to the line will begin playing after this is called.
	 */
	public void start() {
		if (line != null && !line.isActive()) {
			line.start();
		}
	}

	/**
	 * Stops the audio line, pausing playback.
	 * The line can be restarted with {@link #start()}.
	 */
	public void stop() {
		if (line != null && line.isActive()) {
			line.stop();
		}
	}

	/**
	 * Returns whether the line is currently active (playing or ready to play).
	 *
	 * @return true if the line is active, false otherwise
	 */
	public boolean isActive() {
		return line != null && line.isActive();
	}

	/**
	 * Returns whether the line is currently open.
	 *
	 * @return true if the line is open, false otherwise
	 */
	public boolean isOpen() {
		return line != null && line.isOpen();
	}
}
