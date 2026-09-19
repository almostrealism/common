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
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.ml.DiffusionTransformerWeightFixture;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.TransformerResamplingShapeTest;
import org.almostrealism.ml.t5gemma.T5GemmaConfig;
import org.almostrealism.ml.t5gemma.T5GemmaEncoder;
import org.almostrealism.ml.t5gemma.T5GemmaWeightFixture;
import org.junit.Test;

import java.util.Random;

/**
 * End-to-end tests of {@link StableAudio3} over small synthetic components: the generated clip
 * has the requested length and channel count, the transformer's padding mask covers the requested
 * duration plus the headroom, and guided generation over a negative prompt produces finite audio
 * within the clamp.
 */
public class StableAudio3Test extends TransformerResamplingShapeTest {

	/** Audio sample rate of the small pipeline, in Hz. */
	private static final double SAMPLE_RATE = 100.0;

	/** Longest clip the small pipeline generates, in seconds. */
	private static final double MAX_SECONDS = 0.2;

	/** Headroom generated beyond the requested duration, in seconds. */
	private static final double HEADROOM = 0.1;

	/** Token positions the small prompt encoder is compiled for. */
	private static final int PROMPT_LENGTH = 8;

	/** Fourier feature count of the small duration embedder. */
	private static final int FOURIER = 8;

	/** Transformer width of the small pipeline. */
	private static final int DIM = 16;

	/** Builder of the small autoencoder. */
	private final SAMEAutoEncoderFixture autoencoders = new SAMEAutoEncoderFixture();

	/**
	 * A clip of the requested duration has one row per audio channel and exactly the samples the
	 * duration spans, and the padding mask marks the frames covering the duration plus the headroom
	 * as valid: 15 samples need 4 of the 8 frames, and the headroom adds 2 more.
	 */
	@Test(timeout = 240000)
	public void generatesRequestedDurationWithPaddingMask() {
		StableAudio3 model = smallModel().setSteps(2).setVerbose(false);
		try {
			assertEquals(8, model.getLatentLength());
			assertEquals(6, model.validFrames(0.15));
			assertEquals(4, model.validFrames(0.05));
			assertEquals(7, model.validFrames(MAX_SECONDS));

			PackedCollection audio = model.generate(7, new long[]{5, 7, 9}, 0.15).evaluate();
			assertEquals(2, audio.getShape().getDimensions());
			assertEquals(SAMEAutoEncoderFixture.CHANNELS, audio.getShape().length(0));
			assertEquals(15, audio.getShape().length(1));

			PackedCollection mask = model.getPaddingMask();
			for (int i = 0; i < 8; i++) {
				assertEquals("frame " + i, i < 6 ? 1.0 : 0.0, mask.toDouble(i), 0.0);
			}

			model.generate(7, new long[]{5, 7, 9}, 0.05).evaluate();
			assertEquals(1.0, mask.toDouble(3), 0.0);
			assertEquals(0.0, mask.toDouble(4), 0.0);
		} finally {
			model.destroy();
		}
	}

	/**
	 * Guided generation over a negative prompt runs the conditioner and transformer for both
	 * prompts and produces finite audio within {@code [-1, 1]}.
	 */
	@Test(timeout = 240000)
	public void guidedGenerationIsFiniteAndBounded() {
		StableAudio3 model = smallModel().setSteps(2).setVerbose(false).setGuidance(3.0, new long[]{11});
		try {
			PackedCollection audio = model.generate(3, new long[]{5, 7, 9}, 0.1).evaluate();
			assertFinite(audio);
			for (int i = 0; i < audio.getShape().getTotalSize(); i++) {
				assertTrue(Math.abs(audio.toDouble(i)) <= 1.0);
			}
		} finally {
			model.destroy();
		}
	}

	/**
	 * A duration beyond the one the model was built for is rejected.
	 */
	@Test(timeout = 240000)
	public void overlongDurationIsRejected() {
		StableAudio3 model = smallModel().setVerbose(false);
		try {
			model.generate(1, new long[]{5}, MAX_SECONDS + 0.1);
			throw new AssertionError("an overlong duration must be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains(String.valueOf(MAX_SECONDS)));
		} finally {
			model.destroy();
		}
	}

	/**
	 * The small pipeline: a two-layer prompt encoder of width 16, a duration embedder of the same
	 * width, a one-block transformer with adaptive layer-norm conditioning, two memory tokens, a
	 * local additive input and a padding mask, and the small autoencoder.
	 *
	 * @return the model
	 */
	private StableAudio3 smallModel() {
		T5GemmaWeightFixture prompts = new T5GemmaWeightFixture();
		T5GemmaConfig encoderConfig = prompts.smallConfig(PROMPT_LENGTH);
		Random random = new Random(21);
		NumberConditioner duration = NumberConditioner.expo(0.0, 384.0, DIM, FOURIER, 0.5, 10000.0,
				prompts.random(random, DIM, FOURIER), prompts.random(random, DIM));
		StableAudio3Conditioner conditioner = new StableAudio3Conditioner(
				new T5GemmaEncoder(encoderConfig, new StateDictionary(prompts.weights(encoderConfig, 3))),
				prompts.random(random, DIM), duration);

		DiffusionTransformerConfig config = new DiffusionTransformerConfig(
				SAMEAutoEncoderFixture.LATENT, DIM, 1, 2, 1, DIM, DIM, "rf_denoiser", 1, PROMPT_LENGTH + 1)
				.withConditioningMode(ConditioningMode.ADALN)
				.withMemoryTokens(2)
				.withLocalAddCondDim(SAMEAutoEncoderFixture.LATENT + 1)
				.withTimestepEncoding(TimestepEncoding.EXPO)
				.withNormalization(NormalizationType.RMS)
				.withPaddingMask(true);
		StateDictionary transformerWeights = new StateDictionary(new DiffusionTransformerWeightFixture().weights(config));

		return new StableAudio3(config, transformerWeights, conditioner, autoencoders.autoencoder(),
				SAMPLE_RATE, MAX_SECONDS, HEADROOM);
	}
}
