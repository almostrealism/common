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

package org.almostrealism.audio.sources;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;

/**
 * Describes audio buffer metadata including sample rate and frame count.
 * Provides utility methods for converting between frames and duration,
 * and for creating WaveData instances with the appropriate dimensions.
 * This class serves as a configuration object for audio processing operations.
 *
 * @see AudioBuffer
 * @see WaveData
 */
public class BufferDetails {
	/** Audio sample rate in Hz (e.g., 44100). */
	private final int sampleRate;

	/** Number of audio frames in the buffer. */
	private final int frames;

	/**
	 * Creates buffer details with the specified sample rate and frame count.
	 *
	 * @param sampleRate audio sample rate in Hz
	 * @param frames     number of audio frames
	 */
	public BufferDetails(int sampleRate, int frames) {
		this.sampleRate = sampleRate;
		this.frames = frames;
	}

	/**
	 * Creates buffer details with the specified sample rate and duration.
	 * The frame count is computed as {@code (int)(duration * sampleRate)}.
	 *
	 * @param sampleRate audio sample rate in Hz
	 * @param duration   buffer duration in seconds
	 */
	public BufferDetails(int sampleRate, double duration) {
		this.sampleRate = sampleRate;
		this.frames = (int) (duration * sampleRate);
	}

	/**
	 * Returns the audio sample rate in Hz.
	 *
	 * @return sample rate in Hz
	 */
	public int getSampleRate() {
		return sampleRate;
	}

	/**
	 * Returns the number of audio frames in the buffer.
	 *
	 * @return frame count
	 */
	public int getFrames() {
		return frames;
	}

	/**
	 * Returns the buffer duration in seconds, computed as frames divided by sample rate.
	 *
	 * @return duration in seconds
	 */
	public double getDuration() {
		return (double) frames / sampleRate;
	}

	/**
	 * Creates a new {@link WaveData} instance with the dimensions described by this object.
	 * The underlying storage is a freshly allocated {@link PackedCollection}.
	 *
	 * @return a new WaveData with the configured frame count and sample rate
	 */
	public WaveData createWaveData() {
		return new WaveData(new PackedCollection(getFrames()).traverseEach(), getSampleRate());
	}
}
