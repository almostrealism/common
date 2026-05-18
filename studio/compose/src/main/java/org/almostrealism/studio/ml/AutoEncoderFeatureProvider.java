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

package org.almostrealism.studio.ml;
import org.almostrealism.ml.audio.AutoEncoder;

import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataFeatureProvider;
import org.almostrealism.collect.PackedCollection;

/**
 * Computes audio features by encoding wave data through an {@link AutoEncoder},
 * producing a transposed latent representation for use in similarity analysis.
 */
public class AutoEncoderFeatureProvider implements WaveDataFeatureProvider, CodeFeatures {
	/** The autoencoder used to encode audio into latent feature vectors. */
	private final AutoEncoder autoencoder;

	/**
	 * Creates a feature provider backed by the given autoencoder.
	 *
	 * @param autoencoder the autoencoder to use for feature extraction
	 */
	public AutoEncoderFeatureProvider(AutoEncoder autoencoder) {
		this.autoencoder = autoencoder;
	}

	public AutoEncoder getAutoEncoder() { return autoencoder; }

	@Override
	public PackedCollection computeFeatures(WaveData waveData) {
		PackedCollection features = autoencoder.encode(cp(waveData.getData())).evaluate();
		int bins = features.getShape().length(1);
		int frames = features.getShape().length(2);
		return cp(features.reshape(bins, frames)).transpose().evaluate();
	}

	@Override
	public int getAudioSampleRate() {
		return (int) autoencoder.getSampleRate();
	}

	@Override
	public double getFeatureSampleRate() {
		return autoencoder.getLatentSampleRate();
	}
}
