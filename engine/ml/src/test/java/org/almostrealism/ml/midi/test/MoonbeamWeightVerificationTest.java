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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestDepth;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Verifies that extracted Moonbeam protobuf weights load correctly and
 * match the expected key names, shapes, and value ranges.
 *
 * <p>This test requires the extracted weights in
 * {@code /workspace/project/moonbeam-weights-protobuf/}. It is skipped
 * if the directory does not exist.</p>
 *
 * @see StateDictionary
 * @see MoonbeamConfig#checkpoint309M()
 */
public class MoonbeamWeightVerificationTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/moonbeam-weights-protobuf";

	/**
	 * Verify all expected weight keys are present and have correct shapes.
	 */
	@Test
	@TestDepth(2)
	public void testWeightKeysAndShapes() throws IOException {
		Assume.assumeTrue("Weights directory not found", new File(WEIGHTS_DIR).isDirectory());

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		int hidden = config.hiddenSize;
		int intermediate = config.intermediateSize;
		int kvDim = config.numKvHeads * config.headDim;
		int decoderHidden = config.decoderHiddenSize;
		int decodeVocab = config.decodeVocabSize;
		int embDim = config.embeddingDim;

		// Transformer layer weights
		for (int i = 0; i < config.numLayers; i++) {
			String p = String.format("model.layers.%d", i);
			assertShape(stateDict, p + ".input_layernorm.weight", hidden);
			assertShape(stateDict, p + ".post_attention_layernorm.weight", hidden);
			assertShape(stateDict, p + ".self_attn.q_proj.weight", hidden * hidden);
			assertShape(stateDict, p + ".self_attn.k_proj.weight", kvDim * hidden);
			assertShape(stateDict, p + ".self_attn.v_proj.weight", kvDim * hidden);
			assertShape(stateDict, p + ".self_attn.o_proj.weight", hidden * hidden);
			assertShape(stateDict, p + ".mlp.gate_proj.weight", intermediate * hidden);
			assertShape(stateDict, p + ".mlp.up_proj.weight", intermediate * hidden);
			assertShape(stateDict, p + ".mlp.down_proj.weight", hidden * intermediate);
		}

		assertShape(stateDict, "model.norm.weight", hidden);

		// Embedding weights
		String[] fmePrefixes = {"onset_embedding", "duration_embedding",
				"octave_embedding", "pitch_embedding", "velocity_embedding"};
		for (String prefix : fmePrefixes) {
			assertShape(stateDict, prefix + ".linear.weight", embDim * embDim);
			assertShape(stateDict, prefix + ".linear.bias", embDim);
			assertPresent(stateDict, prefix + ".translation_bias");
		}
		assertShape(stateDict, "instrument_embedding.weight", 131 * embDim);
		assertShape(stateDict, "supplementary_embedding.weight", 2 * hidden);
		assertPresent(stateDict, "supplementary_mlp.0.weight");
		assertPresent(stateDict, "supplementary_mlp.0.bias");
		assertPresent(stateDict, "supplementary_mlp.2.weight");
		assertPresent(stateDict, "supplementary_mlp.2.bias");

		// Decoder weights
		for (int l = 0; l < config.decoderLayers; l++) {
			assertShape(stateDict, String.format("decoder.weight_ih_l%d", l),
					3 * decoderHidden * decoderHidden);
			assertShape(stateDict, String.format("decoder.weight_hh_l%d", l),
					3 * decoderHidden * decoderHidden);
			assertShape(stateDict, String.format("decoder.bias_ih_l%d", l),
					3 * decoderHidden);
			assertShape(stateDict, String.format("decoder.bias_hh_l%d", l),
					3 * decoderHidden);
		}

		assertShape(stateDict, "summary_projection.weight", decoderHidden * hidden);
		assertShape(stateDict, "summary_projection.bias", decoderHidden);
		assertShape(stateDict, "decoder.fc_out.weight", decoderHidden * decoderHidden);
		assertShape(stateDict, "decoder.fc_out.bias", decoderHidden);
		assertShape(stateDict, "lm_head.weight", decodeVocab * decoderHidden);
		assertShape(stateDict, "lm_head.bias", decodeVocab);
		assertShape(stateDict, "decoder_embedding.weight", decodeVocab * decoderHidden);

		log("All " + stateDict.size() + " weight keys verified.");
	}

	/**
	 * Verify no weight tensor contains NaN or infinity values.
	 */
	@Test
	@TestDepth(2)
	public void testNoNanOrInfinity() throws IOException {
		Assume.assumeTrue("Weights directory not found", new File(WEIGHTS_DIR).isDirectory());

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		int checked = 0;

		for (String key : stateDict.keySet()) {
			PackedCollection weight = stateDict.get(key);
			int size = weight.getMemLength();

			for (int i = 0; i < size; i++) {
				double val = weight.toDouble(i);
				Assert.assertFalse(key + " contains NaN at index " + i,
						Double.isNaN(val));
				Assert.assertFalse(key + " contains Infinity at index " + i,
						Double.isInfinite(val));
			}
			checked++;
		}

		log("Verified " + checked + " tensors contain no NaN/Inf.");
	}

	/**
	 * Verify weight value statistics are reasonable (not all zeros, etc.).
	 */
	@Test
	@TestDepth(2)
	public void testWeightStatistics() throws IOException {
		Assume.assumeTrue("Weights directory not found", new File(WEIGHTS_DIR).isDirectory());

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Check a few key weights have non-trivial values
		String[] checkKeys = {
				"model.layers.0.self_attn.q_proj.weight",
				"onset_embedding.linear.weight",
				"decoder.weight_ih_l0",
				"lm_head.weight"
		};

		for (String key : checkKeys) {
			PackedCollection weight = stateDict.get(key);
			Assert.assertNotNull("Missing weight: " + key, weight);

			int size = weight.getMemLength();
			double min = Double.MAX_VALUE;
			double max = -Double.MAX_VALUE;
			double sum = 0.0;
			int nonZero = 0;

			for (int i = 0; i < size; i++) {
				double val = weight.toDouble(i);
				if (val < min) min = val;
				if (val > max) max = val;
				sum += val;
				if (val != 0.0) nonZero++;
			}

			double mean = sum / size;
			log(String.format("  %s: min=%.6f, max=%.6f, mean=%.6f, nonZero=%d/%d",
					key, min, max, mean, nonZero, size));

			Assert.assertTrue(key + " appears to be all zeros",
					nonZero > size / 10);
			Assert.assertTrue(key + " has suspiciously large values (max=" + max + ")",
					max < 1000.0);
			Assert.assertTrue(key + " has suspiciously large values (min=" + min + ")",
					min > -1000.0);
		}
	}

	/** Assert a weight key exists and has the expected total element count. */
	private void assertShape(StateDictionary stateDict, String key, int expectedSize) {
		PackedCollection weight = stateDict.get(key);
		Assert.assertNotNull("Missing weight key: " + key, weight);
		Assert.assertEquals("Wrong size for " + key, expectedSize, weight.getMemLength());
	}

	/** Assert a weight key exists. */
	private void assertPresent(StateDictionary stateDict, String key) {
		Assert.assertNotNull("Missing weight key: " + key, stateDict.get(key));
	}
}
