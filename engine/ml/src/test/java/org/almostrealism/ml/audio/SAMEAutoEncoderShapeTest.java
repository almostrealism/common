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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.ResamplingConfig;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.TransformerResamplingShapeTest;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Shape and composition tests for {@link SAMEAutoEncoder} with synthetic weights at small
 * dimensions: the pretransform, resampling block, latent projection and bottleneck compose into an
 * encoder and a decoder whose shapes are exact inverses, and the alignment arithmetic matches.
 */
public class SAMEAutoEncoderShapeTest extends TransformerResamplingShapeTest {

	/** Audio channels. */
	private static final int CHANNELS = 2;
	/** Samples folded per frame. */
	private static final int PATCH = 2;
	/** Latent channels. */
	private static final int LATENT = 3;

	/**
	 * The alignment and length arithmetic follows from the patch size, stride and chunk size.
	 */
	@Test(timeout = 60000)
	public void alignmentArithmetic() {
		SAMEAutoEncoder ae = autoencoder(syntheticWeights());
		assertEquals(PATCH * 2, ae.getDownsamplingRatio());
		assertEquals(LATENT, ae.getLatentDim());
		assertEquals(CHANNELS, ae.getChannels());

		// frames align to the chunk size (4) => samples align to 8
		assertEquals(16, ae.alignedAudioLength(9));
		assertEquals(16, ae.alignedAudioLength(16));
		assertEquals(4, ae.latentLength(16));
	}

	/**
	 * The released configuration builds without weights being consumed at construction.
	 */
	@Test(timeout = 60000)
	public void smallConfigurationArithmetic() {
		Map<String, PackedCollection> w = new HashMap<>();
		w.put("bottleneck.scaling_factor", new PackedCollection(shape(1, 256, 1)).fill(1.0));
		w.put("bottleneck.bias", new PackedCollection(shape(1, 256, 1)));
		w.put("bottleneck.running_std", new PackedCollection(shape(1)).fill(1.0));

		SAMEAutoEncoder ae = SAMEAutoEncoder.small(new StateDictionary(w));
		assertEquals(4096, ae.getDownsamplingRatio());
		assertEquals(256, ae.getLatentDim());
		assertEquals(8192, ae.alignedAudioLength(4097));
		assertEquals(2, ae.latentLength(8192));
	}

	/**
	 * Encoding a stereo signal produces a latent of the expected shape and decoding restores the
	 * audio shape, with finite values throughout.
	 */
	@Test(timeout = 240000)
	public void encodeDecodeShapes() {
		skipWhenMetalPresent();

		SAMEAutoEncoder ae = autoencoder(syntheticWeights());
		int samples = 32;
		int latentLen = ae.latentLength(samples);

		PackedCollection audio = new PackedCollection(shape(1, CHANNELS, samples)).randnFill();
		PackedCollection latent = evalBlock(ae.encoder(1, samples), audio);
		assertEquals(LATENT * latentLen, latent.getShape().getTotalSize());
		assertFinite(latent);

		PackedCollection decoded = evalBlock(ae.decoder(1, latentLen), latent.reshape(shape(1, LATENT, latentLen)));
		assertEquals(CHANNELS * samples, decoded.getShape().getTotalSize());
		assertFinite(decoded);
	}

	/**
	 * Misaligned lengths are rejected with the aligned length named.
	 */
	@Test(timeout = 60000)
	public void misalignedLengthsRejected() {
		SAMEAutoEncoder ae = autoencoder(syntheticWeights());

		try {
			ae.encoder(1, 10);
			throw new AssertionError("encoder must reject a misaligned length");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("16"));
		}

		try {
			ae.decoder(1, 3);
			throw new AssertionError("decoder must reject a misaligned latent length");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("aligned"));
		}
	}

	/**
	 * Builds the small autoencoder over the given weights.
	 *
	 * @param weights synthetic weights
	 * @return the autoencoder
	 */
	private SAMEAutoEncoder autoencoder(StateDictionary weights) {
		return new SAMEAutoEncoder(weights, new PatchedPretransform(CHANNELS, PATCH),
				smallConfig(true), smallConfig(false), LATENT,
				SAMEAutoEncoder.softNormBottleneck(weights, LATENT));
	}

	/**
	 * Synthetic weights for both resampling blocks, the latent projections and the bottleneck.
	 *
	 * @return the weights
	 */
	private StateDictionary syntheticWeights() {
		Map<String, PackedCollection> w = new HashMap<>();
		blockWeightShapes(smallConfig(true), "encoder.layers.0").forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));
		blockWeightShapes(smallConfig(false), "decoder.layers.3").forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));

		int dim = smallConfig(true).getOutChannels();
		w.put("encoder.layers.2.weight", new PackedCollection(shape(LATENT, dim)).randnFill());
		w.put("encoder.layers.2.bias", new PackedCollection(shape(LATENT)).randnFill());
		w.put("decoder.layers.1.weight", new PackedCollection(shape(dim, LATENT)).randnFill());
		w.put("decoder.layers.1.bias", new PackedCollection(shape(dim)).randnFill());
		w.put("bottleneck.scaling_factor", new PackedCollection(shape(1, LATENT, 1)).randnFill());
		w.put("bottleneck.bias", new PackedCollection(shape(1, LATENT, 1)).randnFill());
		w.put("bottleneck.running_std", new PackedCollection(shape(1)).fill(1.5));
		return new StateDictionary(w);
	}
}
