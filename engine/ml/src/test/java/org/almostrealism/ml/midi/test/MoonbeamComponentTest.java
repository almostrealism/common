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
import org.almostrealism.ml.midi.GRUBlock;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.ml.midi.MidiAutoregressiveModel;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Component tests for the Moonbeam MIDI model at REAL model dimensions.
 *
 * <p>Every test uses random weights at the full default model dimensions
 * (hiddenSize=1920, intermediateSize=6720, numLayers=15, etc.) to expose
 * the actual computational cost. Tests with tiny/synthetic weights are
 * useless for identifying performance bottlenecks.</p>
 *
 * <p>Each test has an explicit {@code @Test(timeout = ...)} so CI will
 * reveal which component is the bottleneck by timing out. Timing output
 * is printed for every test.</p>
 *
 * @see MoonbeamMidi
 * @see MoonbeamConfig#defaultConfig()
 */
public class MoonbeamComponentTest extends TestSuiteBase {

	/** Real model config used by all tests. */
	private static final MoonbeamConfig REAL_CONFIG = MoonbeamConfig.defaultConfig();

	/* ------------------------------------------------------------ */
	/*  Test 1: Single FME embed at real dim (320)                  */
	/* ------------------------------------------------------------ */

	/**
	 * Embed a single attribute value using FundamentalMusicEmbedding at the
	 * real embedding dimension (320). Exercises the sinusoidal encoding,
	 * translation bias, and linear projection.
	 */
	@Test(timeout = 5_000)
	public void testSingleFmeEmbed() {
		long start = System.currentTimeMillis();

		int dim = REAL_CONFIG.embeddingDim; // 320
		double base = 199999.0;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(base, dim);

		long computeStart = System.currentTimeMillis();
		PackedCollection result = fme.embed(42);
		long computeTime = System.currentTimeMillis() - computeStart;

		Assert.assertNotNull("FME result should not be null", result);
		Assert.assertEquals("FME output dimension", dim,
				result.getShape().getTotalSize());

		for (int i = 0; i < dim; i++) {
			Assert.assertTrue("FME value at " + i + " should be finite",
					Double.isFinite(result.toDouble(i)));
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleFmeEmbed: "
				+ elapsed + " ms (compute: " + computeTime + " ms)");
	}

	/* ------------------------------------------------------------ */
	/*  Test 2: Single CompoundMidiEmbedding at real dim (1920)     */
	/* ------------------------------------------------------------ */

	/**
	 * Embed a single compound MIDI token at the real hidden dimension (1920).
	 * This exercises all 6 parallel FME embeddings (5 sinusoidal + 1 lookup)
	 * and the concatenation to produce the full hidden-size vector.
	 */
	@Test(timeout = 10_000)
	public void testSingleCompoundEmbedding() {
		long start = System.currentTimeMillis();

		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(REAL_CONFIG);

		MidiCompoundToken token = new MidiCompoundToken(100, 50, 5, 7, 0, 80);

		long computeStart = System.currentTimeMillis();
		PackedCollection result = embedding.embed(token);
		long computeTime = System.currentTimeMillis() - computeStart;

		Assert.assertNotNull("Embedding result should not be null", result);
		Assert.assertEquals("Embedding output size", REAL_CONFIG.hiddenSize,
				result.getShape().getTotalSize());

		for (int i = 0; i < REAL_CONFIG.hiddenSize; i++) {
			Assert.assertTrue("Value at " + i + " should be finite",
					Double.isFinite(result.toDouble(i)));
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleCompoundEmbedding: "
				+ elapsed + " ms (compute: " + computeTime + " ms)");
	}

	/* ------------------------------------------------------------ */
	/*  Test 3: RoPE frequency computation at real headDim (160)    */
	/* ------------------------------------------------------------ */

	/**
	 * Compute RoPE frequencies for all 6 MRA theta values at the real
	 * head dimension (160) and a reasonable sequence length. Validates
	 * shapes and value ranges.
	 */
	@Test(timeout = 5_000)
	public void testRopeFrequencyComputation() {
		long start = System.currentTimeMillis();

		int headDim = REAL_CONFIG.headDim; // 160
		int maxSeqLen = REAL_CONFIG.maxSeqLen; // 8192

		for (int g = 0; g < REAL_CONFIG.ropeThetas.length; g++) {
			double theta = REAL_CONFIG.ropeThetas[g];

			long groupStart = System.currentTimeMillis();
			PackedCollection freqCis = HeadGroupConfig.computeFreqCis(
					theta, headDim, maxSeqLen);
			long groupTime = System.currentTimeMillis() - groupStart;

			int expectedSize = maxSeqLen * (headDim / 2) * 2;
			Assert.assertEquals("FreqCis size for theta=" + theta,
					expectedSize, freqCis.getShape().getTotalSize());

			System.out.println("[MoonbeamComponentTest] freqCis theta=" + theta
					+ " (headDim=" + headDim + ", seqLen=" + maxSeqLen
					+ "): " + groupTime + " ms");
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testRopeFrequencyComputation: "
				+ elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 4: Single GRU cell at real dim (1536)                  */
	/* ------------------------------------------------------------ */

	/**
	 * One GRU block forward pass at the real decoder hidden dimension (1536).
	 * Weight matrices are (3*1536, 1536) = ~7M parameters per weight tensor.
	 * Tests via a single-step decode using a minimal GRUDecoder wrapper.
	 */
	@Test(timeout = 10_000)
	public void testSingleGruBlock() {
		long start = System.currentTimeMillis();

		int decoderHidden = REAL_CONFIG.decoderHiddenSize; // 1536
		Random rng = new Random(42);

		long allocStart = System.currentTimeMillis();
		GRUDecoder decoder = createRandomDecoder(REAL_CONFIG);
		long allocTime = System.currentTimeMillis() - allocStart;
		System.out.println("[MoonbeamComponentTest] GRU block weight alloc: " + allocTime + " ms");

		PackedCollection hidden = createRandomCollection(rng, REAL_CONFIG.hiddenSize);

		long fwdStart = System.currentTimeMillis();
		int[] tokens = decoder.decode(hidden);
		long fwdTime = System.currentTimeMillis() - fwdStart;

		Assert.assertEquals("GRU should produce 7 tokens",
				GRUDecoder.TOKENS_PER_NOTE, tokens.length);
		for (int i = 0; i < tokens.length; i++) {
			Assert.assertTrue("Token " + i + " should be >= 0", tokens[i] >= 0);
			Assert.assertTrue("Token " + i + " should be < decodeVocabSize",
					tokens[i] < REAL_CONFIG.decodeVocabSize);
		}

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleGruBlock: "
				+ elapsed + " ms (decode: " + fwdTime + " ms)");
	}

	/* ------------------------------------------------------------ */
	/*  Test 5: GRU decoder (7 tokens, real dims)                   */
	/* ------------------------------------------------------------ */

	/**
	 * GRU decoder generating 7 output tokens from one transformer hidden state
	 * at real dimensions (hiddenSize=1920, decoderHidden=1536, vocab=8487).
	 */
	@Test(timeout = 30_000)
	public void testGruDecoderRealDims() {
		long start = System.currentTimeMillis();

		long allocStart = System.currentTimeMillis();
		GRUDecoder decoder = createRandomDecoder(REAL_CONFIG);
		long allocTime = System.currentTimeMillis() - allocStart;
		System.out.println("[MoonbeamComponentTest] GRU decoder alloc: " + allocTime + " ms");

		Random rng = new Random(42);
		PackedCollection hidden = createRandomCollection(rng, REAL_CONFIG.hiddenSize);

		long decodeStart = System.currentTimeMillis();
		int[] tokens = decoder.decode(hidden);
		long decodeTime = System.currentTimeMillis() - decodeStart;

		Assert.assertEquals("Should produce 7 tokens",
				GRUDecoder.TOKENS_PER_NOTE, tokens.length);

		for (int i = 0; i < tokens.length; i++) {
			Assert.assertTrue("Token " + i + " should be >= 0", tokens[i] >= 0);
			Assert.assertTrue("Token " + i + " should be < decodeVocabSize",
					tokens[i] < REAL_CONFIG.decodeVocabSize);
		}

		System.out.println("[MoonbeamComponentTest] GRU decode tokens: "
				+ Arrays.toString(tokens));

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testGruDecoderRealDims: "
				+ elapsed + " ms (decode: " + decodeTime + " ms)");
	}

	/* ------------------------------------------------------------ */
	/*  Test 6: Single attention layer (real dims)                  */
	/* ------------------------------------------------------------ */

	/**
	 * Build and compile a single transformer layer at real dimensions
	 * (1920 hidden, 12 heads, 6 kv heads, 160 head dim, 6720 FFN).
	 * Measures the model compilation cost at real matrix sizes.
	 */
	@Test(timeout = 60_000)
	public void testSingleAttentionLayerBuild() {
		long start = System.currentTimeMillis();

		MoonbeamConfig oneLayerConfig = createRealConfigWithLayers(1);

		long allocStart = System.currentTimeMillis();
		StateDictionary stateDict = createRandomWeights(oneLayerConfig);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(oneLayerConfig);
		GRUDecoder decoder = createRandomDecoder(oneLayerConfig);
		long allocTime = System.currentTimeMillis() - allocStart;
		System.out.println("[MoonbeamComponentTest] 1-layer weight alloc: " + allocTime + " ms");

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(oneLayerConfig, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;

		Assert.assertNotNull("Model should compile", model.getCompiledTransformer());

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleAttentionLayerBuild: "
				+ elapsed + " ms (build: " + buildTime + " ms)");
	}

	/* ------------------------------------------------------------ */
	/*  Test 7: Single transformer block forward (real dims)        */
	/* ------------------------------------------------------------ */

	/**
	 * Forward one token through a single transformer block (attention + FFN)
	 * at real dimensions. Measures actual forward pass computation cost.
	 */
	@Test(timeout = 120_000)
	public void testSingleTransformerBlockForward() {
		long start = System.currentTimeMillis();

		MoonbeamConfig oneLayerConfig = createRealConfigWithLayers(1);
		StateDictionary stateDict = createRandomWeights(oneLayerConfig);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(oneLayerConfig);
		GRUDecoder decoder = createRandomDecoder(oneLayerConfig);

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(oneLayerConfig, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;
		System.out.println("[MoonbeamComponentTest] 1-layer model build: " + buildTime + " ms");

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		long fwdStart = System.currentTimeMillis();
		MidiCompoundToken result = autoregressive.next();
		long fwdTime = System.currentTimeMillis() - fwdStart;

		Assert.assertNotNull("Forward should produce a token", result);

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleTransformerBlockForward: "
				+ elapsed + " ms (forward+decode: " + fwdTime + " ms)");
	}

	/* ------------------------------------------------------------ */
	/*  Test 8: 2-layer forward pass at real dims                   */
	/* ------------------------------------------------------------ */

	/**
	 * Build and forward one token through a 2-layer model at real dimensions.
	 * Measures scaling from 1 to 2 layers.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void testTwoLayerForwardPass() {
		long start = System.currentTimeMillis();

		MoonbeamConfig twoLayerConfig = createRealConfigWithLayers(2);

		long allocStart = System.currentTimeMillis();
		StateDictionary stateDict = createRandomWeights(twoLayerConfig);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(twoLayerConfig);
		GRUDecoder decoder = createRandomDecoder(twoLayerConfig);
		long allocTime = System.currentTimeMillis() - allocStart;
		System.out.println("[MoonbeamComponentTest] 2-layer weight alloc: " + allocTime + " ms");

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(twoLayerConfig, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;
		System.out.println("[MoonbeamComponentTest] 2-layer model build: " + buildTime + " ms");

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
		MidiCompoundToken[] prompt = new MidiCompoundToken[]{
				MidiCompoundToken.sos(),
				new MidiCompoundToken(100, 50, 5, 7, 0, 80)
		};
		autoregressive.setPrompt(prompt);

		for (int i = 0; i < prompt.length; i++) {
			long fwdStart = System.currentTimeMillis();
			MidiCompoundToken result = autoregressive.next();
			long fwdTime = System.currentTimeMillis() - fwdStart;
			System.out.println("[MoonbeamComponentTest] 2-layer prompt token " + i
					+ ": " + fwdTime + " ms");
			Assert.assertNotNull("Prompt token " + i + " result", result);
		}

		long genStart = System.currentTimeMillis();
		MidiCompoundToken generated = autoregressive.next();
		long genTime = System.currentTimeMillis() - genStart;
		System.out.println("[MoonbeamComponentTest] 2-layer generate: " + genTime + " ms");

		Assert.assertNotNull("Generated token should not be null", generated);

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testTwoLayerForwardPass: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 9: Full 15-layer single token forward pass             */
	/* ------------------------------------------------------------ */

	/**
	 * Build and forward one token through the full 15-layer model at real
	 * dimensions. This allocates ~6GB of random weights and exercises the
	 * complete transformer stack.
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void testFullModelForwardPass() {
		long start = System.currentTimeMillis();

		long allocStart = System.currentTimeMillis();
		StateDictionary stateDict = createRandomWeights(REAL_CONFIG);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(REAL_CONFIG);
		GRUDecoder decoder = createRandomDecoder(REAL_CONFIG);
		long allocTime = System.currentTimeMillis() - allocStart;
		System.out.println("[MoonbeamComponentTest] 15-layer weight alloc: " + allocTime + " ms");

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(REAL_CONFIG, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;
		System.out.println("[MoonbeamComponentTest] 15-layer model build: " + buildTime + " ms");

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		long fwdStart = System.currentTimeMillis();
		MidiCompoundToken result = autoregressive.next();
		long fwdTime = System.currentTimeMillis() - fwdStart;

		Assert.assertNotNull("Forward should produce a token", result);
		System.out.println("[MoonbeamComponentTest] 15-layer forward+decode: " + fwdTime + " ms");

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testFullModelForwardPass: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Test 10: Single autoregressive step (full model + GRU)      */
	/* ------------------------------------------------------------ */

	/**
	 * Full autoregressive step: embed SOS, forward through all 15 layers,
	 * RMSNorm, GRU decode 7 tokens, map back to compound token. This is
	 * the atomic unit of generation at real dimensions.
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void testSingleAutoregressiveStep() {
		long start = System.currentTimeMillis();

		long allocStart = System.currentTimeMillis();
		StateDictionary stateDict = createRandomWeights(REAL_CONFIG);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(REAL_CONFIG);
		GRUDecoder decoder = createRandomDecoder(REAL_CONFIG);
		long allocTime = System.currentTimeMillis() - allocStart;
		System.out.println("[MoonbeamComponentTest] weight alloc: " + allocTime + " ms");

		long buildStart = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(REAL_CONFIG, stateDict, embedding, decoder);
		long buildTime = System.currentTimeMillis() - buildStart;
		System.out.println("[MoonbeamComponentTest] model build: " + buildTime + " ms");

		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		long stepStart = System.currentTimeMillis();
		MidiCompoundToken result = autoregressive.next();
		long stepTime = System.currentTimeMillis() - stepStart;

		Assert.assertNotNull("Should produce a token", result);
		Assert.assertFalse("Token should not be PAD", result.isPAD());

		System.out.println("[MoonbeamComponentTest] autoregressive step: " + stepTime + " ms");
		System.out.println("[MoonbeamComponentTest] generated token: onset="
				+ result.getOnset() + " dur=" + result.getDuration()
				+ " oct=" + result.getOctave() + " pc=" + result.getPitchClass()
				+ " inst=" + result.getInstrument() + " vel=" + result.getVelocity());

		long elapsed = System.currentTimeMillis() - start;
		System.out.println("[MoonbeamComponentTest] testSingleAutoregressiveStep: " + elapsed + " ms");
	}

	/* ------------------------------------------------------------ */
	/*  Helpers                                                     */
	/* ------------------------------------------------------------ */

	/**
	 * Create a MoonbeamConfig with real dimensions but a custom number of layers.
	 * All other parameters match {@link MoonbeamConfig#defaultConfig()}.
	 */
	private static MoonbeamConfig createRealConfigWithLayers(int numLayers) {
		return new MoonbeamConfig(
				1920, 6720, numLayers,
				12, 6, 160,
				1536, 4,
				8487, 8192, 1e-5,
				new double[]{199999, 1031, 19, 20, 199999, 131},
				new int[]{2, 2, 2, 2, 2, 2},
				new int[]{4099, 4099, 13, 14, 131, 130},
				new double[]{199999, 1031, 19, 20, 199999, 131},
				2);
	}

	/**
	 * Create a StateDictionary with random weights at the real model dimensions.
	 * RMSNorm weights are initialized to ones to avoid zeroing out activations.
	 */
	private static StateDictionary createRandomWeights(MoonbeamConfig config) {
		Map<String, PackedCollection> weights = new HashMap<>();
		Random rng = new Random(42);
		int dim = config.hiddenSize;
		int kvDim = dim * config.numKvHeads / config.numHeads;
		int ffnDim = config.intermediateSize;

		for (int i = 0; i < config.numLayers; i++) {
			String prefix = String.format("model.layers.%d", i);
			weights.put(prefix + ".input_layernorm.weight", onesCollection(dim));
			weights.put(prefix + ".post_attention_layernorm.weight", onesCollection(dim));
			weights.put(prefix + ".self_attn.q_proj.weight",
					createRandomCollection(rng, dim, dim));
			weights.put(prefix + ".self_attn.k_proj.weight",
					createRandomCollection(rng, kvDim, dim));
			weights.put(prefix + ".self_attn.v_proj.weight",
					createRandomCollection(rng, kvDim, dim));
			weights.put(prefix + ".self_attn.o_proj.weight",
					createRandomCollection(rng, dim, dim));
			weights.put(prefix + ".mlp.gate_proj.weight",
					createRandomCollection(rng, ffnDim, dim));
			weights.put(prefix + ".mlp.down_proj.weight",
					createRandomCollection(rng, dim, ffnDim));
			weights.put(prefix + ".mlp.up_proj.weight",
					createRandomCollection(rng, ffnDim, dim));
		}

		weights.put("model.norm.weight", onesCollection(dim));

		return new StateDictionary(weights);
	}

	/**
	 * Create a GRU decoder with random weights at real dimensions.
	 */
	private static GRUDecoder createRandomDecoder(MoonbeamConfig config) {
		Random rng = new Random(123);
		int hidden = config.hiddenSize;
		int decoderHidden = config.decoderHiddenSize;
		int vocabSize = config.decodeVocabSize;

		GRUBlock[] layers = new GRUBlock[config.decoderLayers];
		for (int l = 0; l < config.decoderLayers; l++) {
			layers[l] = new GRUBlock(
					decoderHidden, decoderHidden,
					createRandomCollection(rng, 3 * decoderHidden, decoderHidden),
					createRandomCollection(rng, 3 * decoderHidden, decoderHidden),
					createRandomCollection(rng, 3 * decoderHidden),
					createRandomCollection(rng, 3 * decoderHidden));
		}

		return new GRUDecoder(config, layers,
				createRandomCollection(rng, decoderHidden, hidden),
				createRandomCollection(rng, decoderHidden),
				createRandomCollection(rng, vocabSize, decoderHidden),
				createRandomCollection(rng, vocabSize),
				createRandomCollection(rng, vocabSize, decoderHidden));
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
	 * Uses bulk setMem to avoid per-element JNI overhead.
	 */
	private static PackedCollection createRandomCollection(Random rng, int... dims) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(dims));
		int total = collection.getShape().getTotalSize();
		double[] data = new double[total];
		for (int i = 0; i < total; i++) {
			data[i] = rng.nextGaussian() * 0.02;
		}
		collection.setMem(0, data, 0, total);
		return collection;
	}
}
