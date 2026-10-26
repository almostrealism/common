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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.ResamplingConfig;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.PatchedPretransform;
import org.almostrealism.ml.audio.SAMEAutoEncoder;
import org.almostrealism.ml.audio.SoftNormBottleneck;
import org.almostrealism.studio.ml.AudioComposer;
import org.almostrealism.studio.ml.CompiledModelAutoEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Verifies that a {@link SAMEAutoEncoder} compiles through {@link CompiledModelAutoEncoder#of}
 * into an autoencoder whose encode and decode paths carry the expected shapes and whose rates
 * follow the downsampling ratio, and that {@link AudioComposer} composes its latents in the shape
 * Stable Audio 3 diffuses from.
 */
public class SAMEAutoEncoderCompilationTest extends TestSuiteBase {

	/** Audio channels of the small autoencoder. */
	private static final int CHANNELS = 2;

	/** Samples folded per frame by the pretransform. */
	private static final int PATCH = 2;

	/** Latent channels of the small autoencoder. */
	private static final int LATENT = 3;

	/** Latent channels of the released SAME-S autoencoder, which Stable Audio 3 diffuses over. */
	private static final int SA3_LATENT = 256;

	/** Length of the interpolation weight vectors the composer assigns to each source. */
	private static final int COMPOSER_DIM = 8;

	/** Sample rate the clip is declared at. */
	private static final double SAMPLE_RATE = 48000.0;

	/**
	 * The compiled autoencoder reports the latent rate as the sample rate divided by the
	 * downsampling ratio, encodes a clip to a latent of the expected size and decodes it back to
	 * the clip's size.
	 */
	@Test(timeout = 240000)
	public void compiledAutoEncoderRoundTripsShapes() {
		SAMEAutoEncoder autoencoder = smallAutoEncoder(LATENT);
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
	 * {@link AudioComposer} composes latents of a SAME-S-shaped autoencoder — 256 latent channels —
	 * compiled through {@link CompiledModelAutoEncoder#of}. Each clip is encoded to the batched
	 * {@code [1, 256, latentLen]} latent, the batch axis is dropped so the composer sees the
	 * unbatched {@code [bins, time]} features it weights, and the composed latent is restored to the
	 * batched {@code [1, latentDim, latentLen]} shape that {@code StableAudio3.generateFrom} accepts,
	 * which the compiled decoder also accepts. The composition is a non-negative mix of the two
	 * encoded sources, as the composer's position-weighted blend defines it.
	 */
	@Test(timeout = 240000)
	public void composerBlendsSAMESLatentsInStableAudio3Shape() {
		SAMEAutoEncoder autoencoder = smallAutoEncoder(SA3_LATENT);
		int samples = autoencoder.alignedAudioLength(30);
		int latentLen = autoencoder.latentLength(samples);
		TraversalPolicy batched = shape(1, autoencoder.getLatentDim(), latentLen);
		TraversalPolicy unbatched = shape(autoencoder.getLatentDim(), latentLen);

		CompiledModelAutoEncoder compiled = CompiledModelAutoEncoder.of(autoencoder, 1, samples, SAMPLE_RATE, 10.0);
		AudioComposer composer = new AudioComposer(compiled, COMPOSER_DIM, 3);
		try {
			PackedCollection first = encode(compiled, samples, 31).reshape(unbatched);
			PackedCollection second = encode(compiled, samples, 37).reshape(unbatched);
			composer.addSource(cp(first));
			composer.addSource(cp(second));
			assertEquals(unbatched, composer.getFeatureShape());

			// The composer draws each source's weights around a mean of one, so a position along the
			// all-ones direction scores both sources positively and the blend keeps both of them.
			PackedCollection position = new PackedCollection(shape(COMPOSER_DIM)).fill(1.0);
			PackedCollection composed = composer.getInterpolatedLatent(cp(position)).get().evaluate();
			assertEquals(unbatched.getTotalSize(), composed.getShape().getTotalSize());

			double[] mix = leastSquaresMix(composed, first, second);
			assertTrue("first source weight " + mix[0], mix[0] >= -1e-6);
			assertTrue("second source weight " + mix[1], mix[1] >= -1e-6);
			assertTrue("the position must select at least one source", mix[0] + mix[1] > 1e-3);

			for (int i = 0; i < unbatched.getTotalSize(); i++) {
				double expected = mix[0] * first.toDouble(i) + mix[1] * second.toDouble(i);
				assertEquals("latent element " + i, expected, composed.toDouble(i),
						1e-4 * (1.0 + Math.abs(expected)));
			}

			PackedCollection start = composed.reshape(batched);
			assertEquals(batched, start.getShape());

			PackedCollection decoded = compiled.decode(cp(start)).get().evaluate();
			assertEquals(CHANNELS * samples, decoded.getShape().getTotalSize());
		} finally {
			composer.destroy();
		}
	}

	/**
	 * Encodes a synthetic clip and copies the latent out of the encoder's output buffer, which the
	 * next encode overwrites.
	 *
	 * @param compiled the compiled autoencoder
	 * @param samples  the clip length in samples
	 * @param seed     seed of the clip's samples
	 * @return the batched latent
	 */
	private PackedCollection encode(CompiledModelAutoEncoder compiled, int samples, long seed) {
		PackedCollection audio = new PackedCollection(shape(1, CHANNELS, samples)).randnFill(new Random(seed));
		return compiled.encode(cp(audio)).get().evaluate().clone();
	}

	/**
	 * The coefficients {@code a, b} minimizing {@code |composed - a * first - b * second|}, from
	 * the two-by-two normal equations.
	 *
	 * @param composed the composed latent
	 * @param first    the first source latent
	 * @param second   the second source latent
	 * @return the two coefficients
	 */
	private static double[] leastSquaresMix(PackedCollection composed, PackedCollection first,
											PackedCollection second) {
		double[] c = composed.toArray();
		double[] x = first.toArray();
		double[] y = second.toArray();
		double xx = 0.0;
		double xy = 0.0;
		double yy = 0.0;
		double xc = 0.0;
		double yc = 0.0;
		for (int i = 0; i < c.length; i++) {
			xx += x[i] * x[i];
			xy += x[i] * y[i];
			yy += y[i] * y[i];
			xc += x[i] * c[i];
			yc += y[i] * c[i];
		}

		double det = xx * yy - xy * xy;
		return new double[] {(xc * yy - yc * xy) / det, (yc * xx - xc * xy) / det};
	}

	/**
	 * A small autoencoder over synthetic weights: a two-sample patch, stride-two resampling blocks
	 * with two layers each, and a latent of the given width.
	 *
	 * @param latent the number of latent channels
	 * @return the autoencoder
	 */
	private SAMEAutoEncoder smallAutoEncoder(int latent) {
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
		w.put("encoder.layers.2.weight", new PackedCollection(shape(latent, dim)).randnFill());
		w.put("encoder.layers.2.bias", new PackedCollection(shape(latent)).randnFill());
		w.put("decoder.layers.1.weight", new PackedCollection(shape(dim, latent)).randnFill());
		w.put("decoder.layers.1.bias", new PackedCollection(shape(dim)).randnFill());
		w.put("bottleneck.scaling_factor", new PackedCollection(shape(1, latent, 1)).randnFill());
		w.put("bottleneck.bias", new PackedCollection(shape(1, latent, 1)).randnFill());
		w.put("bottleneck.running_std", new PackedCollection(shape(1)).fill(1.5));

		StateDictionary weights = new StateDictionary(w);
		return new SAMEAutoEncoder(weights, new PatchedPretransform(CHANNELS, PATCH),
				encoder, decoder, latent, new SoftNormBottleneck(weights, latent));
	}
}
