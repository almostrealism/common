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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.ResamplingConfig;
import org.almostrealism.ml.SAMEResamplingTestBase;
import org.almostrealism.ml.StateDictionary;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

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
	 * Base seed for the synthetic weights. Each weight is filled from a {@link Random} seeded by this
	 * value combined with the weight's key, so the autoencoder the fixture builds is the same on every
	 * run. An unseeded fill left the decoded audio to vary run to run, which made the tests that assert
	 * a decoded sample stays strictly inside the generator's {@code [-1, 1]} clamp flaky: a run whose
	 * random weights happened to decode a sample just past the clamp failed the precondition. A fixed
	 * seed removes that run-to-run variation.
	 */
	private static final long WEIGHT_SEED = 1927L;

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
		return weights(1.0);
	}

	/**
	 * Synthetic weights whose decoded audio is scaled by {@code outputGain}. Every normalization in
	 * the decoder's resampling block precedes the residual writes, so scaling the weights that write
	 * into the residual stream — the input mapping, the new tokens, and each layer's attention and
	 * feed-forward output projections — scales the decoded audio by the same factor. Unit-variance
	 * weights decode to samples in the hundreds, which the generator's clamp to {@code [-1, 1]}
	 * would flatten; a small gain keeps them inside it.
	 *
	 * @param outputGain the factor applied to the decoder's residual-writing weights
	 * @return the weights
	 */
	public StateDictionary weights(double outputGain) {
		Map<String, PackedCollection> w = new HashMap<>();
		config(true).weightShapes("encoder.layers.0").forEach((key, dims) ->
				w.put(key, randn(key, shape(dims))));
		config(false).weightShapes("decoder.layers.3").forEach((key, dims) -> {
			PackedCollection weight = randn(key, shape(dims));
			w.put(key, writesResidual(key) ? cp(weight).multiply(outputGain).evaluate() : weight);
		});

		int dim = config(true).getOutChannels();
		w.put("encoder.layers.2.weight", randn("encoder.layers.2.weight", shape(LATENT, dim)));
		w.put("encoder.layers.2.bias", randn("encoder.layers.2.bias", shape(LATENT)));
		w.put("decoder.layers.1.weight", randn("decoder.layers.1.weight", shape(dim, LATENT)));
		w.put("decoder.layers.1.bias", randn("decoder.layers.1.bias", shape(dim)));
		w.put("bottleneck.scaling_factor", randn("bottleneck.scaling_factor", shape(1, LATENT, 1)));
		w.put("bottleneck.bias", randn("bottleneck.bias", shape(1, LATENT, 1)));
		w.put("bottleneck.running_std", new PackedCollection(shape(1)).fill(1.5));
		return new StateDictionary(w);
	}

	/**
	 * A collection of the given shape filled with normally distributed values from a {@link Random}
	 * seeded deterministically from {@link #WEIGHT_SEED} and the weight's key. Seeding per key rather
	 * than from one shared source keeps the fill independent of the order the weight map is iterated.
	 *
	 * @param key   the weight's key, mixed into the seed
	 * @param shape the shape of the collection
	 * @return the filled collection
	 */
	private PackedCollection randn(String key, TraversalPolicy shape) {
		return new PackedCollection(shape).randnFill(new Random(WEIGHT_SEED + key.hashCode()));
	}

	/**
	 * Whether a resampling-block weight writes into the residual stream rather than feeding a
	 * normalization or the attention scores.
	 *
	 * @param key the weight name
	 * @return whether scaling it scales the block's output
	 */
	private static boolean writesResidual(String key) {
		return key.contains(".mapping.") || key.endsWith(".new_tokens") ||
				key.contains(".self_attn.to_out.") || key.contains(".ff.ff.2.");
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
