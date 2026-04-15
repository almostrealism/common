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
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link HeadGroupConfig} and the Multidimensional Relative Attention
 * (MRA) infrastructure, verifying freqCis computation and head group configuration.
 */
public class MoonbeamAttentionTest extends TestSuiteBase {

	/**
	 * Verify that computeFreqCis produces the correct output shape.
	 */
	@Test(timeout = 60000)
	public void testFreqCisShape() {
		int headDim = 160;
		int maxSeqLen = 128;
		double theta = 199999.0;

		PackedCollection freqCis = RotationFeatures.computeRopeFreqs(theta, headDim, maxSeqLen);

		Assert.assertEquals(3, freqCis.getShape().getDimensions());
		Assert.assertEquals(maxSeqLen, freqCis.getShape().length(0));
		Assert.assertEquals(headDim / 2, freqCis.getShape().length(1));
		Assert.assertEquals(2, freqCis.getShape().length(2));
	}

	/**
	 * Verify that freqCis values are valid cos/sin (in [-1, 1] range).
	 */
	@Test(timeout = 60000)
	public void testFreqCisValues() {
		int headDim = 8;
		int maxSeqLen = 16;
		double theta = 19.0;

		PackedCollection freqCis = RotationFeatures.computeRopeFreqs(theta, headDim, maxSeqLen);

		int totalSize = freqCis.getShape().getTotalSize();
		for (int i = 0; i < totalSize; i++) {
			double val = freqCis.toDouble(i);
			Assert.assertTrue("freqCis value out of range: " + val,
					val >= -1.0 && val <= 1.0);
		}
	}

	/**
	 * Verify that position 0 produces cos=1, sin=0 for all frequencies.
	 */
	@Test(timeout = 60000)
	public void testFreqCisPositionZero() {
		int headDim = 8;
		int maxSeqLen = 4;
		double theta = 100.0;
		int freqDim = headDim / 2;

		PackedCollection freqCis = RotationFeatures.computeRopeFreqs(theta, headDim, maxSeqLen);

		for (int f = 0; f < freqDim; f++) {
			int idx = f * 2;
			double cosVal = freqCis.toDouble(idx);
			double sinVal = freqCis.toDouble(idx + 1);
			Assert.assertEquals("cos at position 0 should be 1.0", 1.0, cosVal, 1e-10);
			Assert.assertEquals("sin at position 0 should be 0.0", 0.0, sinVal, 1e-10);
		}
	}

	/**
	 * Verify that different theta values produce different frequencies.
	 */
	@Test(timeout = 60000)
	public void testDifferentThetasProduceDifferentFreqs() {
		int headDim = 8;
		int maxSeqLen = 16;
		int pos = 5;
		int freqDim = headDim / 2;

		PackedCollection freqCis1 = RotationFeatures.computeRopeFreqs(19.0, headDim, maxSeqLen);
		PackedCollection freqCis2 = RotationFeatures.computeRopeFreqs(199999.0, headDim, maxSeqLen);

		boolean anyDifferent = false;
		for (int f = 0; f < freqDim; f++) {
			int idx = (pos * freqDim + f) * 2;
			if (Math.abs(freqCis1.toDouble(idx) - freqCis2.toDouble(idx)) > 1e-10) {
				anyDifferent = true;
				break;
			}
		}
		Assert.assertTrue("Different theta values should produce different frequencies", anyDifferent);
	}

	/**
	 * Verify that fromConfig creates the correct number of head groups
	 * with the correct head counts.
	 */
	@Test(timeout = 60000)
	public void testFromConfigHeadGroups() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();

		Producer<PackedCollection>[] positions =
				createPositionProducers(MoonbeamConfig.NUM_ATTRIBUTES);
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			PackedCollection posVal = new PackedCollection(1);
			posVal.setMem(0, i);
			positions[i] = () -> args -> posVal;
		}

		HeadGroupConfig[] groups = HeadGroupConfig.fromParams(
				config.ropeThetas, config.headDim, config.maxSeqLen, config.headsPerGroup, positions);

		Assert.assertEquals(MoonbeamConfig.NUM_ATTRIBUTES, groups.length);

		int totalHeads = 0;
		for (int g = 0; g < groups.length; g++) {
			Assert.assertEquals(config.headsPerGroup[g], groups[g].headCount);
			Assert.assertNotNull(groups[g].freqCis);
			Assert.assertNotNull(groups[g].position);
			totalHeads += groups[g].headCount;
		}
		Assert.assertEquals(config.numHeads, totalHeads);
	}

	/**
	 * Verify that each head group's freqCis has the correct shape.
	 */
	@Test(timeout = 60000)
	public void testFromConfigFreqCisShapes() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		int freqDim = config.headDim / 2;

		PackedCollection posVal = new PackedCollection(1);
		Producer<PackedCollection>[] positions =
				createPositionProducers(MoonbeamConfig.NUM_ATTRIBUTES);
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			positions[i] = () -> args -> posVal;
		}

		HeadGroupConfig[] groups = HeadGroupConfig.fromParams(
				config.ropeThetas, config.headDim, config.maxSeqLen, config.headsPerGroup, positions);

		for (int g = 0; g < groups.length; g++) {
			PackedCollection fc = groups[g].freqCis;
			Assert.assertEquals(3, fc.getShape().getDimensions());
			Assert.assertEquals(config.maxSeqLen, fc.getShape().length(0));
			Assert.assertEquals(freqDim, fc.getShape().length(1));
			Assert.assertEquals(2, fc.getShape().length(2));
		}
	}

	/**
	 * Verify vocabulary offset computation for the GRU decode vocabulary.
	 * Total should equal decodeVocabSize (8487).
	 */
	@Test(timeout = 60000)
	public void testDecodeVocabOffsets() {
		MoonbeamConfig config = MoonbeamConfig.defaultConfig();
		int[] offsets = GRUDecoder.computeVocabOffsets(config);

		Assert.assertEquals(7, offsets.length);
		Assert.assertEquals(0, offsets[0]); // SOS

		// Verify offsets are monotonically increasing
		for (int i = 1; i < offsets.length; i++) {
			Assert.assertTrue("Offset " + i + " should be > offset " + (i - 1),
					offsets[i] > offsets[i - 1]);
		}

		// Verify total coverage equals decodeVocabSize
		int totalVocab = 1; // SOS slot
		for (int v : config.vocabSizes) {
			totalVocab += v;
		}
		Assert.assertEquals(config.decodeVocabSize, totalVocab);
	}

	/**
	 * Helper to create a Producer array, isolating the unavoidable unchecked
	 * cast required by Java's lack of generic array creation.
	 */
	private static Producer<PackedCollection>[] createPositionProducers(int size) {
		return new Producer[size];
	}
}
