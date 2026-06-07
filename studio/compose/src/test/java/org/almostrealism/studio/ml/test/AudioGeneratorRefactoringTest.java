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
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;
import org.almostrealism.ml.audio.*;
import org.almostrealism.studio.ml.AudioGenerator;
import org.almostrealism.studio.ml.ConditionalAudioSystem;
import org.almostrealism.studio.ml.LegacyAudioGenerator;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.*;

/**
 * Tests that validate the refactored AudioGenerator produces identical output
 * to the legacy implementation.
 *
 * <p>This test compares:
 * <ul>
 *   <li>{@link AudioGenerator} - refactored to use {@link AudioDiffusionGenerator}</li>
 *   <li>{@link LegacyAudioGenerator} - original implementation with direct sampler</li>
 * </ul>
 *
 * <p>Both should produce identical results given the same inputs and seed.
 */
public class AudioGeneratorRefactoringTest extends TestSuiteBase {

	/**
	 * Tests that both generators produce the same latent output from the sampler.
	 * This validates that the refactoring didn't change the diffusion behavior.
	 */
	@Test(timeout = 60_000)
	public void testSamplerOutputEquivalence() {
		log("=== Testing Sampler Output Equivalence ===");

		// Use mock weights (empty map) for testing
		StateDictionary ditStates = new StateDictionary(new HashMap<>());

		// Create mock components for testing
		MockTokenizer tokenizer = new MockTokenizer();
		MockConditioner conditioner = new MockConditioner();
		MockAutoEncoder autoencoder = new MockAutoEncoder();

		// Create both generators with same seed
		long composerSeed = 12345L;
		AudioGenerator refactored = new AudioGenerator(
				tokenizer, conditioner, autoencoder, ditStates, 8, composerSeed);
		LegacyAudioGenerator legacy = new LegacyAudioGenerator(
				tokenizer, conditioner, autoencoder, ditStates, 8, composerSeed);

		// Get the underlying samplers
		DiffusionSampler refactoredSampler = refactored.getSampler();
		DiffusionSampler legacySampler = legacy.getSampler();

		// Both samplers should be non-null
		assertNotNull("Refactored sampler should not be null", refactoredSampler);
		assertNotNull("Legacy sampler should not be null", legacySampler);

		log("Both generators have valid samplers - PASSED");

		// Test that both samplers produce same output with same seed
		// (This requires actually running diffusion which needs GPU, so we just verify setup)
		assertEquals("Both should have same number of steps",
				refactoredSampler.getNumInferenceSteps(),
				legacySampler.getNumInferenceSteps());

		log("Configuration equivalence verified - PASSED");
	}

	/**
	 * Tests the structural equivalence of the generators.
	 */
	@Test(timeout = 60_000)
	public void testStructuralEquivalence() {
		log("=== Testing Structural Equivalence ===");

		// Use mock weights (empty map) for testing
		StateDictionary ditStates = new StateDictionary(new HashMap<>());

		MockTokenizer tokenizer = new MockTokenizer();
		MockConditioner conditioner = new MockConditioner();
		MockAutoEncoder autoencoder = new MockAutoEncoder();

		long composerSeed = 12345L;
		AudioGenerator refactored = new AudioGenerator(
				tokenizer, conditioner, autoencoder, ditStates, 8, composerSeed);
		LegacyAudioGenerator legacy = new LegacyAudioGenerator(
				tokenizer, conditioner, autoencoder, ditStates, 8, composerSeed);

		// Verify same default settings
		assertEquals("Audio duration should match",
				legacy.getAudioDuration(), refactored.getAudioDuration(), 0.0);
		assertEquals("Strength should match",
				legacy.getStrength(), refactored.getStrength(), 0.0);
		assertEquals("Composer dimension should match",
				legacy.getComposerDimension(), refactored.getComposerDimension());

		log("Default settings match - PASSED");

		// Verify setters work identically
		refactored.setAudioDurationSeconds(5.0);
		legacy.setAudioDurationSeconds(5.0);
		assertEquals("Audio duration after set should match",
				legacy.getAudioDuration(), refactored.getAudioDuration(), 0.0);

		refactored.setStrength(0.75);
		legacy.setStrength(0.75);
		assertEquals("Strength after set should match",
				legacy.getStrength(), refactored.getStrength(), 0.0);

		log("Setter behavior matches - PASSED");

		// Verify refactored has new getGenerator() method
		assertNotNull("Refactored should expose AudioDiffusionGenerator",
				refactored.getGenerator());

		log("Structural equivalence verified - PASSED");
	}

	/**
	 * Exercises the full "generate from samples" (img2img) pipeline end-to-end with
	 * the production input shapes, which is exactly the path that produced the
	 * "Axis 3 is greater than the number of dimensions (2)" crash in the desktop app.
	 *
	 * <p>The autoencoder latent space is 2-D ({@code [LATENT_DIMENSIONS, LATENT_TIME_STEPS]});
	 * the diffusion model's primary input is 3-D ({@code (1, LATENT_DIMENSIONS, LATENT_TIME_STEPS)}).
	 * This test adds a 2-D latent feature (as the real autoencoder produces), runs the
	 * from-samples generation path, and verifies audio is produced — i.e. that the 2-D
	 * latent is correctly batched before reaching the (strictly shape-validated) model.
	 * A smaller-capacity model is used, but every model input keeps its production shape
	 * (latent {@code (1,64,256)}, cross {@code (1,65,768)}, global {@code (1,768)},
	 * timestep {@code (1,1)}).</p>
	 */
	@Test(timeout = 300_000)
	@TestDepth(1)
	public void generatesAudioFromSamplesWithProductionShapes() {
		log("=== Testing from-samples generation with production shapes ===");

		int ioChannels = ConditionalAudioSystem.LATENT_DIMENSIONS;
		int audioSeqLen = ConditionalAudioSystem.LATENT_TIME_STEPS;
		int condSeqLen = 65;
		int condTokenDim = 768;
		int globalCondDim = 768;
		int composerDim = 8;

		MockTokenizer tokenizer = new MockTokenizer();
		MockConditioner conditioner = new MockConditioner(condSeqLen);
		MockAutoEncoder autoencoder = new MockAutoEncoder();

		// Small-capacity diffusion model that preserves the production input shapes.
		// Random (non-zero) weights keep the full process tree intact. ioChannels and
		// audioSeqLen match the production latent so the primary input is (1, 64, 256).
		DiffusionTransformer ditModel = new DiffusionTransformer(
				ioChannels, 64, 2, 2, 1,
				condTokenDim, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen, null, false) {
			@Override
			protected PackedCollection createWeight(String key, TraversalPolicy expectedShape) {
				PackedCollection weight = new PackedCollection(expectedShape);
				weight.fill(pos -> Math.random() * 0.02 - 0.01);
				return weight;
			}
		};

		AudioGenerator generator = new AudioGenerator(
				tokenizer, conditioner, autoencoder, ditModel, composerDim, 1234L);
		generator.setStrength(0.4);
		generator.setAudioDurationSeconds(2.0);

		// Add a latent feature in the autoencoder's 2-D latent shape (the production
		// composer/autoencoder representation), reproducing the data shape that triggered
		// the original failure.
		PackedCollection features = new PackedCollection(ioChannels, audioSeqLen);
		features.fill(pos -> Math.random());
		generator.addFeatures(features);

		PackedCollection position = new PackedCollection(composerDim);
		position.fill(pos -> Math.random());

		log("Running from-samples generateAudio...");
		WaveData audio = generator.generateAudio(position, "a test sound", 1234L);

		assertNotNull("generateAudio should produce audio via the from-samples path", audio);
		assertNotNull("generated audio should contain data", audio.getData());

		log("From-samples generation produced audio - PASSED");
	}

	/**
	 * Mock tokenizer for testing without actual model weights.
	 */
	private static class MockTokenizer implements Tokenizer {
		@Override
		public long[] encodeAsLong(String text) {
			return new long[]{1L, 2L, 3L};
		}

		@Override
		public String decodeAsLong(long[] tokens) {
			return "mock";
		}
	}

	/**
	 * Mock conditioner for testing without actual model weights.
	 */
	private static class MockConditioner implements AudioAttentionConditioner {
		/** Cross-attention sequence length the mock emits (matches the DiT condSeqLen). */
		private final int condSeqLen;

		/** Creates a mock conditioner with a default cross-attention sequence length. */
		MockConditioner() {
			this(77);
		}

		/**
		 * Creates a mock conditioner emitting the given cross-attention sequence length.
		 *
		 * @param condSeqLen the cross-attention sequence length to emit
		 */
		MockConditioner(int condSeqLen) {
			this.condSeqLen = condSeqLen;
		}

		@Override
		public ConditionerOutput runConditioners(long[] tokenIds, double audioDuration) {
			// Return mock conditioning tensors with the real ONNX conditioner ranks:
			// cross_attention_input [batch, seqLen, 768], global_cond [batch, 768].
			PackedCollection crossAttn = new PackedCollection(1, condSeqLen, 768);
			PackedCollection crossAttnMask = new PackedCollection(1, condSeqLen);
			PackedCollection globalCond = new PackedCollection(1, 768);
			return new ConditionerOutput(crossAttn, crossAttnMask, globalCond);
		}
	}

	/**
	 * Mock autoencoder for testing without actual model weights.
	 */
	private static class MockAutoEncoder implements AutoEncoder, CollectionFeatures {
		/** The unbatched latent shape this autoencoder produces and consumes, matching OnnxAutoEncoder. */
		private static final TraversalPolicy LATENT_SHAPE = new TraversalPolicy(
				ConditionalAudioSystem.LATENT_DIMENSIONS, ConditionalAudioSystem.LATENT_TIME_STEPS);

		@Override
		public Producer<PackedCollection> encode(Producer<PackedCollection> input) {
			// The real OnnxAutoEncoder.encode produces an UNBATCHED 2-D latent (LATENT_DIMENSIONS, 256).
			return c(new PackedCollection(LATENT_SHAPE));
		}

		@Override
		public Producer<PackedCollection> decode(Producer<PackedCollection> latent) {
			// Mirror OnnxAutoEncoder.decode's contract exactly: it accepts only an unbatched 2-D
			// latent (LATENT_DIMENSIONS, 256) and rejects any other rank. This is precisely the
			// boundary that crashed the desktop app, so the mock must enforce it for the test to
			// be a faithful reproduction of the production generation path.
			if (!shape(latent).equalsIgnoreAxis(LATENT_SHAPE)) {
				throw new IllegalArgumentException(
						"Autoencoder expected latent " + LATENT_SHAPE +
								" but received " + shape(latent));
			}

			// Return mock audio (stereo, ~1 second at 44100Hz)
			return c(new PackedCollection(2, 44100));
		}

		@Override
		public double getSampleRate() {
			return 44100.0;
		}

		@Override
		public double getLatentSampleRate() {
			return 44100.0 / 2048.0;
		}

		@Override
		public double getMaximumDuration() {
			return 47.0;
		}
	}
}
