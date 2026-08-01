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

package org.almostrealism.audio.sources;

import org.almostrealism.collect.PackedCollection;

/**
 * Encapsulates paired input and output audio buffers along with their metadata.
 * Provides a complete audio buffer context including sample rate, frame count,
 * and the actual audio data storage. This class ensures consistency between
 * buffer details and the actual PackedCollection dimensions.
 *
 * @see BufferDetails
 * @see PackedCollection
 */
public class AudioBuffer {
	/** Metadata describing the buffer's sample rate and frame count. */
	private final BufferDetails details;

	/** Input audio buffer holding the source samples. */
	private final PackedCollection input;

	/** Output audio buffer for writing processed samples. */
	private final PackedCollection output;

	/**
	 * Creates an AudioBuffer with the given details and pre-allocated buffers.
	 * The input and output buffer sizes must match the frame count in {@code details}.
	 *
	 * @param details metadata describing the buffer configuration
	 * @param input   input audio data buffer
	 * @param output  output audio data buffer
	 * @throws IllegalArgumentException if buffer sizes do not match the frame count in details
	 */
	public AudioBuffer(BufferDetails details,
					   PackedCollection input,
					   PackedCollection output) {
		this.details = details;
		this.input = input;
		this.output = output;

		if (details.getFrames() != output.getMemLength() ||
				details.getFrames() != input.getMemLength()) {
			throw new IllegalArgumentException();
		}
	}

	/** Returns the buffer metadata. */
	public BufferDetails getDetails() { return details; }

	/** Returns the input audio buffer. */
	public PackedCollection getInputBuffer() { return input; }

	/** Returns the output audio buffer. */
	public PackedCollection getOutputBuffer() { return output; }

	/**
	 * Creates a new AudioBuffer with freshly allocated input and output buffers.
	 *
	 * @param sampleRate audio sample rate in Hz
	 * @param frames     number of audio frames
	 * @return a new AudioBuffer with empty input and output buffers
	 */
	public static AudioBuffer create(int sampleRate, int frames) {
		return new AudioBuffer(
				new BufferDetails(sampleRate, frames),
				new PackedCollection(frames),
				new PackedCollection(frames));
	}
}
