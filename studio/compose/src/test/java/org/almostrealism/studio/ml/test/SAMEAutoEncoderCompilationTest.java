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

package org.almostrealism.studio.ml.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.ResamplingConfig;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.PatchedPretransform;
import org.almostrealism.ml.audio.SAMEAutoEncoder;
import org.almostrealism.studio.ml.CompiledModelAutoEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Verifies that a {@link SAMEAutoEncoder} compiles through {@link CompiledModelAutoEncoder#of}
 * into an autoencoder whose encode and decode paths carry the expected shapes and whose rates
 * follow the downsampling ratio.
 */
public class SAMEAutoEncoderCompilationTest extends TestSuiteBase {

	/** Audio channels of the small autoencoder. */
	private static final int CHANNELS = 2;

	/** Samples folded per frame by the pretransform. */
	private static final int PATCH = 2;

	/** Latent channels of the small autoencoder. */
	private static final int LATENT = 3;

	/** Sample rate the clip is declared at. */
	private static final double SAMPLE_RATE = 48000.0;

	/**
	 * The compiled autoencoder reports the latent rate as the sample rate divided by the
	 * downsampling ratio, encodes a clip to a latent of the expected size and decodes it back to
	 * the clip's size.
	 */
	@Test(timeout = 240000)
	public void compiledAutoEncoderRoundTripsShapes() {
		SAMEAutoEncoder autoencoder = smallAutoEncoder();
		int samples = autoencoder.alignedAudioLength(30);
		int latentLen = autoencoder.latentLength(samples);

		CompiledModelAutoEncoder compiled = CompiledModelAutoEncoder.of(autoencoder, 1, samples, SAMPLE_RATE, 10.0);
		assertEquals(SAMPLE_RATE, compiled.getSampleRate(), 0.0);
		assertEquals(SAMPLE_RATE / autoencoder.getDownsamplingRatio(), compiled.getLatentSampleRate(), 1e-9);
		assertEquals(10.0, compiled.getMaximumDuration(), 0.0);

		PackedCollection audio = new PackedCollection(shape(1, CHANNELS, samples)).randnFill();
		PackedCollection latent = compiled.encode(cp(audio)).get().evaluate();
		assertEquals(LATENT * latentLen, latent.getShape().getTotalSize());

		PackedCollection decoded = compiled.decode(cp(latent.reshape(shape(1, LATENT, latentLen)))).get().evaluate();
		assertEquals(CHANNELS * samples, decoded.getShape().getTotalSize());

		compiled.destroy();
	}

	/**
	 * A small autoencoder over synthetic weights: a two-sample patch, stride-two resampling blocks
	 * with two layers each, and a three-channel latent.
	 *
	 * @return the autoencoder
	 */
	private SAMEAutoEncoder smallAutoEncoder() {
		ResamplingConfig encoder = new ResamplingConfig(4, 8, 2, 4, 2, 4, 2,
				true, true, true, 2.0, 1, ResamplingConfig.AttentionWindow.CHUNKED);
		ResamplingConfig decoder = new ResamplingConfig(8, 4, 2, 4, 2, 4, 2,
				false, true, true, 2.0, 3, ResamplingConfig.AttentionWindow.CHUNKED);

		Map<String, PackedCollection> w = new HashMap<>();
		encoder.weightShapes("encoder.layers.0").forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));
		decoder.weightShapes("decoder.layers.3").forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));

		int dim = encoder.getOutChannels();
		w.put("encoder.layers.2.weight", new PackedCollection(shape(LATENT, dim)).randnFill());
		w.put("encoder.layers.2.bias", new PackedCollection(shape(LATENT)).randnFill());
		w.put("decoder.layers.1.weight", new PackedCollection(shape(dim, LATENT)).randnFill());
		w.put("decoder.layers.1.bias", new PackedCollection(shape(dim)).randnFill());
		w.put("bottleneck.scaling_factor", new PackedCollection(shape(1, LATENT, 1)).randnFill());
		w.put("bottleneck.bias", new PackedCollection(shape(1, LATENT, 1)).randnFill());
		w.put("bottleneck.running_std", new PackedCollection(shape(1)).fill(1.5));

		StateDictionary weights = new StateDictionary(w);
		return new SAMEAutoEncoder(weights, new PatchedPretransform(CHANNELS, PATCH),
				encoder, decoder, LATENT, SAMEAutoEncoder.softNormBottleneck(weights, LATENT));
	}
}
