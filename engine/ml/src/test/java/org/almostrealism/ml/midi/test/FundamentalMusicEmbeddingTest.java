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
import org.almostrealism.ml.midi.CompoundMidiEmbedding;
import org.almostrealism.ml.midi.FundamentalMusicEmbedding;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link FundamentalMusicEmbedding} and {@link CompoundMidiEmbedding},
 * verifying output shapes and basic mathematical properties.
 */
public class FundamentalMusicEmbeddingTest extends TestSuiteBase {

	/**
	 * Verify that a single FME produces an output vector of the correct dimension.
	 */
	@Test
	public void testFmeOutputShape() {
		int dim = 320;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(199999.0, dim);
		PackedCollection output = fme.embed(42);

		assertEquals("FME output should have dim elements",
				dim, output.getShape().getTotalSize());
	}

	/**
	 * Verify that the sinusoidal encoding produces values in [-1, 1].
	 */
	@Test
	public void testSinusoidalEncodingRange() {
		int dim = 64;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(1031.0, dim);
		PackedCollection encoding = fme.encodeSinusoidal(100);

		for (int i = 0; i < dim; i++) {
			double value = encoding.toDouble(i);
			assertTrue("Sinusoidal values should be in [-1,1], got " + value,
					value >= -1.0 && value <= 1.0);
		}
	}

	/**
	 * Verify that different input values produce different sinusoidal encodings.
	 */
	@Test
	public void testDifferentValuesProduceDifferentEncodings() {
		int dim = 64;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(19.0, dim);

		PackedCollection enc1 = fme.encodeSinusoidal(0);
		PackedCollection enc2 = fme.encodeSinusoidal(5);

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
	@Test
	public void testInvFreqComputation() {
		double base = 10000.0;
		int dim = 8;
		double[] invFreqs = FundamentalMusicEmbedding.computeInvFreqs(base, dim);

		assertEquals("Should have dim/2 frequencies", dim / 2, invFreqs.length);

		double expected0 = 1.0 / Math.pow(base, 0.0 / dim);
		assertEquals("invFreq[0] = 1.0", expected0, invFreqs[0], 1e-10);

		double expected1 = 1.0 / Math.pow(base, 2.0 / dim);
		assertEquals("invFreq[1]", expected1, invFreqs[1], 1e-10);
	}

	/**
	 * Verify that CompoundMidiEmbedding produces a vector of hiddenSize.
	 */
	@Test
	public void testCompoundEmbeddingOutputShape() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		MidiCompoundToken token = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		PackedCollection output = embedding.embed(token);

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
	@Test
	public void testSpecialTokenEmbedding() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		PackedCollection sosEmb = embedding.embed(MidiCompoundToken.sos());
		assertEquals("SOS embedding should be hiddenSize",
				config.hiddenSize, sosEmb.getShape().getTotalSize());
		for (int i = 0; i < config.hiddenSize; i++) {
			assertFalse("SOS embedding should be finite at " + i,
					Double.isNaN(sosEmb.toDouble(i)));
		}

		PackedCollection eosEmb = embedding.embed(MidiCompoundToken.eos());
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
	@Test
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
	@Test
	public void testEmbeddingDimensionAlignment() {
		MoonbeamConfig config = MoonbeamConfig.defaultConfig();
		assertEquals("6 * embeddingDim should equal hiddenSize",
				config.hiddenSize,
				MoonbeamConfig.NUM_ATTRIBUTES * config.embeddingDim);
	}

	/**
	 * Verify that PAD tokens produce an all-zero embedding vector.
	 */
	@Test
	public void testPadTokenEmbedding() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		PackedCollection padEmb = embedding.embed(MidiCompoundToken.pad());
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
	@Test
	public void testEmbedSequenceShape() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		List<MidiCompoundToken> tokens = Arrays.asList(
				MidiCompoundToken.sos(),
				new MidiCompoundToken(100, 50, 5, 0, 0, 80),
				MidiCompoundToken.eos()
		);

		PackedCollection result = embedding.embedSequence(tokens);
		assertEquals("Sequence embedding rows",
				tokens.size() * config.hiddenSize,
				result.getShape().getTotalSize());
	}

	/**
	 * Verify that embedSequence content matches sequential embed() calls.
	 * This ensures embedSequence correctly concatenates individual token embeddings.
	 */
	@Test
	public void testEmbedSequenceContentMatchesIndividualEmbeds() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);

		MidiCompoundToken token0 = MidiCompoundToken.sos();
		MidiCompoundToken token1 = new MidiCompoundToken(100, 50, 5, 0, 0, 80);
		MidiCompoundToken token2 = MidiCompoundToken.pad();

		List<MidiCompoundToken> tokens = Arrays.asList(token0, token1, token2);
		PackedCollection sequenceResult = embedding.embedSequence(tokens);

		PackedCollection emb0 = embedding.embed(token0);
		PackedCollection emb1 = embedding.embed(token1);
		PackedCollection emb2 = embedding.embed(token2);

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
	 * Verify that the same token produces identical embeddings on repeated calls.
	 */
	@Test
	public void testEmbeddingDeterminism() {
		int dim = 64;
		FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(1031.0, dim);

		PackedCollection first = fme.embed(42);
		PackedCollection second = fme.embed(42);

		for (int i = 0; i < dim; i++) {
			assertEquals("Embedding should be deterministic at index " + i,
					first.toDouble(i), second.toDouble(i), 1e-15);
		}
	}
}
