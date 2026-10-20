/*
 * Copyright 2026 Michael Murray
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

import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.ResamplingConfig;
import org.almostrealism.ml.SAMEResamplingTestBase;
import org.almostrealism.ml.StateDictionary;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds a small {@link SAMEAutoEncoder} over synthetic weights: two audio channels folded two
 * samples per frame, stride-two resampling blocks of two layers and a three-channel latent, so the
 * downsampling ratio is four and audio lengths align to sixteen samples.
 */
public class SAMEAutoEncoderFixture implements CodeFeatures {

	/** Audio channels. */
	public static final int CHANNELS = 2;

	/** Samples folded per frame. */
	public static final int PATCH = 2;

	/** Latent channels. */
	public static final int LATENT = 3;

	/**
	 * The resampling configuration of one side of the small autoencoder: the same small
	 * configuration the standalone resampling tests use, so both exercise one set of dimensions.
	 *
	 * @param encoder whether to configure the encoder (downsampling) or the decoder side
	 * @return the configuration
	 */
	public ResamplingConfig config(boolean encoder) {
		return SAMEResamplingTestBase.smallConfig(encoder);
	}

	/**
	 * Synthetic weights for both resampling blocks, the latent projections and the bottleneck.
	 *
	 * @return the weights
	 */
	public StateDictionary weights() {
		Map<String, PackedCollection> w = new HashMap<>();
		config(true).weightShapes("encoder.layers.0").forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));
		config(false).weightShapes("decoder.layers.3").forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));

		int dim = config(true).getOutChannels();
		w.put("encoder.layers.2.weight", new PackedCollection(shape(LATENT, dim)).randnFill());
		w.put("encoder.layers.2.bias", new PackedCollection(shape(LATENT)).randnFill());
		w.put("decoder.layers.1.weight", new PackedCollection(shape(dim, LATENT)).randnFill());
		w.put("decoder.layers.1.bias", new PackedCollection(shape(dim)).randnFill());
		w.put("bottleneck.scaling_factor", new PackedCollection(shape(1, LATENT, 1)).randnFill());
		w.put("bottleneck.bias", new PackedCollection(shape(1, LATENT, 1)).randnFill());
		w.put("bottleneck.running_std", new PackedCollection(shape(1)).fill(1.5));
		return new StateDictionary(w);
	}

	/**
	 * The small autoencoder over the given weights.
	 *
	 * @param weights weights in the layout of {@link #weights()}
	 * @return the autoencoder
	 */
	public SAMEAutoEncoder autoencoder(StateDictionary weights) {
		return new SAMEAutoEncoder(weights, new PatchedPretransform(CHANNELS, PATCH),
				config(true), config(false), LATENT,
				new SoftNormBottleneck(weights, LATENT));
	}

	/**
	 * The small autoencoder over fresh synthetic weights.
	 *
	 * @return the autoencoder
	 */
	public SAMEAutoEncoder autoencoder() {
		return autoencoder(weights());
	}
}
