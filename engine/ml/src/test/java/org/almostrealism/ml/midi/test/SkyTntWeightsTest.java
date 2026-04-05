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

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Verifies that SkyTNT midi-model weights extracted by
 * {@code extract_skytnt_weights.py} load correctly via {@link StateDictionary}
 * and match the expected key names and tensor shapes from the planning doc (§4.2).
 *
 * <p>This test requires extracted protobuf weights in the directory pointed to by
 * {@link #WEIGHTS_DIR}. It is skipped automatically if the directory does not exist,
 * so it will not block CI. Run it after executing the extraction script:</p>
 *
 * <pre>
 *   python engine/ml/src/main/python/extract_skytnt_weights.py \
 *       skytnt/midi-model-tv2o-medium /workspace/project/skytnt-weights-protobuf
 * </pre>
 *
 * @see StateDictionary
 */
public class SkyTntWeightsTest extends TestSuiteBase implements ConsoleFeatures {

	/** Default output directory produced by extract_skytnt_weights.py. */
	private static final String WEIGHTS_DIR = "/workspace/project/skytnt-weights-protobuf";

	/** Vocab size for tv2o-medium. */
	private static final int VOCAB_SIZE = 3406;

	/** Hidden dimension for both net and net_token. */
	private static final int HIDDEN = 1024;

	/** FFN intermediate size for the main transformer (net). */
	private static final int NET_FFN_INTERMEDIATE = 4096;

	/** FFN intermediate size for the token transformer (net_token). */
	private static final int NET_TOKEN_FFN_INTERMEDIATE = 1024;

	/** Number of layers in the main transformer (net). */
	private static final int NET_LAYERS = 12;

	/** Number of layers in the token transformer (net_token). */
	private static final int NET_TOKEN_LAYERS = 3;

	/**
	 * Verify that all expected weight keys are present in the loaded dictionary.
	 * Checks top-level tensors and a sample from every transformer layer.
	 */
	@Test
	@TestDepth(2)
	public void testWeightKeysPresent() throws IOException {
		Assume.assumeTrue("SkyTNT weights not found at " + WEIGHTS_DIR,
				new File(WEIGHTS_DIR).isDirectory());

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Top-level tensors
		MoonbeamWeightVerificationTest.assertPresent(stateDict,"net.embed_tokens.weight");
		MoonbeamWeightVerificationTest.assertPresent(stateDict,"net.norm.weight");
		MoonbeamWeightVerificationTest.assertPresent(stateDict,"net_token.embed_tokens.weight");
		MoonbeamWeightVerificationTest.assertPresent(stateDict,"net_token.norm.weight");
		MoonbeamWeightVerificationTest.assertPresent(stateDict,"lm_head.weight");

		// All net layer keys (12 layers)
		for (int i = 0; i < NET_LAYERS; i++) {
			String p = "net.layers." + i;
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.q_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.k_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.v_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.o_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".mlp.gate_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".mlp.up_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".mlp.down_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".input_layernorm.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".post_attention_layernorm.weight");
		}

		// All net_token layer keys (3 layers)
		for (int i = 0; i < NET_TOKEN_LAYERS; i++) {
			String p = "net_token.layers." + i;
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.q_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.k_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.v_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".self_attn.o_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".mlp.gate_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".mlp.up_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".mlp.down_proj.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".input_layernorm.weight");
			MoonbeamWeightVerificationTest.assertPresent(stateDict,p + ".post_attention_layernorm.weight");
		}

		log("All expected weight keys present. Total loaded: " + stateDict.size());
	}

	/**
	 * Verify that key tensors have the correct shapes as defined in planning doc §4.2.
	 * Checks embeddings, norms, and a full set of layer 0 weights for both transformers.
	 */
	@Test
	@TestDepth(2)
	public void testWeightShapes() throws IOException {
		Assume.assumeTrue("SkyTNT weights not found at " + WEIGHTS_DIR,
				new File(WEIGHTS_DIR).isDirectory());

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Embedding and LM head: [3406, 1024]
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.embed_tokens.weight", VOCAB_SIZE * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.embed_tokens.weight", VOCAB_SIZE * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"lm_head.weight", VOCAB_SIZE * HIDDEN);

		// Final norms: [1024]
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.norm.weight", HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.norm.weight", HIDDEN);

		// net layer 0 attention: [1024, 1024]
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.self_attn.q_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.self_attn.k_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.self_attn.v_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.self_attn.o_proj.weight", HIDDEN * HIDDEN);

		// net layer 0 FFN: gate/up [4096, 1024], down [1024, 4096]
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.mlp.gate_proj.weight",
				NET_FFN_INTERMEDIATE * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.mlp.up_proj.weight",
				NET_FFN_INTERMEDIATE * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.mlp.down_proj.weight",
				HIDDEN * NET_FFN_INTERMEDIATE);

		// net layer 0 norms: [1024]
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.input_layernorm.weight", HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.0.post_attention_layernorm.weight", HIDDEN);

		// net last layer (11) — verify all 12 layers were extracted
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.11.self_attn.q_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net.layers.11.mlp.gate_proj.weight",
				NET_FFN_INTERMEDIATE * HIDDEN);

		// net_token layer 0 attention: [1024, 1024]
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.0.self_attn.q_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.0.self_attn.k_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.0.self_attn.v_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.0.self_attn.o_proj.weight", HIDDEN * HIDDEN);

		// net_token layer 0 FFN: all [1024, 1024]
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.0.mlp.gate_proj.weight",
				NET_TOKEN_FFN_INTERMEDIATE * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.0.mlp.up_proj.weight",
				NET_TOKEN_FFN_INTERMEDIATE * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.0.mlp.down_proj.weight",
				HIDDEN * NET_TOKEN_FFN_INTERMEDIATE);

		// net_token last layer (2)
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.2.self_attn.q_proj.weight", HIDDEN * HIDDEN);
		MoonbeamWeightVerificationTest.assertShape(stateDict,"net_token.layers.2.mlp.gate_proj.weight",
				NET_TOKEN_FFN_INTERMEDIATE * HIDDEN);

		log("All sampled weight shapes verified correctly.");
	}

}
