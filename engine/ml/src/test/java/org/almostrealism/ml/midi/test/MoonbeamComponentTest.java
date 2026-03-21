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
import org.almostrealism.ml.midi.CompoundMidiEmbedding;
import org.almostrealism.ml.midi.FundamentalMusicEmbedding;
import org.almostrealism.ml.midi.GRUCell;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.ml.midi.MidiAutoregressiveModel;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Isolated component tests for the Moonbeam MIDI model, each with an explicit
 * timeout. The purpose of this test class is to identify which component is the
 * performance bottleneck when running end-to-end MIDI generation.
 *
 * <p>Each test exercises a single computational component in isolation with
 * timing output so that CI results immediately reveal where time is spent.
 * Tests that can run with synthetic (random) weights do so; tests that require
 * real weights are skipped via {@code Assume.assumeTrue} if weights are absent.</p>
 *
 * @see MoonbeamMidi
 * @see CompoundMidiEmbedding
 * @see FundamentalMusicEmbedding
 * @see GRUCell
 * @see GRUDecoder
 * @see HeadGroupConfig
 */
public class MoonbeamComponentTest extends TestSuiteBase {

	/* ------------------------------------------------------------ */
	/*  Test 1: Weight Loading                                      */
	/* ------------------------------------------------------------ */

	/**
	 * Verify that StateDictionary can be created from a synthetic weight map,
	 * and that expected key patterns are present and have correct shapes.
	 * Uses synthetic weights so it always runs in CI.
	 */
	@Test(timeout = 60_000)
	public void testWeightLoading() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);

		int expectedKeys = config.numLayers * 9 + 1; // 9 per layer + final norm
		Assert.assertEquals("Key count", expectedKeys, stateDict.keySet().size());

		Assert.assertTrue("q_proj key exists",
				stateDict.containsKey("model.layers.0.self_attn.q_proj.weight"));
		Assert.assertTrue("final norm key exists",
				stateDict.containsKey("model.norm.weight"));

		PackedCollection qWeight = stateDict.get("model.layers.0.self_attn.q_proj.weight");
		Assert.assertEquals("q_proj row count", config.hiddenSize,
				qWeight.getShape().length(0));

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testWeightLoading: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 2: Single Embedding                                    */
	/* ------------------------------------------------------------ */

	/**
	 * Create a CompoundMidiEmbedding with synthetic weights and embed a
	 * single compound token. Validates output shape is (hiddenSize).
	 */
	@Test(timeout = 10_000)
	public void testSingleEmbedding() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		MidiCompoundToken token = new MidiCompoundToken(100, 50, 5, 7, 0, 80);
		PackedCollection result = embedding.embed(token);

		Assert.assertNotNull("Embedding result should not be null", result);
		Assert.assertEquals("Embedding output size", config.hiddenSize,
				result.getShape().getTotalSize());

		// Check finiteness
		for (int i = 0; i < config.hiddenSize; i++) {
			Assert.assertTrue("Value at " + i + " should be finite",
					Double.isFinite(result.toDouble(i)));
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleEmbedding: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 3: Single FME Computation                              */
	/* ------------------------------------------------------------ */

	/**
	 * Compute a FundamentalMusicEmbedding for a single attribute value.
	 * Validates output shape and finiteness.
	 */
	@Test(timeout = 5_000)
	public void testSingleFmeComputation() {
		long start = System.currentTimeMillis();

		int dim = 320;
		double base = 199999.0;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(base, dim);

		PackedCollection result = fme.embed(42);

		Assert.assertNotNull("FME result should not be null", result);
		Assert.assertEquals("FME output dimension", dim,
				result.getShape().getTotalSize());

		for (int i = 0; i < dim; i++) {
			Assert.assertTrue("FME value at " + i + " should be finite",
					Double.isFinite(result.toDouble(i)));
		}

		// Also test the sinusoidal encoding stage separately
		PackedCollection sincos = fme.encodeSinusoidal(42);
		Assert.assertEquals("Sinusoidal encoding dimension", dim,
				sincos.getShape().getTotalSize());

		for (int i = 0; i < dim; i++) {
			double val = sincos.toDouble(i);
			Assert.assertTrue("Sinusoidal value at " + i + " should be in [-1,1]",
					val >= -1.0 && val <= 1.0);
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleFmeComputation: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 4: Single Attention Layer                              */
	/* ------------------------------------------------------------ */

	/**
	 * One transformer layer forward pass with MRA attention using a single-layer
	 * config. Exercises model build + one autoregressive step through the layer.
	 */
	@Test(timeout = 30_000)
	public void testSingleAttentionLayer() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = new MoonbeamConfig(
				48, 144,
				1, // single layer
				6, 6, 8,
				32, 2,
				8487, 128, 1e-5,
				new double[]{199999, 1031, 19, 20, 199999, 131},
				new int[]{1, 1, 1, 1, 1, 1},
				new int[]{4099, 4099, 13, 14, 131, 130},
				new double[]{199999, 1031, 19, 20, 199999, 131},
				2);

		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;
		System.out.println("[MoonbeamComponentTest] single layer model build: " + buildTime + " ms");

		Assert.assertNotNull("Model should compile", model.getCompiledTransformer());

		// Forward a single token via the autoregressive model
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		long fwdStart = System.currentTimeMillis();
		MidiCompoundToken result = autoregressive.next();
		long fwdTime = System.currentTimeMillis() - fwdStart;
		System.out.println("[MoonbeamComponentTest] single layer forward+decode: " + fwdTime + " ms");

		Assert.assertNotNull("Forward should produce a token", result);

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleAttentionLayer: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 5: RoPE Frequency Computation                          */
	/* ------------------------------------------------------------ */

	/**
	 * Compute freqCis for each of the 6 theta values used by Moonbeam MRA.
	 * Validates shapes and that all values are finite.
	 */
	@Test(timeout = 5_000)
	public void testRopeFrequencyComputation() {
		long start = System.currentTimeMillis();

		double[] thetas = {199999, 1031, 19, 20, 199999, 131};
		int headDim = 160;
		int maxSeqLen = 256; // Small for test, default is 8192

		for (int g = 0; g < thetas.length; g++) {
			long groupStart = System.currentTimeMillis();
			PackedCollection freqCis = HeadGroupConfig.computeFreqCis(thetas[g], headDim, maxSeqLen);
			long groupTime = System.currentTimeMillis() - groupStart;

			int expectedSize = maxSeqLen * (headDim / 2) * 2;
			Assert.assertEquals("FreqCis size for theta=" + thetas[g],
					expectedSize, freqCis.getShape().getTotalSize());

			// Spot-check finiteness on first and last positions
			for (int pos : new int[]{0, maxSeqLen - 1}) {
				int freqDim = headDim / 2;
				for (int f = 0; f < freqDim; f++) {
					int idx = (pos * freqDim + f) * 2;
					double cos = freqCis.toDouble(idx);
					double sin = freqCis.toDouble(idx + 1);
					Assert.assertTrue("cos finite at pos=" + pos + " f=" + f,
							Double.isFinite(cos));
					Assert.assertTrue("sin finite at pos=" + pos + " f=" + f,
							Double.isFinite(sin));
					Assert.assertTrue("cos in [-1,1]", cos >= -1.0 && cos <= 1.0);
					Assert.assertTrue("sin in [-1,1]", sin >= -1.0 && sin <= 1.0);
				}
			}

			System.out.println("[MoonbeamComponentTest] freqCis theta=" + thetas[g]
					+ ": " + groupTime + " ms");
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testRopeFrequencyComputation: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 6: Single GRU Cell Step                                */
	/* ------------------------------------------------------------ */

	/**
	 * One GRUCell forward pass with small synthetic weights.
	 * Validates output shape and finiteness.
	 */
	@Test(timeout = 5_000)
	public void testSingleGruCellStep() {
		long start = System.currentTimeMillis();

		int inputSize = 32;
		int hiddenSize = 32;
		Random rng = new Random(42);

		PackedCollection weightIh = createRandomCollection(rng, 3 * hiddenSize, inputSize);
		PackedCollection weightHh = createRandomCollection(rng, 3 * hiddenSize, hiddenSize);
		PackedCollection biasIh = createRandomCollection(rng, 3 * hiddenSize);
		PackedCollection biasHh = createRandomCollection(rng, 3 * hiddenSize);

		GRUCell cell = new GRUCell(inputSize, hiddenSize, weightIh, weightHh, biasIh, biasHh);

		PackedCollection x = createRandomCollection(rng, inputSize);
		PackedCollection h = createRandomCollection(rng, hiddenSize);

		long fwdStart = System.currentTimeMillis();
		PackedCollection hNew = cell.forward(x, h);
		long fwdTime = System.currentTimeMillis() - fwdStart;

		Assert.assertEquals("GRU output size", hiddenSize, hNew.getShape().getTotalSize());
		for (int i = 0; i < hiddenSize; i++) {
			Assert.assertTrue("GRU output at " + i + " should be finite",
					Double.isFinite(hNew.toDouble(i)));
		}

		System.out.println("[MoonbeamComponentTest] GRU cell forward: " + fwdTime + " ms");

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleGruCellStep: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 7: GRU Decoder (single position)                      */
	/* ------------------------------------------------------------ */

	/**
	 * GRUDecoder decoding 7 tokens from one hidden state using synthetic weights.
	 * Validates output contains 7 token indices within valid range.
	 */
	@Test(timeout = 15_000)
	public void testGruDecoderSinglePosition() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		GRUDecoder decoder = createSyntheticDecoder(config);

		// Create a random hidden state vector of size hiddenSize
		Random rng = new Random(42);
		PackedCollection hidden = createRandomCollection(rng, config.hiddenSize);

		long decodeStart = System.currentTimeMillis();
		int[] tokens = decoder.decode(hidden);
		long decodeTime = System.currentTimeMillis() - decodeStart;

		Assert.assertEquals("Should produce 7 tokens",
				GRUDecoder.TOKENS_PER_NOTE, tokens.length);

		for (int i = 0; i < tokens.length; i++) {
			Assert.assertTrue("Token " + i + " should be >= 0", tokens[i] >= 0);
			Assert.assertTrue("Token " + i + " should be < decodeVocabSize",
					tokens[i] < config.decodeVocabSize);
		}

		// Verify toAttributeValues also works
		int[] attrValues = decoder.toAttributeValues(tokens);
		Assert.assertEquals("Should have 7 attribute values",
				GRUDecoder.TOKENS_PER_NOTE, attrValues.length);

		System.out.println("[MoonbeamComponentTest] GRU decode: " + decodeTime + " ms");
		System.out.println("[MoonbeamComponentTest] Decoded tokens: "
				+ java.util.Arrays.toString(tokens));

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testGruDecoderSinglePosition: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 8: Two-Layer Forward Pass                              */
	/* ------------------------------------------------------------ */

	/**
	 * Build a model with 2 transformer layers using the test config,
	 * forward one token through embedding -> 2 layers -> norm -> decode.
	 * Validates that the full pipeline produces a valid token.
	 */
	@Test(timeout = 60_000)
	public void testTwoLayerForwardPass() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig(); // already 2 layers
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;
		System.out.println("[MoonbeamComponentTest] 2-layer model build: " + buildTime + " ms");

		// Forward a token via the autoregressive API
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
		MidiCompoundToken[] prompt = new MidiCompoundToken[]{
				MidiCompoundToken.sos(),
				new MidiCompoundToken(100, 50, 5, 7, 0, 80)
		};
		autoregressive.setPrompt(prompt);

		// Process prompt tokens
		for (int i = 0; i < prompt.length; i++) {
			long fwdStart = System.currentTimeMillis();
			MidiCompoundToken result = autoregressive.next();
			long fwdTime = System.currentTimeMillis() - fwdStart;
			System.out.println("[MoonbeamComponentTest] 2-layer prompt token " + i
					+ " forward: " + fwdTime + " ms");
			Assert.assertNotNull("Prompt token " + i + " should not be null", result);
		}

		// Generate one token
		long genStart = System.currentTimeMillis();
		MidiCompoundToken generated = autoregressive.next();
		long genTime = System.currentTimeMillis() - genStart;
		System.out.println("[MoonbeamComponentTest] 2-layer generate: " + genTime + " ms");

		Assert.assertNotNull("Generated token should not be null", generated);

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testTwoLayerForwardPass: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 9: Single Autoregressive Step                          */
	/* ------------------------------------------------------------ */

	/**
	 * Full model forward + GRU decode for ONE token using the test config.
	 * This is the atomic unit of generation -- if this is slow, generation
	 * will never work.
	 */
	@Test(timeout = 120_000)
	public void testSingleAutoregressiveStep() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;
		System.out.println("[MoonbeamComponentTest] model build: " + buildTime + " ms");

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		// Generate a single token (unconditional, from SOS)
		long stepStart = System.currentTimeMillis();
		MidiCompoundToken result = autoregressive.next();
		long stepTime = System.currentTimeMillis() - stepStart;

		Assert.assertNotNull("Should produce a token", result);
		Assert.assertFalse("Token should not be special", result.isPAD());

		System.out.println("[MoonbeamComponentTest] single autoregressive step: " + stepTime + " ms");
		System.out.println("[MoonbeamComponentTest] generated token: onset="
				+ result.getOnset() + " dur=" + result.getDuration()
				+ " oct=" + result.getOctave() + " pc=" + result.getPitchClass()
				+ " inst=" + result.getInstrument() + " vel=" + result.getVelocity());

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleAutoregressiveStep: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 10: Small Sequence Generation                          */
	/* ------------------------------------------------------------ */

	/**
	 * Generate 5 tokens from SOS using the test config.
	 * If this times out, per-token cost is too high for practical generation.
	 */
	@Test(timeout = 300_000)
	public void testSmallSequenceGeneration() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		int numTokens = 5;
		long genStart = System.currentTimeMillis();
		List<MidiCompoundToken> generated = autoregressive.generate(numTokens);
		long genTime = System.currentTimeMillis() - genStart;

		Assert.assertFalse("Should generate at least one token", generated.isEmpty());
		Assert.assertTrue("Should generate at most " + numTokens + " tokens",
				generated.size() <= numTokens);

		// Print per-token timing
		double perToken = generated.isEmpty() ? 0 : (double) genTime / generated.size();
		System.out.println("[MoonbeamComponentTest] generated " + generated.size()
				+ " tokens in " + genTime + " ms ("
				+ String.format("%.1f", perToken) + " ms/token)");

		for (int i = 0; i < generated.size(); i++) {
			MidiCompoundToken t = generated.get(i);
			System.out.println("[MoonbeamComponentTest] token " + i + ": onset="
					+ t.getOnset() + " dur=" + t.getDuration()
					+ " oct=" + t.getOctave() + " pc=" + t.getPitchClass()
					+ " inst=" + t.getInstrument() + " vel=" + t.getVelocity()
					+ (t.isEOS() ? " [EOS]" : ""));
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSmallSequenceGeneration: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 11: FME Multiple Bases                                 */
	/* ------------------------------------------------------------ */

	/**
	 * Test FME computation for all 6 attribute bases used by Moonbeam.
	 * Ensures none of them produce NaN or infinite values.
	 */
	@Test(timeout = 5_000)
	public void testFmeAllBases() {
		long start = System.currentTimeMillis();

		double[] bases = {199999, 1031, 19, 20, 199999, 131};
		String[] names = {"onset", "duration", "octave", "pitch", "instrument", "velocity"};
		int dim = 8; // small for test speed

		for (int b = 0; b < bases.length; b++) {
			long baseStart = System.currentTimeMillis();
			FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(bases[b], dim);

			// Test a range of values
			for (int val : new int[]{0, 1, 100, 4098}) {
				PackedCollection result = fme.embed(val);
				Assert.assertEquals("Dimension for " + names[b],
						dim, result.getShape().getTotalSize());
				for (int i = 0; i < dim; i++) {
					Assert.assertTrue(names[b] + " value=" + val + " idx=" + i
									+ " should be finite",
							Double.isFinite(result.toDouble(i)));
				}
			}

			long baseTime = System.currentTimeMillis() - baseStart;
			System.out.println("[MoonbeamComponentTest] FME " + names[b]
					+ " (base=" + bases[b] + "): " + baseTime + " ms");
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testFmeAllBases: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 12: GRU Decoder with Sampling                          */
	/* ------------------------------------------------------------ */

	/**
	 * Test GRU decoder with temperature and top-p sampling to ensure
	 * sampling path does not hang or produce invalid tokens.
	 */
	@Test(timeout = 15_000)
	public void testGruDecoderWithSampling() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		GRUDecoder decoder = createSyntheticDecoder(config);

		Random rng = new Random(42);
		PackedCollection hidden = createRandomCollection(rng, config.hiddenSize);

		long decodeStart = System.currentTimeMillis();
		int[] tokens = decoder.decode(hidden, 0.8, 0.95, new Random(123));
		long decodeTime = System.currentTimeMillis() - decodeStart;

		Assert.assertEquals("Should produce 7 tokens",
				GRUDecoder.TOKENS_PER_NOTE, tokens.length);
		for (int i = 0; i < tokens.length; i++) {
			Assert.assertTrue("Sampled token " + i + " in valid range",
					tokens[i] >= 0 && tokens[i] < config.decodeVocabSize);
		}

		System.out.println("[MoonbeamComponentTest] GRU decode with sampling: " + decodeTime + " ms");

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testGruDecoderWithSampling: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 13: Embedding Special Tokens                           */
	/* ------------------------------------------------------------ */

	/**
	 * Verify that SOS, EOS, and PAD tokens embed correctly and quickly.
	 */
	@Test(timeout = 5_000)
	public void testEmbeddingSpecialTokens() {
		long start = System.currentTimeMillis();

		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		// SOS
		long sosStart = System.currentTimeMillis();
		PackedCollection sosEmb = embedding.embed(MidiCompoundToken.sos());
		long sosTime = System.currentTimeMillis() - sosStart;
		Assert.assertEquals("SOS size", config.hiddenSize, sosEmb.getShape().getTotalSize());
		System.out.println("[MoonbeamComponentTest] SOS embed: " + sosTime + " ms");

		// EOS
		long eosStart = System.currentTimeMillis();
		PackedCollection eosEmb = embedding.embed(MidiCompoundToken.eos());
		long eosTime = System.currentTimeMillis() - eosStart;
		Assert.assertEquals("EOS size", config.hiddenSize, eosEmb.getShape().getTotalSize());
		System.out.println("[MoonbeamComponentTest] EOS embed: " + eosTime + " ms");

		// PAD
		long padStart = System.currentTimeMillis();
		PackedCollection padEmb = embedding.embed(MidiCompoundToken.pad());
		long padTime = System.currentTimeMillis() - padStart;
		Assert.assertEquals("PAD size", config.hiddenSize, padEmb.getShape().getTotalSize());
		// PAD should be all zeros
		for (int i = 0; i < config.hiddenSize; i++) {
			Assert.assertEquals("PAD value at " + i, 0.0, padEmb.toDouble(i), 0.0);
		}
		System.out.println("[MoonbeamComponentTest] PAD embed: " + padTime + " ms");

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testEmbeddingSpecialTokens: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Helpers                                                     */
	/* ------------------------------------------------------------ */

	/**
	 * Create a StateDictionary with synthetic weights for all transformer
	 * layer parameters. RMSNorm weights are ones so norms don't zero out.
	 */
	private static StateDictionary createSyntheticWeights(MoonbeamConfig config) {
		Map<String, PackedCollection> weights = new HashMap<>();
		int dim = config.hiddenSize;
		int kvDim = dim * config.numKvHeads / config.numHeads;
		int ffnDim = config.intermediateSize;

		for (int i = 0; i < config.numLayers; i++) {
			String prefix = String.format("model.layers.%d", i);
			weights.put(prefix + ".input_layernorm.weight", onesCollection(dim));
			weights.put(prefix + ".post_attention_layernorm.weight", onesCollection(dim));
			weights.put(prefix + ".self_attn.q_proj.weight",
					new PackedCollection(new TraversalPolicy(dim, dim)));
			weights.put(prefix + ".self_attn.k_proj.weight",
					new PackedCollection(new TraversalPolicy(kvDim, dim)));
			weights.put(prefix + ".self_attn.v_proj.weight",
					new PackedCollection(new TraversalPolicy(kvDim, dim)));
			weights.put(prefix + ".self_attn.o_proj.weight",
					new PackedCollection(new TraversalPolicy(dim, dim)));
			weights.put(prefix + ".mlp.gate_proj.weight",
					new PackedCollection(new TraversalPolicy(ffnDim, dim)));
			weights.put(prefix + ".mlp.down_proj.weight",
					new PackedCollection(new TraversalPolicy(dim, ffnDim)));
			weights.put(prefix + ".mlp.up_proj.weight",
					new PackedCollection(new TraversalPolicy(ffnDim, dim)));
		}

		weights.put("model.norm.weight", onesCollection(dim));

		return new StateDictionary(weights);
	}

	/**
	 * Create a GRU decoder with synthetic (zero-initialized) weights.
	 */
	private static GRUDecoder createSyntheticDecoder(MoonbeamConfig config) {
		int hidden = config.hiddenSize;
		int decoderHidden = config.decoderHiddenSize;
		int vocabSize = config.decodeVocabSize;

		GRUCell[] layers = new GRUCell[config.decoderLayers];
		for (int l = 0; l < config.decoderLayers; l++) {
			layers[l] = new GRUCell(
					decoderHidden, decoderHidden,
					new PackedCollection(new TraversalPolicy(3 * decoderHidden, decoderHidden)),
					new PackedCollection(new TraversalPolicy(3 * decoderHidden, decoderHidden)),
					new PackedCollection(new TraversalPolicy(3 * decoderHidden)),
					new PackedCollection(new TraversalPolicy(3 * decoderHidden)));
		}

		return new GRUDecoder(config, layers,
				new PackedCollection(new TraversalPolicy(decoderHidden, hidden)),
				new PackedCollection(new TraversalPolicy(decoderHidden)),
				new PackedCollection(new TraversalPolicy(vocabSize, decoderHidden)),
				new PackedCollection(new TraversalPolicy(vocabSize)),
				new PackedCollection(new TraversalPolicy(vocabSize, decoderHidden)));
	}

	/**
	 * Create a PackedCollection filled with ones.
	 */
	private static PackedCollection onesCollection(int size) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(size));
		for (int i = 0; i < size; i++) {
			collection.setMem(i, 1.0);
		}
		return collection;
	}

	/**
	 * Create a PackedCollection with random values scaled by 0.02 (typical weight init).
	 */
	private static PackedCollection createRandomCollection(Random rng, int... dims) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(dims));
		int total = collection.getShape().getTotalSize();
		for (int i = 0; i < total; i++) {
			collection.setMem(i, rng.nextGaussian() * 0.02);
		}
		return collection;
	}

	/**
	 * Create a random input vector of the given size.
	 */
	private static PackedCollection createRandomInput(int size) {
		Random rng = new Random(42);
		PackedCollection input = new PackedCollection(new TraversalPolicy(size));
		for (int i = 0; i < size; i++) {
			input.setMem(i, rng.nextGaussian() * 0.1);
		}
		return input;
	}
}
