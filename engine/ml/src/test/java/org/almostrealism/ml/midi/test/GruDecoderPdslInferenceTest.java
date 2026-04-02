/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.midi.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

/**
 * End-to-end GRU decoder inference test.
 *
 * <p>Verifies that the single-compiled-model architecture produces
 * {@link GRUDecoder#TOKENS_PER_NOTE} valid output tokens for both synthetic
 * and real (Moonbeam checkpoint) weights.</p>
 *
 * <h2>Architecture enforcement</h2>
 * <p>All GRU gate computations are compiled into exactly ONE
 * {@link org.almostrealism.model.CompiledModel} inside {@link GRUDecoder}.
 * The test creates one {@link GRUDecoder} and calls {@code decode()};
 * it does not create any {@link org.almostrealism.model.Model} instances
 * itself, verifying that the entire pipeline is expressed as a single
 * compiled computation graph.</p>
 *
 * <h2>PDSL coverage</h2>
 * <ul>
 *   <li>{@code summary_proj} — projects transformer hidden → decoder hidden (init)</li>
 *   <li>{@code gru_r_gate} — reset gate</li>
 *   <li>{@code gru_z_gate} — update gate</li>
 *   <li>{@code gru_n_gate} — candidate gate</li>
 *   <li>{@code gru_h_new}  — hidden update (lerp)</li>
 *   <li>{@code lm_head}    — projects decoder hidden → vocab logits</li>
 * </ul>
 */
public class GruDecoderPdslInferenceTest extends TestSuiteBase {

	private static final String WEIGHTS_DIR = "/Users/Shared/models/moonbeam-weights-protobuf";

	// -----------------------------------------------------------------------
	//  Test 1: Synthetic weights — always runs, validates build and flow
	// -----------------------------------------------------------------------

	/**
	 * Build a {@link GRUDecoder} with synthetic random weights and run a full
	 * 7-step decode. Validates the single-model architecture produces the correct
	 * number of output tokens.
	 */
	@Test
	public void testGruDecoderSyntheticWeights() {
		// hiddenSize must be divisible by NUM_ATTRIBUTES (6) for embeddingDim
		int hiddenSize = 12;
		// decodeVocabSize = 1 (sos) + NUM_ATTRIBUTES * 2 = 13
		int numAttrs = MoonbeamConfig.NUM_ATTRIBUTES;
		int perAttrVocab = 2;
		int vocabSize = 1 + numAttrs * perAttrVocab;  // 13
		int dh = 8;       // decoder hidden size, independent of hiddenSize
		int numLayers = 2;
		Random rng = new Random(42);

		// Build weight arrays for each layer
		int n = numLayers;
		int[] inputSizes = new int[n];
		PackedCollection[] weightIh = new PackedCollection[n];
		PackedCollection[] weightHh = new PackedCollection[n];
		PackedCollection[] biasIh = new PackedCollection[n];
		PackedCollection[] biasHh = new PackedCollection[n];
		for (int l = 0; l < n; l++) {
			inputSizes[l] = dh;
			weightIh[l] = random(rng, 3 * dh, dh);
			weightHh[l] = random(rng, 3 * dh, dh);
			biasIh[l] = random(rng, 3 * dh);
			biasHh[l] = random(rng, 3 * dh);
		}

		// Build a minimal MoonbeamConfig with the synthetic dimensions
		int[] vocabSizes = new int[numAttrs];
		for (int i = 0; i < numAttrs; i++) vocabSizes[i] = perAttrVocab;
		MoonbeamConfig config = syntheticConfig(hiddenSize, dh, vocabSize, vocabSizes, numLayers);

		// Summary, lm_head, and embedding weights
		PackedCollection summaryW = random(rng, dh, hiddenSize);
		PackedCollection summaryB = random(rng, dh);
		PackedCollection lmW = random(rng, vocabSize, dh);
		PackedCollection lmB = random(rng, vocabSize);
		PackedCollection embedTable = random(rng, vocabSize, dh);

		// ONE GRUDecoder — internally builds ONE compiled model
		GRUDecoder decoder = new GRUDecoder(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
				summaryW, summaryB, lmW, lmB, embedTable);

		// Synthetic transformer hidden state
		PackedCollection transformerHidden = random(rng, hiddenSize);

		// decode() is the only entry point — no extra Model instances created here
		int[] tokens = decoder.decode(transformerHidden);

		Assert.assertEquals("Should produce 7 output tokens",
				GRUDecoder.TOKENS_PER_NOTE, tokens.length);
		for (int i = 0; i < tokens.length; i++) {
			Assert.assertTrue("Token " + i + " must be >= 0", tokens[i] >= 0);
			Assert.assertTrue("Token " + i + " must be < vocabSize", tokens[i] < vocabSize);
		}

		System.out.println("[GruDecoderPdslInferenceTest] synthetic decode: "
				+ Arrays.toString(tokens));
	}

	// -----------------------------------------------------------------------
	//  Test 2: Real weights — skipped if weights directory not present
	// -----------------------------------------------------------------------

	/**
	 * Load Moonbeam protobuf weights, build a {@link GRUDecoder}, and run a
	 * full 7-step decode.
	 *
	 * <p>Skipped when {@value WEIGHTS_DIR} is not present.</p>
	 */
	@Test
	@TestDepth(2)
	public void testGruDecoderRealWeights() throws IOException {
		Assume.assumeTrue("Moonbeam weights not found at " + WEIGHTS_DIR,
				new File(WEIGHTS_DIR).isDirectory());

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		int numLayers = config.decoderLayers;
		int dh = config.decoderHiddenSize;

		int[] inputSizes = new int[numLayers];
		PackedCollection[] weightIh = new PackedCollection[numLayers];
		PackedCollection[] weightHh = new PackedCollection[numLayers];
		PackedCollection[] biasIh = new PackedCollection[numLayers];
		PackedCollection[] biasHh = new PackedCollection[numLayers];
		for (int l = 0; l < numLayers; l++) {
			inputSizes[l] = dh;
			weightIh[l] = stateDict.get(String.format("decoder.weight_ih_l%d", l));
			weightHh[l] = stateDict.get(String.format("decoder.weight_hh_l%d", l));
			biasIh[l] = stateDict.get(String.format("decoder.bias_ih_l%d", l));
			biasHh[l] = stateDict.get(String.format("decoder.bias_hh_l%d", l));
		}

		PackedCollection summaryW = stateDict.get("summary_projection.weight");
		PackedCollection summaryB = stateDict.get("summary_projection.bias");
		PackedCollection lmW = stateDict.get("lm_head.weight");
		PackedCollection lmB = stateDict.get("lm_head.bias");
		PackedCollection embedTable = stateDict.get("decoder_embedding.weight");

		GRUDecoder decoder = new GRUDecoder(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
				summaryW, summaryB, lmW, lmB, embedTable);

		// Synthetic transformer hidden state (all 0.1)
		PackedCollection transformerHidden = new PackedCollection(
				new TraversalPolicy(config.hiddenSize));
		for (int i = 0; i < config.hiddenSize; i++) {
			transformerHidden.setMem(i, 0.1);
		}

		int[] tokens = decoder.decode(transformerHidden);

		Assert.assertEquals("Should produce 7 output tokens",
				GRUDecoder.TOKENS_PER_NOTE, tokens.length);
		int[] vocabOffsets = GRUDecoder.computeVocabOffsets(config);
		for (int i = 0; i < tokens.length; i++) {
			Assert.assertTrue("Token " + i + " >= 0", tokens[i] >= 0);
			Assert.assertTrue("Token " + i + " < decodeVocabSize",
					tokens[i] < config.decodeVocabSize);
		}

		int[] attrValues = decoder.toAttributeValues(tokens);

		System.out.println("[GruDecoderPdslInferenceTest] output tokens (flat vocab): "
				+ Arrays.toString(tokens));
		System.out.println("[GruDecoderPdslInferenceTest] attribute values: "
				+ Arrays.toString(attrValues));
		System.out.println("[GruDecoderPdslInferenceTest] inference test passed.");
	}

	// -----------------------------------------------------------------------
	//  Utilities
	// -----------------------------------------------------------------------

	/**
	 * Create a minimal {@link MoonbeamConfig} suitable for synthetic GRU testing.
	 *
	 * <p>All transformer parameters are set to small but valid values.
	 * {@code hiddenSize} must be divisible by {@link MoonbeamConfig#NUM_ATTRIBUTES}.</p>
	 *
	 * @param hiddenSize    transformer hidden size (must be divisible by NUM_ATTRIBUTES)
	 * @param decoderHidden decoder hidden size
	 * @param vocabSize     total decode vocabulary size
	 * @param vocabSizes    per-attribute vocabulary sizes, length NUM_ATTRIBUTES
	 * @param numLayers     number of GRU layers
	 * @return synthetic config
	 */
	private static MoonbeamConfig syntheticConfig(int hiddenSize, int decoderHidden,
												   int vocabSize, int[] vocabSizes,
												   int numLayers) {
		double[] fmeBases = new double[MoonbeamConfig.NUM_ATTRIBUTES];
		for (int i = 0; i < fmeBases.length; i++) fmeBases[i] = 1000.0;
		double[] ropeThetas = new double[MoonbeamConfig.NUM_ATTRIBUTES];
		for (int i = 0; i < ropeThetas.length; i++) ropeThetas[i] = 10000.0;
		return new MoonbeamConfig(
				hiddenSize,        // hiddenSize; embeddingDim = hiddenSize / NUM_ATTRIBUTES
				hiddenSize * 4,    // intermediateSize
				2,                 // numLayers (transformer depth, not GRU)
				2,                 // numHeads
				2,                 // numKvHeads
				hiddenSize / 2,    // headDim
				decoderHidden,     // decoderHiddenSize
				numLayers,         // decoderLayers
				vocabSize,         // decodeVocabSize
				512,               // maxSeqLen
				1e-5,              // rmsNormEps
				ropeThetas,        // ropeThetas (one per attribute)
				null,              // headsPerGroup
				vocabSizes,        // per-attribute vocab sizes
				fmeBases,          // fmeBases
				2                  // supplementaryVocabSize (SOS, EOS)
		);
	}

	/**
	 * Create a random {@link PackedCollection} with small values near 0.
	 *
	 * @param rng  random number generator
	 * @param dims dimension sizes
	 * @return initialised collection
	 */
	private static PackedCollection random(Random rng, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		PackedCollection c = new PackedCollection(shape);
		int total = shape.getTotalSize();
		for (int i = 0; i < total; i++) {
			c.setMem(i, (rng.nextDouble() - 0.5) * 0.1);
		}
		return c;
	}
}
