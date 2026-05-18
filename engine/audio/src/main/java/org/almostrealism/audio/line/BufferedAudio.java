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

import org.almostrealism.io.SystemUtils;

/**
 * Defines audio buffering characteristics including sample rate and buffer size.
 * This interface provides the foundational parameters needed for audio processing
 * and I/O operations.
 */
public interface BufferedAudio {
	/**
	 * Default sample rate in Hz, configurable via AR_AUDIO_SAMPLE_RATE system property.
	 * Defaults to 44100 Hz (CD quality).
	 */
	int sampleRate = SystemUtils.getInt("AR_AUDIO_SAMPLE_RATE").orElse(44100);

	/**
	 * Returns the sample rate in Hz (samples per second).
	 * @return The sample rate, typically 44100 Hz
	 */
	default int getSampleRate() { return sampleRate; }

	/**
	 * Returns the buffer size in frames. This determines how many frames
	 * are processed in a single batch operation.
	 * @return The buffer size in frames, default is 1024
	 */
	default int getBufferSize() { return 1024; }
}
