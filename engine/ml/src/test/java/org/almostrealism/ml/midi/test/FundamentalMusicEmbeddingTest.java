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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.midi.CompoundMidiEmbedding;
import org.almostrealism.ml.midi.FundamentalMusicEmbedding;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tests for {@link FundamentalMusicEmbedding} and {@link CompoundMidiEmbedding},
 * verifying output shapes and basic mathematical properties.
 */
public class FundamentalMusicEmbeddingTest extends TestSuiteBase {

	/**
	 * Verify that a single FME produces an output vector of the correct dimension.
	 */
	@Test(timeout = 60000)
	public void testFmeOutputShape() {
		int dim = 320;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(199999.0, dim);
		PackedCollection output = fme.embed(42).evaluate();

		assertEquals("FME output should have dim elements",
				dim, output.getShape().getTotalSize());
	}

	/**
	 * Verify that the sinusoidal encoding produces values in [-1, 1].
	 */
	@Test(timeout = 60000)
	public void testSinusoidalEncodingRange() {
		int dim = 64;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(1031.0, dim);
		PackedCollection encoding = fme.encodeSinusoidal(100).evaluate();

		for (int i = 0; i < dim; i++) {
			double value = encoding.toDouble(i);
			assertTrue("Sinusoidal values should be in [-1,1], got " + value,
					value >= -1.0 && value <= 1.0);
		}
	}

	/**
	 * Verify that different input values produce different sinusoidal encodings.
	 */
	@Test(timeout = 60000)
	public void testDifferentValuesProduceDifferentEncodings() {
		int dim = 64;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(19.0, dim);

		PackedCollection enc1 = fme.encodeSinusoidal(0).evaluate();
		PackedCollection enc2 = fme.encodeSinusoidal(5).evaluate();

		boolean anyDifferent = false;
		for (int i = 0; i < dim; i++) {
			if (Math.abs(enc1.toDouble(i) - enc2.toDouble(i)) > 1e-10) {
				anyDifferent = true;
				break;
			}
		}
		assertTrue("Different values should produce different encodings", anyDifferent);
	}

	/**
	 * Verify that inverse frequencies follow the expected formula.
	 */
	@Test(timeout = 60000)
	public void testInvFreqComputation() {
		double base = 10000.0;
		int dim = 8;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(base, dim);
		PackedCollection invFreqs = fme.computeInvFreqs(base, dim).get().evaluate();

		assertEquals("Should have dim/2 frequencies", dim / 2, invFreqs.getShape().getTotalSize());

		double expected0 = 1.0 / Math.pow(base, 0.0 / dim);
		assertEquals("invFreq[0] = 1.0", expected0, invFreqs.toDouble(0), 1e-5);

		double expected1 = 1.0 / Math.pow(base, 2.0 / dim);
		assertEquals("invFreq[1]", expected1, invFreqs.toDouble(1), 1e-5);
	}

	/**
	 * Verify that CompoundMidiEmbedding produces a vector of hiddenSize.
	 */
	@Test(timeout = 60000)
	public void testCompoundEmbeddingOutputShape() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		MidiCompoundToken token = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		PackedCollection output = embedding.embed(token).evaluate();

		assertEquals("Compound embedding output should be hiddenSize",
				config.hiddenSize, output.getShape().getTotalSize());
	}

	/**
	 * Verify that SOS and EOS special tokens produce valid embeddings
	 * with the correct shape and finite values.
	 *
	 * <p>With zero-initialized weights (test constructor), SOS and EOS
	 * produce identical zero vectors. Difference testing requires
	 * pretrained weights via StateDictionary.</p>
	 */
	@Test(timeout = 60000)
	public void testSpecialTokenEmbedding() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		PackedCollection sosEmb = embedding.embed(MidiCompoundToken.sos()).evaluate();
		assertEquals("SOS embedding should be hiddenSize",
				config.hiddenSize, sosEmb.getShape().getTotalSize());
		for (int i = 0; i < config.hiddenSize; i++) {
			assertFalse("SOS embedding should be finite at " + i,
					Double.isNaN(sosEmb.toDouble(i)));
		}

		PackedCollection eosEmb = embedding.embed(MidiCompoundToken.eos()).evaluate();
		assertEquals("EOS embedding should be hiddenSize",
				config.hiddenSize, eosEmb.getShape().getTotalSize());
		for (int i = 0; i < config.hiddenSize; i++) {
			assertFalse("EOS embedding should be finite at " + i,
					Double.isNaN(eosEmb.toDouble(i)));
		}
	}

	/**
	 * Verify that default config FME bases match the ropeThetas array.
	 */
	@Test(timeout = 60000)
	public void testDefaultConfigFmeBases() {
		MoonbeamConfig config = MoonbeamConfig.defaultConfig();
		double[] expectedBases = {199999, 1031, 19, 20, 199999, 131};
		for (int i = 0; i < expectedBases.length; i++) {
			assertEquals("FME base for attribute " + i,
					expectedBases[i], config.fmeBases[i], 1e-10);
		}
	}

	/**
	 * Verify that the compound embedding dimensions align:
	 * 6 * embeddingDim == hiddenSize.
	 */
	@Test(timeout = 60000)
	public void testEmbeddingDimensionAlignment() {
		MoonbeamConfig config = MoonbeamConfig.defaultConfig();
		assertEquals("6 * embeddingDim should equal hiddenSize",
				config.hiddenSize,
				MoonbeamConfig.NUM_ATTRIBUTES * config.embeddingDim);
	}

	/**
	 * Verify that PAD tokens produce an all-zero embedding vector.
	 */
	@Test(timeout = 60000)
	public void testPadTokenEmbedding() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		PackedCollection padEmb = embedding.embed(MidiCompoundToken.pad()).evaluate();
		assertEquals("PAD embedding should be hiddenSize",
				config.hiddenSize, padEmb.getShape().getTotalSize());

		for (int i = 0; i < config.hiddenSize; i++) {
			assertEquals("PAD embedding should be zero at index " + i,
					0.0, padEmb.toDouble(i), 1e-15);
		}
	}

	/**
	 * Verify that embedSequence produces the correct output shape.
	 */
	@Test(timeout = 60000)
	public void testEmbedSequenceShape() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		List<MidiCompoundToken> tokens = Arrays.asList(
				MidiCompoundToken.sos(),
				new MidiCompoundToken(100, 50, 5, 0, 0, 80),
				MidiCompoundToken.eos()
		);

		PackedCollection result = embedding.embedSequence(tokens).evaluate();
		assertEquals("Sequence embedding rows",
				tokens.size() * config.hiddenSize,
				result.getShape().getTotalSize());
	}

	/**
	 * Verify that embedSequence content matches sequential embed() calls.
	 * This ensures embedSequence correctly concatenates individual token embeddings.
	 */
	@Test(timeout = 60000)
	public void testEmbedSequenceContentMatchesIndividualEmbeds() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		MidiCompoundToken token0 = MidiCompoundToken.sos();
		MidiCompoundToken token1 = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		MidiCompoundToken token2 = MidiCompoundToken.pad();

		List<MidiCompoundToken> tokens = Arrays.asList(token0, token1, token2);
		PackedCollection sequenceResult = embedding.embedSequence(tokens).evaluate();

		PackedCollection emb0 = embedding.embed(token0).evaluate();
		PackedCollection emb1 = embedding.embed(token1).evaluate();
		PackedCollection emb2 = embedding.embed(token2).evaluate();

		int hidden = config.hiddenSize;
		for (int i = 0; i < hidden; i++) {
			assertEquals("Token 0 embedding mismatch at index " + i,
					emb0.toDouble(i), sequenceResult.toDouble(i), 1e-15);
			assertEquals("Token 1 embedding mismatch at index " + i,
					emb1.toDouble(i), sequenceResult.toDouble(hidden + i), 1e-15);
			assertEquals("Token 2 embedding mismatch at index " + i,
					emb2.toDouble(i), sequenceResult.toDouble(2 * hidden + i), 1e-15);
		}
	}

	/**
	 * Verify that the build-time {@link CompoundMidiEmbedding#embed(MidiCompoundToken)} path,
	 * which includes only the branch the already-known token takes, produces the same
	 * embedding as the evaluation-time {@link CompoundMidiEmbedding#embedValues(Producer)}
	 * path, which carries every branch and selects among them arithmetically. This pins the
	 * equivalence the path-selecting {@code embed} relies on across every token class: an
	 * ordinary token, both special-token rows (start and fill-start take supplementary row 0,
	 * end and fill-end take row 1), and the pad token, so a regression in either path's branch
	 * selection is caught.
	 *
	 * <p>The embedding is built from a non-zero weight fixture rather than the zero-valued test
	 * constructor: with zero weights every branch collapses to the zero vector, so the
	 * equivalence would hold trivially and a wrong supplementary row or mis-routed special token
	 * would go undetected. The closing assertions confirm the fixture actually discriminates the
	 * branches (ordinary and both supplementary rows are non-trivial and the two rows differ) and
	 * that the fill delimiters resolve to the same row as their SOS/EOS counterparts, which is the
	 * {@code isEOS() || isFillEnd()} routing the change introduced.</p>
	 */
	@Test(timeout = 60000)
	public void testEmbedMatchesEmbedValuesForEachTokenClass() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = nonZeroEmbedding(config);

		List<MidiCompoundToken> tokens = Arrays.asList(
				new MidiCompoundToken(100, 50, 5, 0, 0, 80),
				MidiCompoundToken.sos(),
				MidiCompoundToken.eos(),
				MidiCompoundToken.fillStart(),
				MidiCompoundToken.fillEnd(),
				MidiCompoundToken.pad());

		int hidden = config.hiddenSize;
		for (MidiCompoundToken token : tokens) {
			PackedCollection pathSelected = embedding.embed(token).evaluate();
			PackedCollection allBranches = embedding.embedValues(cp(token.pack())).evaluate();

			assertEquals("embed and embedValues must agree on output size for " + token,
					hidden, pathSelected.getShape().getTotalSize());
			assertEquals("embed and embedValues must agree on output size for " + token,
					hidden, allBranches.getShape().getTotalSize());
			assertEquals("embed must match embedValues for " + token,
					0.0, largestDeviation(allBranches, pathSelected), 1e-6);
		}

		// The fixture must actually discriminate the branches, otherwise the equivalence above
		// holds for the wrong reason. These fail if embed collapses a branch to zeros or selects
		// the wrong supplementary row.
		PackedCollection ordinary = embedding.embed(new MidiCompoundToken(100, 50, 5, 0, 0, 80)).evaluate();
		PackedCollection sos = embedding.embed(MidiCompoundToken.sos()).evaluate();
		PackedCollection eos = embedding.embed(MidiCompoundToken.eos()).evaluate();
		PackedCollection fillStart = embedding.embed(MidiCompoundToken.fillStart()).evaluate();
		PackedCollection fillEnd = embedding.embed(MidiCompoundToken.fillEnd()).evaluate();
		PackedCollection pad = embedding.embed(MidiCompoundToken.pad()).evaluate();

		assertTrue("ordinary embedding must be non-trivial", largestDeviation(0.0, ordinary) > 1e-3);
		assertTrue("SOS embedding (supplementary row 0) must be non-trivial", largestDeviation(0.0, sos) > 1e-3);
		assertTrue("EOS embedding (supplementary row 1) must be non-trivial", largestDeviation(0.0, eos) > 1e-3);
		assertTrue("SOS and EOS must use different supplementary rows", largestDeviation(sos, eos) > 1e-3);
		assertEquals("FILL_START must match SOS (supplementary row 0)",
				0.0, largestDeviation(sos, fillStart), 1e-6);
		assertEquals("FILL_END must match EOS (supplementary row 1)",
				0.0, largestDeviation(eos, fillEnd), 1e-6);
		assertEquals("PAD embedding must be an all-zero vector",
				0.0, largestDeviation(0.0, pad), 1e-15);
	}

	/**
	 * Builds a {@link CompoundMidiEmbedding} whose weights are non-zero, so that each branch
	 * (the per-attribute embeddings, the two supplementary rows, and the pad zeros) produces a
	 * distinguishable output. The weight keys and shapes match those read by
	 * {@link CompoundMidiEmbedding#CompoundMidiEmbedding(StateDictionary, MoonbeamConfig)}. A
	 * fixed seed keeps the fixture reproducible across runs.
	 *
	 * @param config model configuration supplying the embedding dimensions
	 * @return a CompoundMidiEmbedding over randomly-initialized weights
	 */
	private CompoundMidiEmbedding nonZeroEmbedding(MoonbeamConfig config) {
		int dim = config.embeddingDim;
		int hidden = config.hiddenSize;
		int mlpIntermediate = hidden / 2;
		// Index 4 is the instrument attribute, which uses a lookup table rather than an FME.
		int instrumentIndex = 4;
		String[] fmePrefixes = {"onset_embedding", "duration_embedding", "octave_embedding",
				"pitch_embedding", null, "velocity_embedding"};
		Random source = new Random(1729);

		Map<String, PackedCollection> weights = new HashMap<>();
		for (String prefix : fmePrefixes) {
			if (prefix == null) continue;
			weights.put(prefix + ".linear.weight", new PackedCollection(shape(dim, dim)).randnFill(source));
			weights.put(prefix + ".linear.bias", new PackedCollection(shape(dim)).randnFill(source));
			weights.put(prefix + ".translation_bias", new PackedCollection(shape(1)).randnFill(source));
		}
		weights.put("instrument_embedding.weight",
				new PackedCollection(shape(config.vocabSizes[instrumentIndex], dim)).randnFill(source));
		weights.put("supplementary_embedding.weight",
				new PackedCollection(shape(config.supplementaryVocabSize, hidden)).randnFill(source));
		weights.put("supplementary_mlp.0.weight",
				new PackedCollection(shape(mlpIntermediate, hidden)).randnFill(source));
		weights.put("supplementary_mlp.0.bias",
				new PackedCollection(shape(mlpIntermediate)).randnFill(source));
		weights.put("supplementary_mlp.2.weight",
				new PackedCollection(shape(hidden, mlpIntermediate)).randnFill(source));
		weights.put("supplementary_mlp.2.bias",
				new PackedCollection(shape(hidden)).randnFill(source));

		return new CompoundMidiEmbedding(new StateDictionary(weights), config);
	}

	/**
	 * Verify that the same token produces identical embeddings on repeated calls.
	 */
	@Test(timeout = 60000)
	public void testEmbeddingDeterminism() {
		int dim = 64;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(1031.0, dim);

		PackedCollection first = fme.embed(42).evaluate();
		PackedCollection second = fme.embed(42).evaluate();

		for (int i = 0; i < dim; i++) {
			assertEquals("Embedding should be deterministic at index " + i,
					first.toDouble(i), second.toDouble(i), 1e-15);
		}
	}
}
