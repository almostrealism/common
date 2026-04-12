/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.data;

import org.almostrealism.collect.PackedCollection;

/**
 * Provider of feature vectors computed from audio data.
 *
 * <p>WaveDataFeatureProvider defines an interface for extracting feature representations
 * from audio data, typically used for audio similarity comparison, classification,
 * or machine learning applications. Features might include spectral characteristics,
 * MFCCs, or other audio descriptors.</p>
 *
 * @see WaveDetails
 * @see WaveDetailsFactory
 */
public interface WaveDataFeatureProvider {
	/**
	 * Computes feature vectors for the given wave data provider, resampling to the expected audio sample rate.
	 *
	 * @param provider the audio provider to extract features from
	 * @return feature vector as a PackedCollection
	 */
	default PackedCollection computeFeatures(WaveDataProvider provider) {
		return computeFeatures(provider.get(getAudioSampleRate()));
	}

	/**
	 * Computes feature vectors directly from the given WaveData.
	 *
	 * @param waveData the audio data to extract features from
	 * @return feature vector as a PackedCollection
	 */
	PackedCollection computeFeatures(WaveData waveData);

	/**
	 * Returns the expected audio sample rate in Hz for input to this feature provider.
	 *
	 * @return required audio sample rate in Hz
	 */
	int getAudioSampleRate();

	/**
	 * Returns the sample rate of the output feature vectors in frames per second.
	 *
	 * @return feature sample rate in frames per second
	 */
	double getFeatureSampleRate();
}
