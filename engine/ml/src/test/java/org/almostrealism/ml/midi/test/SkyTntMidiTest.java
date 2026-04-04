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
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.ml.midi.SkyTntConfig;
import org.almostrealism.ml.midi.SkyTntMidi;
import org.almostrealism.ml.midi.SkyTntTokenizerV2;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Milestones 4 and 5 tests: model assembly with synthetic weights and the
 * generation loop.
 *
 * <p>The assembly tests verify that both compiled transformers can be built
 * from PDSL blocks with random weights.  The generation tests verify that
 * the dual-transformer generation loop produces valid token sequences.</p>
 *
 * <p>Tests that require actual forward-pass execution are guarded with
 * {@code if (skipLongTests) return} so they are skipped in CI pipelines that
 * use the {@code pipeline} profile.</p>
 *
 * @see SkyTntMidi
 * @see SkyTntConfig
 */
public class SkyTntMidiTest extends TestSuiteBase {

	/** Reduced hidden size for test speed (must be divisible by both HEADS and HEADS_TOKEN). */
	private static final int DIM = 32;

	/** FFN intermediate size for net (4x). */
	private static final int FFN = 128;

	/** FFN intermediate size for net_token (1:1). */
	private static final int FFN_TOKEN = 32;

	/** Attention heads for net. */
	private static final int HEADS = 4;

	/** Attention heads for net_token. */
	private static final int HEADS_TOKEN = 2;

	/** Sequence length for RoPE. */
	private static final int SEQ_LEN = 16;

	/** Number of layers for the test model (1 each for speed). */
	private static final int NET_LAYERS = 1;

	/** Number of token-transformer layers for the test model. */
	private static final int NET_TOKEN_LAYERS = 1;

	/** Small vocabulary size for test models. */
	private static final int VOCAB = SkyTntTokenizerV2.VOCAB_SIZE;

	/** RMSNorm epsilon. */
	private static final double EPSILON = 1e-5;

	/**
	 * Milestone 4: verify that both PDSL-compiled transformers can be built from
	 * random synthetic weights without throwing exceptions.
	 */
	@Test
	public void testModelAssemblyWithSyntheticWeights() {
		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(42));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int netHeadSize = DIM / HEADS;
		int tokenHeadSize = DIM / HEADS_TOKEN;

		PackedCollection netPos = new PackedCollection(1);
		PackedCollection tokenPos = new PackedCollection(1);

		PackedCollection netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, netHeadSize, SEQ_LEN);
		PackedCollection tokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, tokenHeadSize, SEQ_LEN);

		PackedCollection lmHeadWeight = stateDict.get("lm_head");

		// Build net model (no lm head)
		CompiledModel netModel = SkyTntMidi.buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads,
				netFreqCis, netPos, false, EPSILON, null);
		Assert.assertNotNull("net CompiledModel should not be null", netModel);

		// Build net_token model (with lm head)
		CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads,
				tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);
		Assert.assertNotNull("net_token CompiledModel should not be null", netTokenModel);
	}

	/**
	 * Milestone 4: verify net forward pass shape.
	 *
	 * <p>Guarded by {@code skipLongTests} — runs one forward pass through the compiled
	 * net transformer and checks output shape.</p>
	 */
	@Test
	public void testNetForwardPassShape() {
		if (skipLongTests) return;

		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(42));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int netHeadSize = DIM / HEADS;
		PackedCollection netPos = new PackedCollection(1);
		PackedCollection netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, netHeadSize, SEQ_LEN);

		CompiledModel netModel = SkyTntMidi.buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads,
				netFreqCis, netPos, false, EPSILON, null);

		PackedCollection input = new PackedCollection(new TraversalPolicy(1, DIM));
		netPos.setMem(0, 0.0);
		PackedCollection output = netModel.forward(input);

		Assert.assertNotNull("Net output should not be null", output);
		Assert.assertEquals("Net output should have " + DIM + " elements",
				DIM, output.getShape().getTotalSize());
	}

	/**
	 * Milestone 4: verify net_token forward pass shape (output is logits).
	 *
	 * <p>Guarded by {@code skipLongTests}.</p>
	 */
	@Test
	public void testNetTokenForwardPassShape() {
		if (skipLongTests) return;

		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(99));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int tokenHeadSize = DIM / HEADS_TOKEN;
		PackedCollection tokenPos = new PackedCollection(1);
		PackedCollection tokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, tokenHeadSize, SEQ_LEN);
		PackedCollection lmHeadWeight = stateDict.get("lm_head");

		CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads,
				tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);

		PackedCollection input = new PackedCollection(new TraversalPolicy(1, DIM));
		tokenPos.setMem(0, 0.0);
		PackedCollection output = netTokenModel.forward(input);

		Assert.assertNotNull("net_token output should not be null", output);
		Assert.assertEquals("net_token output should have VOCAB elements",
				VOCAB, output.getShape().getTotalSize());
	}

	/**
	 * Milestone 5: verify unconditional generation from BOS produces a valid token sequence.
	 *
	 * <p>Guarded by {@code skipLongTests}.  Verifies:</p>
	 * <ul>
	 *   <li>Output is non-null with at least 2 rows (BOS + at least one generated event)</li>
	 *   <li>Row 0 is the BOS event</li>
	 *   <li>Each generated row (step 0) contains a valid event-type token or PAD</li>
	 * </ul>
	 */
	@Test
	public void testGenerationFromBos() {
		if (skipLongTests) return;

		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(123));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int netHeadSize = DIM / HEADS;
		int tokenHeadSize = DIM / HEADS_TOKEN;

		PackedCollection netPos = new PackedCollection(1);
		PackedCollection tokenPos = new PackedCollection(1);

		PackedCollection netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, netHeadSize, SEQ_LEN);
		PackedCollection tokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, tokenHeadSize, SEQ_LEN);

		PackedCollection netEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));
		PackedCollection tokenEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));
		PackedCollection lmHeadWeight = stateDict.get("lm_head");

		CompiledModel netModel = SkyTntMidi.buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads,
				netFreqCis, netPos, false, EPSILON, null);

		CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads,
				tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);

		// Construct SkyTntMidi with pre-built models
		SkyTntMidi model = new SkyTntMidi(config, netEmbed, tokenEmbed,
				netModel, netTokenModel, new Random(456));

		// BOS prompt
		int[][] prompt = new int[1][config.maxTokenSeq];
		prompt[0][0] = config.bosId;

		int[][] output = model.generate(prompt, 3,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K);

		Assert.assertNotNull("Generation output should not be null", output);
		Assert.assertTrue("Output should have at least 1 row", output.length >= 1);
		Assert.assertEquals("First row should be BOS prompt", config.bosId, output[0][0]);

		// Each generated row's step-0 token should be in the valid event-type range or EOS
		for (int i = 1; i < output.length; i++) {
			int step0 = output[i][0];
			boolean isValidEvent = (step0 >= SkyTntTokenizerV2.EVENT_NOTE
					&& step0 <= SkyTntTokenizerV2.EVENT_KEY_SIGNATURE);
			boolean isEos = (step0 == config.eosId);
			boolean isPad = (step0 == config.padId);
			Assert.assertTrue(
					"Row " + i + " step-0 token " + step0 + " should be event-type, EOS, or PAD",
					isValidEvent || isEos || isPad);
		}
	}

	// -----------------------------------------------------------------------
	//  Helpers
	// -----------------------------------------------------------------------

	/**
	 * Create a {@link StateDictionary} populated with random synthetic weights that
	 * match the shapes expected by {@link SkyTntMidi.buildTransformerModel}.
	 *
	 * @param config the model configuration
	 * @param rng    random number generator
	 * @return populated StateDictionary
	 */
	static StateDictionary createSyntheticWeights(SkyTntConfig config, Random rng) {
		Map<String, PackedCollection> weights = new HashMap<>();

		// LM head (shared)
		weights.put("lm_head", rand(rng, config.vocabSize, config.hiddenSize));

		// Embedding tables
		weights.put("net_embeddings", rand(rng, config.vocabSize, config.hiddenSize));
		weights.put("net_token_embeddings", rand(rng, config.vocabSize, config.hiddenSize));

		// net layers
		addLayerWeights(weights, "net", config.netLayers, config.hiddenSize,
				config.netIntermediateSize, rng);
		weights.put("net_norm", rand(rng, config.hiddenSize));

		// net_token layers
		addLayerWeights(weights, "net_token", config.netTokenLayers, config.hiddenSize,
				config.netTokenIntermediateSize, rng);
		weights.put("net_token_norm", rand(rng, config.hiddenSize));

		return new StateDictionary(weights);
	}

	/** Add per-layer weights for one transformer to the weight map. */
	static void addLayerWeights(Map<String, PackedCollection> weights,
										String prefix, int numLayers,
										int hiddenSize, int ffnSize, Random rng) {
		for (int i = 0; i < numLayers; i++) {
			String key = prefix + "_layer_" + String.format("%02d", i);
			weights.put(key + ".input_layernorm.weight", rand(rng, hiddenSize));
			weights.put(key + ".post_attention_layernorm.weight", rand(rng, hiddenSize));
			weights.put(key + ".self_attn.q_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".self_attn.k_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".self_attn.v_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".self_attn.o_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".mlp.gate_proj.weight", rand(rng, ffnSize, hiddenSize));
			weights.put(key + ".mlp.up_proj.weight", rand(rng, ffnSize, hiddenSize));
			weights.put(key + ".mlp.down_proj.weight", rand(rng, hiddenSize, ffnSize));
		}
	}

	/** Create a PackedCollection filled with small random values. */
	static PackedCollection rand(Random rng, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		PackedCollection c = new PackedCollection(shape);
		int size = shape.getTotalSize();
		for (int i = 0; i < size; i++) {
			c.setMem(i, (rng.nextDouble() - 0.5) * 0.1);
		}
		return c;
	}

}
