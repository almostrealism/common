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

package org.almostrealism.ml.audio;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Interface for audio autoencoders that compress and reconstruct audio signals.
 *
 * <p>Implementations encode raw audio into a compact latent representation and
 * decode latents back to audio. The compression ratio can be derived from the
 * ratio of {@link #getSampleRate()} to {@link #getLatentSampleRate()}.</p>
 *
 * @see OobleckAutoEncoder
 */
public interface AutoEncoder extends Destroyable {

	/**
	 * Returns the sample rate of the raw audio in Hz.
	 *
	 * @return audio sample rate in Hz
	 */
	double getSampleRate();

	/**
	 * Returns the sample rate of the latent representation in Hz.
	 *
	 * @return latent sample rate in Hz
	 */
	double getLatentSampleRate();

	/**
	 * Returns the maximum audio duration this autoencoder can process in seconds.
	 *
	 * @return maximum supported audio duration in seconds
	 */
	double getMaximumDuration();

	/**
	 * Encodes raw audio into a latent representation.
	 *
	 * @param input producer for the raw audio tensor
	 * @return producer for the compressed latent tensor
	 */
	Producer<PackedCollection> encode(Producer<PackedCollection> input);

	/**
	 * Decodes a latent representation back to audio.
	 *
	 * @param latent producer for the latent tensor
	 * @return producer for the reconstructed audio tensor
	 */
	Producer<PackedCollection> decode(Producer<PackedCollection> latent);
}
