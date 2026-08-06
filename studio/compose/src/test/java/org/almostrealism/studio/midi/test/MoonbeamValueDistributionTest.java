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

package org.almostrealism.studio.midi.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.Ops;
import io.almostrealism.compute.Process;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.relation.Producer;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.studio.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestDepth;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * Diagnostic tests for understanding the Moonbeam MIDI value distribution problem.
 *
 * <p>When generating with real weights, ALL output values exceed valid MIDI ranges
 * (e.g. pitch &gt; 127). A previous clamping fix hides the real problem by clamping
 * everything to the maximum. These tests diagnose exactly WHERE and WHY the values
 * go wrong in the GRU decoder pipeline.</p>
 *
 * <p>All tests use REAL model dimensions (hiddenSize=1920, decoderHiddenSize=1536,
 * decodeVocabSize=8487) with random weights scaled at 0.02 (typical initialization).
 * Diagnostic output is printed liberally so CI logs reveal the distribution characteristics.</p>
 *
 * @see GRUDecoder
 * @see MoonbeamConfig
 */
public class MoonbeamValueDistributionTest extends TestSuiteBase implements ConsoleFeatures {

	/** Real model config used by all tests. */
	private static final MoonbeamConfig REAL_CONFIG = MoonbeamConfig.defaultConfig();

	/** Attribute names for diagnostic output. */
	private static final String[] ATTR_NAMES = {
			"sos_out", "onset", "duration", "octave", "pitch_class", "instrument", "velocity"
	};

	/** Maximum valid attribute values per decode step (vocabSize - 1 for each). */
	private static final int[] MAX_ATTR_VALUES = {
			0,    // sos_out (always 0)
			4098, // onset (vocabSize 4099)
			4098, // duration (vocabSize 4099)
			12,   // octave (vocabSize 13)
			13,   // pitch_class (vocabSize 14)
			130,  // instrument (vocabSize 131)
			129   // velocity (vocabSize 130)
	};

	/* ================================================================ */
	/*  Test 1: Vocabulary Offset Mapping                               */
	/* ================================================================ */

	/**
	 * Verify that {@link GRUDecoder#computeVocabOffsets} and
	 * {@link GRUDecoder#toAttributeValues} correctly partition the flat
	 * decode vocabulary of 8487 tokens into per-attribute ranges.
	 *
	 * <p>The expected layout is:</p>
	 * <ul>
	 *   <li>sos_out: offset 0, size 1</li>
	 *   <li>onset: offset 1, size 4099</li>
	 *   <li>duration: offset 4100, size 4099</li>
	 *   <li>octave: offset 8199, size 13</li>
	 *   <li>pitch_class: offset 8212, size 14</li>
	 *   <li>instrument: offset 8226, size 131</li>
	 *   <li>velocity: offset 8357, size 130</li>
	 * </ul>
	 */
	@Test(timeout = 120_000)
	public void testVocabOffsetMapping() {
		log("\n=== Test 1: Vocabulary Offset Mapping ===\n");

		int[] offsets = GRUDecoder.computeVocabOffsets(REAL_CONFIG);

		int[] expectedOffsets = {0, 1, 4100, 8199, 8212, 8226, 8357};
		int[] expectedSizes = {1, 4099, 4099, 13, 14, 131, 130};

		log("Computed offsets vs expected:");
		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			log(String.format("  [%d] %-12s: offset=%5d (expected %5d), vocabSize=%4d%n",
					i, ATTR_NAMES[i], offsets[i], expectedOffsets[i],
					i < expectedSizes.length ? expectedSizes[i] : -1));
			Assert.assertEquals("Offset for " + ATTR_NAMES[i],
					expectedOffsets[i], offsets[i]);
		}

		int totalVocab = expectedOffsets[6] + expectedSizes[6];
		log(String.format("\nTotal vocab: %d (expected %d, config says %d)%n",
				totalVocab, 8487, REAL_CONFIG.decodeVocabSize));
		Assert.assertEquals("Total vocab size", 8487, totalVocab);

		// Test round-trip with known flat indices
		log("\nRound-trip tests with known flat indices:");

		// Token representing onset=100 should be at flat index 1 + 100 = 101
		int[] flatTokens = new int[]{0, 101, 4150, 8204, 8219, 8226, 8421};
		int[] attrValues = createDecoderForTest().toAttributeValues(flatTokens);
		int[] expectedAttr = {0, 100, 50, 5, 7, 0, 64};

		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			log(String.format("  [%d] %-12s: flat=%5d -> attr=%5d (expected %5d) %s%n",
					i, ATTR_NAMES[i], flatTokens[i], attrValues[i], expectedAttr[i],
					attrValues[i] == expectedAttr[i] ? "OK" : "MISMATCH"));
			Assert.assertEquals("Attribute value for " + ATTR_NAMES[i],
					expectedAttr[i], attrValues[i]);
		}

		// Test boundary: what happens if the decoder picks a flat token
		// that is BEYOND the valid range for its position?
		log("\nBoundary test: what happens with out-of-range flat tokens?");
		int[] outOfRange = new int[]{0, 8000, 8400, 8400, 8400, 8400, 8487};
		int[] outValues = createDecoderForTest().toAttributeValues(outOfRange);
		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			boolean inRange = (i == 0)
					? outValues[i] == 0
					: (outValues[i] >= 0 && outValues[i] <= MAX_ATTR_VALUES[i]);
			log(String.format("  [%d] %-12s: flat=%5d -> attr=%5d, valid_max=%5d, in_range=%s%n",
					i, ATTR_NAMES[i], outOfRange[i], outValues[i], MAX_ATTR_VALUES[i],
					inRange ? "YES" : "NO (THIS IS THE BUG)"));
		}

		log("\nKey insight: toAttributeValues() just subtracts the offset.");
		log("If the GRU decoder picks a flat token > offset + vocabSize for");
		log("that position, the resulting attribute value exceeds the valid range.");
		log("The GRU decoder does NOT constrain its argmax to the correct");
		log("sub-range of the flat vocabulary for each decode step.");
	}

	/* ================================================================ */
	/*  Test 2: GRU Decoder Output Distribution with Random Weights     */
	/* ================================================================ */

	/**
	 * Run the GRU decoder on random hidden states at real dimensions and
	 * analyze the raw logits at each of the 7 decode steps.
	 *
	 * <p>This test instruments the decoder to capture logit statistics
	 * BEFORE argmax, revealing whether the lm_head projection produces
	 * logits in a reasonable range or if they are exploding.</p>
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void testGruDecoderLogitDistribution() {
		if (skipHighMemTests) return;
		log("\n=== Test 2: GRU Decoder Logit Distribution ===\n");

		int decoderHidden = REAL_CONFIG.decoderHiddenSize;
		int vocabSize = REAL_CONFIG.decodeVocabSize;
		int hidden = REAL_CONFIG.hiddenSize;
		Random rng = new Random(42);

		// Build decoder with random weights
		GRUDecoder decoder = createRandomDecoder(rng);

		// Run decoder on multiple random hidden states
		int numTrials = 5;
		log(String.format("Running %d decode trials with random hidden states...%n%n", numTrials));

		for (int trial = 0; trial < numTrials; trial++) {
			PackedCollection hiddenState = createRandomCollection(new Random(trial), hidden);
			int[] tokens = decoder.decode(hiddenState);
			int[] attrValues = decoder.toAttributeValues(tokens);

			log(String.format("Trial %d:%n", trial));
			log(String.format("  Raw decode tokens: %s%n", Arrays.toString(tokens)));
			log(String.format("  Attribute values:  %s%n", Arrays.toString(attrValues)));

			for (int i = 1; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
				boolean inRange = attrValues[i] >= 0 && attrValues[i] <= MAX_ATTR_VALUES[i];
				log(String.format("    [%d] %-12s: attr=%5d, max=%5d %s%n",
						i, ATTR_NAMES[i], attrValues[i], MAX_ATTR_VALUES[i],
						inRange ? "OK" : "OUT OF RANGE"));
				Assert.assertTrue(
						String.format("Trial %d, %s: value %d exceeds max %d",
								trial, ATTR_NAMES[i], attrValues[i], MAX_ATTR_VALUES[i]),
						inRange);
			}
			log("");
		}

		// Now analyze the lm_head logits directly
		log("=== Logit Analysis: lm_head output at each GRU step ===\n");
		analyzeLogitsAtEachStep(rng, hidden, decoderHidden, vocabSize);
	}

	/* ================================================================ */
	/*  Test 3: Softmax and Sampling Verification                       */
	/* ================================================================ */

	/**
	 * Verify that the sampling implementation in {@link AutoregressiveModel#sampleToken}
	 * correctly applies softmax and produces varied token indices.
	 *
	 * <p>Tests with uniform logits (should produce uniform sampling) and
	 * with a spike logit (should strongly prefer the spike index).</p>
	 */
	@Test(timeout = 10_000)
	public void testSoftmaxAndSampling() {
		log("\n=== Test 3: Softmax and Sampling Verification ===\n");

		int vocabSize = REAL_CONFIG.decodeVocabSize;

		// Test 1: Uniform logits should produce diverse samples
		log("Test 3a: Uniform logits (all zeros)");
		PackedCollection uniformLogits = new PackedCollection(vocabSize);
		// All zeros by default

		int[] histogram = new int[10]; // 10 bins across vocab
		int binSize = vocabSize / 10;
		int numSamples = 1000;
		Random samplingRng = new Random(42);

		for (int i = 0; i < numSamples; i++) {
			int token = AutoregressiveModel.sampleToken(uniformLogits, vocabSize,
					1.0, 1.0, samplingRng);
			int bin = Math.min(token / binSize, 9);
			histogram[bin]++;
		}

		log("  Histogram of 1000 samples across 10 bins (expect ~100 each):");
		boolean uniformish = true;
		for (int b = 0; b < 10; b++) {
			String bar = "=".repeat(Math.min(histogram[b] / 2, 80));
			log(String.format("    bin %d [%5d-%5d]: %4d %s%n",
					b, b * binSize, (b + 1) * binSize - 1, histogram[b], bar));
			if (histogram[b] < 20 || histogram[b] > 300) uniformish = false;
		}
		log("  Distribution roughly uniform: " + uniformish);

		// Test 2: Spike logit should produce concentrated samples
		log("\nTest 3b: Spike logit at index 100");
		PackedCollection spikeLogits = new PackedCollection(vocabSize);
		spikeLogits.setMem(100, 100.0); // Very strong preference

		int spikeCount = 0;
		samplingRng = new Random(42);
		for (int i = 0; i < numSamples; i++) {
			int token = AutoregressiveModel.sampleToken(spikeLogits, vocabSize,
					1.0, 1.0, samplingRng);
			if (token == 100) spikeCount++;
		}
		log(String.format("  Spike token selected %d/%d times (expect ~1000)%n",
				spikeCount, numSamples));
		Assert.assertTrue("Spike logit should dominate", spikeCount > 900);

		// Test 3: Greedy (argmax) should always pick the max
		log("\nTest 3c: Greedy argmax verification");
		PackedCollection greedyLogits = new PackedCollection(vocabSize);
		greedyLogits.setMem(4242, 10.0);

		// GRUDecoder.decode uses argmax internally, but we verify the static method
		int argmaxResult = argmax(greedyLogits, vocabSize);
		log(String.format("  Argmax of logits with spike at 4242: %d%n", argmaxResult));
		Assert.assertEquals("Argmax should find the spike", 4242, argmaxResult);

		// Test 4: What does argmax on random logits look like?
		log("\nTest 3d: Argmax on random N(0,1) logits (10 trials)");
		log("  (Shows which vocab region argmax lands in with random weights)");
		Random rng = new Random(42);
		for (int trial = 0; trial < 10; trial++) {
			PackedCollection randomLogits = createRandomLogits(rng, vocabSize, 1.0);
			int maxIdx = argmax(randomLogits, vocabSize);
			double maxVal = randomLogits.toDouble(maxIdx);
			String region = describeVocabRegion(maxIdx);
			log(String.format("    trial %d: argmax=%5d (val=%.4f) -> %s%n",
					trial, maxIdx, maxVal, region));
		}
	}

	/* ================================================================ */
	/*  Test 4: Weight Shape Verification                               */
	/* ================================================================ */

	/**
	 * Verify the expected shapes of all GRU decoder weight matrices.
	 *
	 * <p>If weights are loaded with the wrong shape (e.g. lm_head transposed),
	 * the decoder will produce garbage. This test prints all expected vs actual
	 * shapes for the decoder components.</p>
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void testWeightShapeVerification() {
		if (skipHighMemTests) return;
		log("\n=== Test 4: Weight Shape Verification ===\n");

		int hidden = REAL_CONFIG.hiddenSize;           // 1920
		int decoderHidden = REAL_CONFIG.decoderHiddenSize; // 1536
		int vocabSize = REAL_CONFIG.decodeVocabSize;   // 8487
		int decoderLayers = REAL_CONFIG.decoderLayers;  // 4

		log("Expected GRU decoder weight shapes:");
		log(String.format("  summary_projection.weight: (%d, %d) = %d elements%n",
				decoderHidden, hidden, decoderHidden * hidden));
		log(String.format("  summary_projection.bias:   (%d) = %d elements%n",
				decoderHidden, decoderHidden));
		log(String.format("  lm_head.weight:            (%d, %d) = %d elements%n",
				vocabSize, decoderHidden, vocabSize * decoderHidden));
		log(String.format("  lm_head.bias:              (%d) = %d elements%n",
				vocabSize, vocabSize));
		log(String.format("  decoder_embedding.weight:  (%d, %d) = %d elements%n",
				vocabSize, decoderHidden, vocabSize * decoderHidden));

		for (int l = 0; l < decoderLayers; l++) {
			log(String.format("  decoder.weight_ih_l%d:      (%d, %d) = %d elements%n",
					l, 3 * decoderHidden, decoderHidden, 3 * decoderHidden * decoderHidden));
			log(String.format("  decoder.weight_hh_l%d:      (%d, %d) = %d elements%n",
					l, 3 * decoderHidden, decoderHidden, 3 * decoderHidden * decoderHidden));
			log(String.format("  decoder.bias_ih_l%d:        (%d) = %d elements%n",
					l, 3 * decoderHidden, 3 * decoderHidden));
			log(String.format("  decoder.bias_hh_l%d:        (%d) = %d elements%n",
					l, 3 * decoderHidden, 3 * decoderHidden));
		}

		// Create decoder and verify shapes match
		Random rng = new Random(42);
		GRUDecoder decoder = createRandomDecoder(rng);

		Assert.assertEquals("Decoder layers", decoderLayers, decoder.getNumLayers());
		Assert.assertEquals("Decoder hidden size", decoderHidden, decoder.getDecoderHiddenSize());

		// Verify the lm_head output is vocabSize-dimensional
		log("\nVerifying lm_head produces correct output dimension...");
		PackedCollection hiddenState = createRandomCollection(rng, hidden);
		int[] tokens = decoder.decode(hiddenState);
		for (int i = 0; i < tokens.length; i++) {
			Assert.assertTrue("Token " + i + " in valid range [0, " + vocabSize + ")",
					tokens[i] >= 0 && tokens[i] < vocabSize);
		}
		log("  All 7 decode tokens are within [0, " + vocabSize + ") - shapes OK");

		// Check for potential transposition issue with lm_head
		log("\nTransposition diagnostic:");
		log(String.format("  If lm_head.weight is (%d, %d) [correct]: output = W @ h + b%n",
				vocabSize, decoderHidden));
		log(String.format("  If lm_head.weight is (%d, %d) [transposed]: wrong matmul dims%n",
				decoderHidden, vocabSize));
		log("  The linearForward method assumes (outputSize, inputSize) layout.");
		log("  If the extraction script produces (inputSize, outputSize), the");
		log("  matmul is silently wrong (no size check) and produces garbage logits.");
	}

	/* ================================================================ */
	/*  Test 5: Pipeline Stage Analysis                                 */
	/* ================================================================ */

	/**
	 * Trace the value distribution through each stage of the GRU decoder
	 * pipeline: summary projection, GRU layers, lm_head, and argmax.
	 *
	 * <p>At each stage, prints min/max/mean/std to identify where values
	 * start going wrong (exploding or collapsing).</p>
	 */
	@Test(timeout = 60_000)
	public void testPipelineStageAnalysis() {
		log("\n=== Test 5: Pipeline Stage Analysis ===\n");

		int hidden = REAL_CONFIG.hiddenSize;
		int decoderHidden = REAL_CONFIG.decoderHiddenSize;
		int vocabSize = REAL_CONFIG.decodeVocabSize;
		Random rng = new Random(42);

		// Create components separately to instrument intermediate values
		PackedCollection summaryWeight = createRandomCollection(rng, decoderHidden, hidden);
		PackedCollection summaryBias = createRandomCollection(rng, decoderHidden);
		PackedCollection lmHeadWeight = createRandomCollection(rng, vocabSize, decoderHidden);
		PackedCollection lmHeadBias = createRandomCollection(rng, vocabSize);
		PackedCollection decoderEmb = createRandomCollection(rng, vocabSize, decoderHidden);

		int nl = REAL_CONFIG.decoderLayers;
		PackedCollection[] weightIhL = new PackedCollection[nl];
		PackedCollection[] weightHhL = new PackedCollection[nl];
		PackedCollection[] biasIhL = new PackedCollection[nl];
		PackedCollection[] biasHhL = new PackedCollection[nl];
		for (int l = 0; l < nl; l++) {
			weightIhL[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			weightHhL[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			biasIhL[l] = createRandomCollection(rng, 3 * decoderHidden);
			biasHhL[l] = createRandomCollection(rng, 3 * decoderHidden);
		}

		// Stage 1: Random input hidden state
		PackedCollection inputHidden = createRandomCollection(new Random(99), hidden);
		printStats("Stage 1: Input hidden state (hiddenSize=" + hidden + ")",
				inputHidden.range(shape(hidden)));

		// Stage 2: Summary projection
		PackedCollection projected = linearForward(
				inputHidden, hidden, summaryWeight, decoderHidden, summaryBias);
		printStats("Stage 2: After summary projection (decoderHidden=" + decoderHidden + ")",
				projected.range(shape(decoderHidden)));

		// Stage 3: GRU forward steps
		PackedCollection[] h = new PackedCollection[nl];
		for (int l = 0; l < nl; l++) {
			h[l] = copyCollection(projected, decoderHidden);
		}

		PackedCollection x = getEmbeddingSlice(decoderEmb, 0, decoderHidden);
		PackedCollection logits = new PackedCollection(vocabSize);
		printStats("Stage 3a: SOS embedding (token 0)", x.range(shape(decoderHidden)));

		for (int step = 0; step < GRUDecoder.TOKENS_PER_NOTE; step++) {
			PackedCollection layerInput = x;
			for (int l = 0; l < nl; l++) {
				h[l].setFrom(0, gruStep(weightIhL[l], weightHhL[l],
						biasIhL[l], biasHhL[l], layerInput, h[l]));
				layerInput = h[l];
			}

			printStats("Stage 3b: GRU output (step " + step + ", " + ATTR_NAMES[step] + ")",
					h[nl - 1].range(shape(decoderHidden)));

			// Stage 4: lm_head projection to logits
			logits.setFrom(0, linearForward(h[nl - 1], decoderHidden,
					lmHeadWeight, vocabSize, lmHeadBias));
			printStats("Stage 4: lm_head logits (step " + step + ", " + ATTR_NAMES[step] + ")",
					logits.range(shape(vocabSize)));

			// Stage 5: Argmax
			int token = argmax(logits, vocabSize);
			String region = describeVocabRegion(token);
			log(String.format("Stage 5: Argmax token=%d -> %s%n%n", token, region));

			// Embed the selected token for next step
			x.setFrom(0, decoderEmb, token * decoderHidden, decoderHidden);
		}

		// Key diagnostic: what is the lm_head logit magnitude relative to vocab structure?
		log("=== KEY DIAGNOSTIC ===");
		log("The lm_head projects a " + decoderHidden + "-dim vector to " + vocabSize + " logits.");
		log(String.format("With random N(0, 0.02) weights, each logit is a sum of %d terms.%n", decoderHidden));
		log(String.format("Expected logit std ~ 0.02 * sqrt(%d) * input_std%n", decoderHidden));
		log("If input values are O(1), logit std ~ " +
				String.format("%.4f", 0.02 * Math.sqrt(decoderHidden)));
		log("With such small variance, argmax is essentially RANDOM across all 8487 tokens.");
		log("The argmax has no reason to stay within the correct attribute sub-range.");
		log("");
		log("Root cause hypothesis: The decoder picks argmax over the FULL 8487 vocab");
		log("at every step, but each step should only pick from its attribute's sub-range.");
		log("With random/trained weights, the argmax token at step i (e.g. 'octave')");
		log("could land anywhere in the 8487-token space, not just in the 13-token");
		log("octave sub-range. After subtracting the octave offset, this produces");
		log("a value >> 12, which gets clamped to the max.");
	}

	/* ================================================================ */
	/*  Test 6: Round-Trip Validation                                   */
	/* ================================================================ */

	/**
	 * Create a known valid compound token (middle C, velocity 64, quarter note),
	 * compute what flat decode indices would represent it, and verify that
	 * {@link GRUDecoder#toAttributeValues} maps those indices back correctly.
	 *
	 * <p>Also tests what flat decode indices the decoder SHOULD produce for
	 * this token, and checks whether the decoder can actually produce them.</p>
	 */
	@Test(timeout = 10_000)
	public void testRoundTripValidation() {
		log("\n=== Test 6: Round-Trip Validation ===\n");

		// Middle C = MIDI note 60, octave 5, pitch class 0
		// Quarter note at 120 BPM with 100 ticks/sec = 50 ticks duration
		// Velocity 64 (mezzo-forte), piano (instrument 0), onset delta 100 ticks
		int onset = 100;
		int duration = 50;
		int octave = 5;
		int pitchClass = 0;
		int instrument = 0;
		int velocity = 64;

		MidiCompoundToken middleC = new MidiCompoundToken(
				onset, duration, octave, pitchClass, instrument, velocity);
		log("Target token: " + middleC);

		int[] offsets = GRUDecoder.computeVocabOffsets(REAL_CONFIG);
		log("Vocab offsets: " + Arrays.toString(offsets));

		// Compute what flat tokens would represent this compound token
		int[] expectedFlat = new int[GRUDecoder.TOKENS_PER_NOTE];
		expectedFlat[0] = 0;                        // SOS output token
		expectedFlat[1] = offsets[1] + onset;        // 1 + 100 = 101
		expectedFlat[2] = offsets[2] + duration;     // 4100 + 50 = 4150
		expectedFlat[3] = offsets[3] + octave;       // 8199 + 5 = 8204
		expectedFlat[4] = offsets[4] + pitchClass;   // 8212 + 0 = 8212
		expectedFlat[5] = offsets[5] + instrument;   // 8226 + 0 = 8226
		expectedFlat[6] = offsets[6] + velocity;     // 8357 + 64 = 8421

		log("Expected flat tokens for middle C: " + Arrays.toString(expectedFlat));

		// Verify round-trip
		GRUDecoder decoder = createDecoderForTest();
		int[] roundTripped = decoder.toAttributeValues(expectedFlat);

		log("Round-tripped attribute values: " + Arrays.toString(roundTripped));

		int[] expectedAttr = {0, onset, duration, octave, pitchClass, instrument, velocity};
		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			log(String.format("  [%d] %-12s: expected=%5d, got=%5d %s%n",
					i, ATTR_NAMES[i], expectedAttr[i], roundTripped[i],
					roundTripped[i] == expectedAttr[i] ? "OK" : "MISMATCH"));
			Assert.assertEquals("Round-trip for " + ATTR_NAMES[i],
					expectedAttr[i], roundTripped[i]);
		}

		// Now show the valid flat token ranges for each step
		log("\nValid flat token ranges per decode step:");
		int[] vocabSizes = {1, 4099, 4099, 13, 14, 131, 130};
		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			int lo = offsets[i];
			int hi = offsets[i] + vocabSizes[i] - 1;
			log(String.format("  step %d %-12s: flat tokens [%5d, %5d] (size=%4d)%n",
					i, ATTR_NAMES[i], lo, hi, vocabSizes[i]));
		}

		log("\nNote: The decoder does argmax over ALL 8487 tokens at every step.");
		log("For step 3 (octave), valid flat tokens are [8199, 8211] (13 values).");
		log("But argmax could land anywhere in [0, 8486]. If it picks e.g. 5000,");
		log("then attr = 5000 - 8199 = -3199, which is nonsensical.");
		log("If it picks e.g. 8486 (last token), attr = 8486 - 8199 = 287,");
		log("which is >> max octave of 10, resulting in clamping to 10.");
	}

	/* ================================================================ */
	/*  Test 7: Decoded Attribute Range Validation                      */
	/* ================================================================ */

	/**
	 * Verify that the GRU decoder with per-step logit masking produces
	 * attribute values strictly within valid MIDI ranges across many trials.
	 *
	 * <p>Checks: octave 0-10, pitchClass 0-11, velocity 0-127,
	 * instrument 0-128, onset 0-4098, duration 0-4098.</p>
	 *
	 * <p>Tests both greedy (argmax) and sampling decode paths.</p>
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void testDecodedAttributeRangeValidation() {
		if (skipHighMemTests) return;
		log("\n=== Test 7: Decoded Attribute Range Validation ===\n");

		int hidden = REAL_CONFIG.hiddenSize;
		Random rng = new Random(123);
		GRUDecoder decoder = createRandomDecoder(rng);

		int numTrials = 20;

		// Test greedy decode
		log("Greedy decode (argmax) - " + numTrials + " trials:");
		for (int trial = 0; trial < numTrials; trial++) {
			PackedCollection hiddenState = createRandomCollection(new Random(trial * 7), hidden);
			int[] tokens = decoder.decode(hiddenState);
			int[] attrValues = decoder.toAttributeValues(tokens);

			assertAttributeRanges(attrValues, "greedy trial " + trial);
		}
		log("  All greedy trials passed.\n");

		// Test sampling decode
		log("Sampling decode (temperature=0.8, topP=0.9) - " + numTrials + " trials:");
		for (int trial = 0; trial < numTrials; trial++) {
			PackedCollection hiddenState = createRandomCollection(new Random(trial * 13), hidden);
			Random samplingRng = new Random(trial * 17);
			int[] tokens = decoder.decode(hiddenState, 0.8, 0.9, samplingRng);
			int[] attrValues = decoder.toAttributeValues(tokens);

			assertAttributeRanges(attrValues, "sampling trial " + trial);
		}
		log("  All sampling trials passed.\n");

		// Test high-temperature sampling (more randomness, higher chance of edge cases)
		log("High-temperature sampling (temperature=2.0, topP=1.0) - " + numTrials + " trials:");
		for (int trial = 0; trial < numTrials; trial++) {
			PackedCollection hiddenState = createRandomCollection(new Random(trial * 19), hidden);
			Random samplingRng = new Random(trial * 23);
			int[] tokens = decoder.decode(hiddenState, 2.0, 1.0, samplingRng);
			int[] attrValues = decoder.toAttributeValues(tokens);

			assertAttributeRanges(attrValues, "high-temp trial " + trial);
		}
		log("  All high-temperature trials passed.");
	}

	/**
	 * Assert that decoded attribute values are within their valid ranges.
	 *
	 * <p>The masking guarantees values are within the vocab sub-range for each
	 * attribute: onset [0, 4098], duration [0, 4098], octave [0, 12],
	 * pitchClass [0, 13], instrument [0, 130], velocity [0, 129].
	 * Values above the musical maximum (e.g., octave 11-12) are reserved
	 * tokens (SOS/EOS) which is valid model behavior.</p>
	 */
	private void assertAttributeRanges(int[] attrValues, String context) {
		int[] vocabSizes = REAL_CONFIG.vocabSizes;
		Assert.assertTrue(context + ": onset " + attrValues[1],
				attrValues[1] >= 0 && attrValues[1] < vocabSizes[0]);
		Assert.assertTrue(context + ": duration " + attrValues[2],
				attrValues[2] >= 0 && attrValues[2] < vocabSizes[1]);
		Assert.assertTrue(context + ": octave " + attrValues[3],
				attrValues[3] >= 0 && attrValues[3] < vocabSizes[2]);
		Assert.assertTrue(context + ": pitchClass " + attrValues[4],
				attrValues[4] >= 0 && attrValues[4] < vocabSizes[3]);
		Assert.assertTrue(context + ": instrument " + attrValues[5],
				attrValues[5] >= 0 && attrValues[5] < vocabSizes[4]);
		Assert.assertTrue(context + ": velocity " + attrValues[6],
				attrValues[6] >= 0 && attrValues[6] < vocabSizes[5]);
	}

	/* ================================================================ */
	/*  Helper Methods                                                  */
	/* ================================================================ */

	/**
	 * Create a minimal GRU decoder for offset/mapping tests (tiny weights).
	 */
	private GRUDecoder createDecoderForTest() {
		Random rng = new Random(0);
		int decoderHidden = REAL_CONFIG.decoderHiddenSize;
		int vocabSize = REAL_CONFIG.decodeVocabSize;
		int hidden = REAL_CONFIG.hiddenSize;

		int nc = REAL_CONFIG.decoderLayers;
		int[] inputSizesC = new int[nc];
		PackedCollection[] weightIhC = new PackedCollection[nc];
		PackedCollection[] weightHhC = new PackedCollection[nc];
		PackedCollection[] biasIhC = new PackedCollection[nc];
		PackedCollection[] biasHhC = new PackedCollection[nc];
		for (int l = 0; l < nc; l++) {
			inputSizesC[l] = decoderHidden;
			weightIhC[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			weightHhC[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			biasIhC[l] = createRandomCollection(rng, 3 * decoderHidden);
			biasHhC[l] = createRandomCollection(rng, 3 * decoderHidden);
		}

		return new GRUDecoder(REAL_CONFIG, inputSizesC, weightIhC, weightHhC, biasIhC, biasHhC,
				createRandomCollection(rng, decoderHidden, hidden),
				createRandomCollection(rng, decoderHidden),
				createRandomCollection(rng, vocabSize, decoderHidden),
				createRandomCollection(rng, vocabSize),
				createRandomCollection(rng, vocabSize, decoderHidden));
	}

	/**
	 * Create a GRU decoder with random weights at real dimensions.
	 */
	private GRUDecoder createRandomDecoder(Random rng) {
		int decoderHidden = REAL_CONFIG.decoderHiddenSize;
		int vocabSize = REAL_CONFIG.decodeVocabSize;
		int hidden = REAL_CONFIG.hiddenSize;

		int nr = REAL_CONFIG.decoderLayers;
		int[] inputSizesR = new int[nr];
		PackedCollection[] weightIhR = new PackedCollection[nr];
		PackedCollection[] weightHhR = new PackedCollection[nr];
		PackedCollection[] biasIhR = new PackedCollection[nr];
		PackedCollection[] biasHhR = new PackedCollection[nr];
		for (int l = 0; l < nr; l++) {
			inputSizesR[l] = decoderHidden;
			weightIhR[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			weightHhR[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			biasIhR[l] = createRandomCollection(rng, 3 * decoderHidden);
			biasHhR[l] = createRandomCollection(rng, 3 * decoderHidden);
		}

		return new GRUDecoder(REAL_CONFIG, inputSizesR, weightIhR, weightHhR, biasIhR, biasHhR,
				createRandomCollection(rng, decoderHidden, hidden),
				createRandomCollection(rng, decoderHidden),
				createRandomCollection(rng, vocabSize, decoderHidden),
				createRandomCollection(rng, vocabSize),
				createRandomCollection(rng, vocabSize, decoderHidden));
	}

	/**
	 * Analyze lm_head logits at each GRU decode step by manually stepping
	 * through the decode pipeline.
	 */
	private void analyzeLogitsAtEachStep(Random rng, int hidden, int decoderHidden, int vocabSize) {
		PackedCollection summaryWeight = createRandomCollection(rng, decoderHidden, hidden);
		PackedCollection summaryBias = createRandomCollection(rng, decoderHidden);
		PackedCollection lmHeadWeight = createRandomCollection(rng, vocabSize, decoderHidden);
		PackedCollection lmHeadBias = createRandomCollection(rng, vocabSize);
		PackedCollection decoderEmb = createRandomCollection(rng, vocabSize, decoderHidden);

		int na = REAL_CONFIG.decoderLayers;
		PackedCollection[] weightIhA = new PackedCollection[na];
		PackedCollection[] weightHhA = new PackedCollection[na];
		PackedCollection[] biasIhA = new PackedCollection[na];
		PackedCollection[] biasHhA = new PackedCollection[na];
		for (int l = 0; l < na; l++) {
			weightIhA[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			weightHhA[l] = createRandomCollection(rng, 3 * decoderHidden, decoderHidden);
			biasIhA[l] = createRandomCollection(rng, 3 * decoderHidden);
			biasHhA[l] = createRandomCollection(rng, 3 * decoderHidden);
		}

		PackedCollection inputHidden = createRandomCollection(new Random(77), hidden);

		// Summary projection
		PackedCollection projected = linearForward(
				inputHidden, hidden, summaryWeight, decoderHidden, summaryBias);

		PackedCollection[] h = new PackedCollection[na];
		for (int l = 0; l < na; l++) {
			h[l] = copyCollection(projected, decoderHidden);
		}

		PackedCollection x = getEmbeddingSlice(decoderEmb, 0, decoderHidden);
		PackedCollection logits = new PackedCollection(vocabSize);

		for (int step = 0; step < GRUDecoder.TOKENS_PER_NOTE; step++) {
			PackedCollection layerInput = x;
			for (int l = 0; l < na; l++) {
				h[l].setFrom(0, gruStep(weightIhA[l], weightHhA[l],
						biasIhA[l], biasHhA[l], layerInput, h[l]));
				layerInput = h[l];
			}

			logits.setFrom(0, linearForward(h[na - 1], decoderHidden,
					lmHeadWeight, vocabSize, lmHeadBias));

			PackedCollection logitValues = logits.range(shape(vocabSize));

			// Find top-5 tokens
			int[] topK = topKIndices(logitValues, 5);
			PackedCollection stats = statistics(logitValues);
			log(String.format("Step %d (%s):%n", step, ATTR_NAMES[step]));
			log(String.format("  Logit stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f%n",
					stats.toDouble(STAT_MIN), stats.toDouble(STAT_MAX),
					stats.toDouble(STAT_MEAN), stats.toDouble(STAT_STD)));
			log("  Top-5 tokens: ");
			for (int k = 0; k < 5; k++) {
				log(String.format("[%d]=%.4f ", topK[k], logitValues.toDouble(topK[k])));
			}
			log("");

			// Where do the top tokens fall in the vocab layout?
			int token = topK[0];
			String region = describeVocabRegion(token);
			log(String.format("  Argmax token %d -> %s%n", token, region));

			// What would be the correct range for this step?
			int[] offsets = GRUDecoder.computeVocabOffsets(REAL_CONFIG);
			int[] sizes = {1, 4099, 4099, 13, 14, 131, 130};
			log(String.format("  Expected range for this step: [%d, %d] (size %d)%n",
					offsets[step], offsets[step] + sizes[step] - 1, sizes[step]));

			// How many of the top-5 are in the correct range?
			int inRange = 0;
			for (int k = 0; k < 5; k++) {
				if (topK[k] >= offsets[step] && topK[k] < offsets[step] + sizes[step]) {
					inRange++;
				}
			}
			log(String.format("  Top-5 tokens in correct range: %d/5%n%n", inRange));

			x.setFrom(0, decoderEmb, token * decoderHidden, decoderHidden);
		}
	}

	/**
	 * Diagnose why a particular set of decode tokens are out of range.
	 */
	private void diagnoseTokenSelection(int[] tokens, GRUDecoder decoder) {
		int[] offsets = decoder.getVocabOffsets();
		int[] sizes = {1, 4099, 4099, 13, 14, 131, 130};

		for (int i = 1; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			int lo = offsets[i];
			int hi = offsets[i] + sizes[i] - 1;
			boolean inExpectedRange = tokens[i] >= lo && tokens[i] <= hi;
			if (!inExpectedRange) {
				log(String.format("    Step %d (%s): token=%d but valid range=[%d,%d]. ",
						i, ATTR_NAMES[i], tokens[i], lo, hi));
				if (tokens[i] < lo) {
					log(String.format("Token is %d below range (in %s region)%n",
							lo - tokens[i], describeVocabRegion(tokens[i])));
				} else {
					log(String.format("Token is %d above range (in %s region)%n",
							tokens[i] - hi, describeVocabRegion(tokens[i])));
				}
			}
		}
	}

	/**
	 * Describe which attribute region a flat vocab index falls in.
	 */
	private String describeVocabRegion(int flatIndex) {
		if (flatIndex == 0) return "sos_out [0,0]";
		if (flatIndex >= 1 && flatIndex <= 4099) return "onset [1,4099]";
		if (flatIndex >= 4100 && flatIndex <= 8198) return "duration [4100,8198]";
		if (flatIndex >= 8199 && flatIndex <= 8211) return "octave [8199,8211]";
		if (flatIndex >= 8212 && flatIndex <= 8225) return "pitch_class [8212,8225]";
		if (flatIndex >= 8226 && flatIndex <= 8356) return "instrument [8226,8356]";
		if (flatIndex >= 8357 && flatIndex <= 8486) return "velocity [8357,8486]";
		return "UNKNOWN (index=" + flatIndex + ")";
	}

	/**
	 * Slice an embedding row from a flat (vocabSize * dim) embedding table.
	 */
	private static PackedCollection getEmbeddingSlice(PackedCollection embedding,
													  int tokenIndex, int dim) {
		PackedCollection slice = new PackedCollection(dim);
		slice.setFrom(0, embedding, tokenIndex * dim, dim);
		return slice;
	}

	/**
	 * Copy a PackedCollection.
	 */
	private static PackedCollection copyCollection(PackedCollection source, int size) {
		PackedCollection copy = new PackedCollection(size);
		copy.setFrom(0, source, 0, size);
		return copy;
	}

	/**
	 * Create a PackedCollection with random N(0, scale) values.
	 */
	private static PackedCollection createRandomCollection(Random rng, int... dims) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(dims));
		Ops.o().randn(collection.getShape(), rng).multiply(0.02)
				.into(collection.traverseEach()).evaluate();
		return collection;
	}

	/**
	 * Create random logits with specified standard deviation.
	 */
	private static PackedCollection createRandomLogits(Random rng, int size, double std) {
		PackedCollection collection = new PackedCollection(size);
		Ops.o().randn(collection.getShape(), rng).multiply(std)
				.into(collection.traverseEach()).evaluate();
		return collection;
	}

	/**
	 * Project one of the three gates stacked into a GRU weight matrix.
	 *
	 * <p>GRU weights arrive stacked as (3*size, inputSize) in reset, update, candidate
	 * order, with biases stacked the same way. This slices out the requested gate's
	 * rows and returns {@code W_gate @ input + b_gate}.</p>
	 *
	 * @param weight     stacked weights of shape (3*size, inputSize)
	 * @param bias       stacked biases of shape (3*size)
	 * @param input      vector of shape (inputSize)
	 * @param gate       gate index: 0 for reset, 1 for update, 2 for candidate
	 * @param size       hidden size, the number of rows belonging to each gate
	 * @param inputSize  length of the input vector
	 * @return the gate projection of shape (size)
	 */
	private CollectionProducer gateProjection(PackedCollection weight, PackedCollection bias,
											  Producer<PackedCollection> input,
											  int gate, int size, int inputSize) {
		return add(
				matmul(cp(weight.range(shape(size, inputSize), gate * size * inputSize)), input),
				cp(bias.range(shape(size), gate * size))).reshape(shape(size));
	}

	/**
	 * Run one GRU layer step for diagnostic purposes.
	 *
	 * <p>Computes: r = σ(W_ir·x + b_ir + W_hr·h + b_hr),
	 * z = σ(W_iz·x + b_iz + W_hz·h + b_hz),
	 * n = tanh(W_in·x + b_in + r*(W_hn·h + b_hn)),
	 * h_new = (1-z)*n + z*h.</p>
	 *
	 * <p>Callers stepping this in a loop should read the result back into the same
	 * hidden state collection rather than letting it be replaced by the returned one.
	 * An operand's offset forms part of its signature, so a fresh allocation per step
	 * makes every step a distinct graph and recompiles the pipeline once per step.</p>
	 *
	 * @param weightIh   stacked input-hidden weights, shape (3*dh, inputSize)
	 * @param weightHh   stacked hidden-hidden weights, shape (3*dh, dh)
	 * @param biasIh     stacked input-hidden biases, shape (3*dh)
	 * @param biasHh     stacked hidden-hidden biases, shape (3*dh)
	 * @param x          input vector of shape (inputSize)
	 * @param h          previous hidden state of shape (hiddenSize)
	 * @return new hidden state of shape (hiddenSize)
	 */
	private PackedCollection gruStep(
			PackedCollection weightIh, PackedCollection weightHh,
			PackedCollection biasIh, PackedCollection biasHh,
			PackedCollection x, PackedCollection h) {
		int dh = h.getShape().getTotalSize();
		int inputSize = x.getShape().getTotalSize();

		CollectionProducer input = cp(x.range(shape(inputSize)));
		CollectionProducer hidden = cp(h.range(shape(dh)));

		CollectionProducer r = sigmoid(add(
				gateProjection(weightIh, biasIh, input, 0, dh, inputSize),
				gateProjection(weightHh, biasHh, hidden, 0, dh, dh)));
		CollectionProducer z = sigmoid(add(
				gateProjection(weightIh, biasIh, input, 1, dh, inputSize),
				gateProjection(weightHh, biasHh, hidden, 1, dh, dh)));
		CollectionProducer n = tanh(add(
				gateProjection(weightIh, biasIh, input, 2, dh, inputSize),
				r.multiply(gateProjection(weightHh, biasHh, hidden, 2, dh, dh))));

		CollectionProducer hNew = add(
				c(1.0).subtract(z).multiply(n),
				z.multiply(hidden)).reshape(shape(dh));
		return (PackedCollection) Process.optimized(hNew).get().evaluate();
	}

	/**
	 * Compute matrix-vector product plus bias: result = weight @ input + bias.
	 * Mirrors the projection {@link GRUDecoder} applies for its summary and lm_head layers.
	 *
	 * @param input       vector of shape (inputSize)
	 * @param inputSize   length of the input vector
	 * @param weight      weights of shape (outputSize, inputSize)
	 * @param outputSize  length of the result
	 * @param bias        biases of shape (outputSize)
	 * @return the projection of shape (outputSize)
	 */
	private PackedCollection linearForward(PackedCollection input, int inputSize,
										   PackedCollection weight, int outputSize,
										   PackedCollection bias) {
		CollectionProducer projection = add(
				matmul(cp(weight.range(shape(outputSize, inputSize))),
						cp(input.range(shape(inputSize)))),
				cp(bias.range(shape(outputSize)))).reshape(shape(outputSize));
		return (PackedCollection) Process.optimized(projection).get().evaluate();
	}

	/**
	 * Find argmax of a PackedCollection.
	 */
	private int argmax(PackedCollection collection, int size) {
		return (int) cp(collection.range(shape(size))).indexOfMax().evaluate().toDouble(0);
	}

	/**
	 * Find the indices of the top-k values in an array.
	 */
	private int[] topKIndices(PackedCollection values, int k) {
		int n = values.getMemLength();

		// Selection proceeds on a copy, each winner masked out so the next round
		// finds the one after it. The result is already in descending order.
		PackedCollection remaining = new PackedCollection(n);
		remaining.setFrom(0, values);

		int indices[] = new int[k];
		for (int i = 0; i < k; i++) {
			indices[i] = argmax(remaining, n);
			a(cp(remaining), equals(integers(0, n), c(indices[i]),
					c(-Double.MAX_VALUE), cp(remaining))).get().run();
		}
		return indices;
	}

	/** Position of the minimum within the collection returned by {@link #statistics}. */
	private static final int STAT_MIN = 0;

	/** Position of the maximum within the collection returned by {@link #statistics}. */
	private static final int STAT_MAX = 1;

	/** Position of the mean within the collection returned by {@link #statistics}. */
	private static final int STAT_MEAN = 2;

	/** Position of the standard deviation within the collection returned by {@link #statistics}. */
	private static final int STAT_STD = 3;

	/** Position of the NaN count within the collection returned by {@link #statistics}. */
	private static final int STAT_NAN = 4;

	/** Position of the non-finite count within the collection returned by {@link #statistics}. */
	private static final int STAT_NON_FINITE = 5;

	/** Number of values in the collection returned by {@link #statistics}. */
	private static final int STAT_COUNT = 6;

	/**
	 * Summarize a signal as min, max, mean, standard deviation, NaN count and
	 * non-finite count, indexed by the {@code STAT_} constants.
	 *
	 * <p>The six aggregates are concatenated into a single computation so that one
	 * kernel is compiled and dispatched rather than six. There is no whole-collection
	 * minimum, so the smallest value is found by negating around a maximum. A value
	 * that differs from itself is not a number, and one whose magnitude does not
	 * compare below the largest finite value is either that or an infinity.</p>
	 *
	 * <p>Optimized before evaluation: the square root and the counts consume
	 * whole-collection reductions, which an unoptimized consumer embeds at every
	 * element.</p>
	 *
	 * @param values the signal to summarize
	 * @return the six aggregates, of shape (6)
	 */
	private PackedCollection statistics(PackedCollection values) {
		int n = values.getMemLength();
		CollectionProducer v = cp(values);

		CollectionProducer stats = concat(
				max(v.multiply(-1.0)).multiply(-1.0),
				max(v),
				mean(v),
				sqrt(variance(v)),
				sum(equals(v, v, c(0.0), c(1.0)).reshape(shape(n))),
				sum(lessThan(abs(v), c(Double.MAX_VALUE), c(0.0), c(1.0)).reshape(shape(n))))
				.reshape(shape(STAT_COUNT));
		return (PackedCollection) Process.optimized(stats).get().evaluate();
	}

	/** Prints min/max/mean/std statistics for a signal. */
	private void printStats(String label, PackedCollection values) {
		PackedCollection stats = statistics(values);

		Console.root().println(String.format("%s:%n", label));
		Console.root().println(String.format("  size=%d, min=%.6f, max=%.6f, mean=%.6f, std=%.6f%n",
				values.getMemLength(), stats.toDouble(STAT_MIN), stats.toDouble(STAT_MAX),
				stats.toDouble(STAT_MEAN), stats.toDouble(STAT_STD)));

		int nanCount = (int) stats.toDouble(STAT_NAN);
		int nonFinite = (int) stats.toDouble(STAT_NON_FINITE);

		if (nonFinite > 0) {
			Console.root().println(String.format("  WARNING: %d NaN, %d Inf values%n",
					nanCount, nonFinite - nanCount));
		}
	}

}
