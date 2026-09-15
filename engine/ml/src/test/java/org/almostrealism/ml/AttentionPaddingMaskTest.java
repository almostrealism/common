/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.audio.ConditioningMode;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.ml.audio.DiffusionTransformerConfig;
import org.almostrealism.ml.audio.DiffusionTransformerFeatures;
import org.almostrealism.ml.audio.TimestepEncoding;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies value-masked attention padding: the per-axis scaling layer that implements it, its
 * effect inside {@link AttentionFeatures#sequenceAttention}, and the padding mask input of a
 * {@link DiffusionTransformer}.
 */
public class AttentionPaddingMaskTest extends TestSuiteBase implements DiffusionTransformerFeatures {

	/** Batch size of every case (the transformer stack compiles for a single example). */
	private static final int BATCH = 1;

	/** Model dimension of the attention cases. */
	private static final int DIM = 16;

	/** Attention heads of the attention cases. */
	private static final int HEADS = 2;

	/** Sequence length of the attention cases. */
	private static final int SEQ_LEN = 6;

	/**
	 * The per-axis scaling layer multiplies every element by the factor of its position along
	 * the chosen axis, so a factor of zero clears that position across every other axis.
	 */
	@Test(timeout = 120000)
	public void scaleAlongAxisAppliesOneFactorPerPosition() {
		int heads = 2;
		int positions = 4;
		int width = 3;
		TraversalPolicy shape = shape(BATCH, heads, positions, width);
		PackedCollection factors = PackedCollection.of(1.0, 0.0, 0.5, 0.0).reshape(shape(BATCH, positions));
		PackedCollection input = new PackedCollection(shape).randnFill();

		Model model = new Model(shape);
		model.sequential().add(scale(shape, 2, cp(factors)));
		PackedCollection output = model.compile(false).forward(input).reshape(shape);

		for (int h = 0; h < heads; h++) {
			for (int n = 0; n < positions; n++) {
				for (int d = 0; d < width; d++) {
					double expected = input.valueAt(0, h, n, d) * factors.valueAt(0, n);
					assertEquals(expected, output.valueAt(0, h, n, d), 1e-6);
				}
			}
		}
	}

	/** A mask that marks every position valid must reproduce unmasked attention exactly. */
	@Test(timeout = 240000)
	public void allValidMaskLeavesAttentionUnchanged() {
		AttentionWeights weights = new AttentionWeights();
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		PackedCollection allValid = new PackedCollection(shape(BATCH, SEQ_LEN)).fill(1.0);
		PackedCollection unmasked = run(weights.attention(null), input);
		PackedCollection masked = run(weights.attention(cp(allValid)), input);

		assertClose(unmasked, masked, 1e-6);
	}

	/**
	 * A masked position contributes nothing: masking one position changes the output, and
	 * masking every position leaves attention with only zero values to mix, so the block's output
	 * (a linear projection of those values) is zero everywhere.
	 */
	@Test(timeout = 240000)
	public void maskedPositionsContributeNothing() {
		AttentionWeights weights = new AttentionWeights();
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		PackedCollection twoPadded = PackedCollection.of(1.0, 1.0, 1.0, 1.0, 0.0, 0.0).reshape(shape(BATCH, SEQ_LEN));
		PackedCollection allPadded = new PackedCollection(shape(BATCH, SEQ_LEN));
		allPadded.clear();
		PackedCollection unmasked = run(weights.attention(null), input);
		PackedCollection partial = run(weights.attention(cp(twoPadded)), input);
		PackedCollection none = run(weights.attention(cp(allPadded)), input);

		assertTrue("masking two positions must change the output",
				maxDifference(unmasked, partial) > 1e-4);
		for (int i = 0; i < none.getShape().getTotalSize(); i++) {
			assertEquals(0.0, none.toDouble(i), 1e-6);
		}
	}

	/**
	 * A {@link DiffusionTransformer} configured with a padding mask starts with every position
	 * valid and then matches the same model built without a mask; zeroing the tail of the mask
	 * changes the prediction while keeping it finite.
	 */
	@Test(timeout = 240000)
	public void diffusionTransformerHonoursPaddingMask() {
		int ioChannels = 2;
		int audioSeqLen = 8;
		int globalCondDim = 8;
		DiffusionTransformerConfig masked = new DiffusionTransformerConfig(
				ioChannels, DIM, 1, HEADS, 1, 0, globalCondDim, "rf_denoiser", audioSeqLen, 0)
				.withConditioningMode(ConditioningMode.ADALN)
				.withMemoryTokens(2)
				.withTimestepEncoding(TimestepEncoding.EXPO)
				.withPaddingMask(true);
		DiffusionTransformerConfig plain = masked.withPaddingMask(false);

		DiffusionTransformerWeightFixture fixture = new DiffusionTransformerWeightFixture();
		DiffusionTransformer withMask = new DiffusionTransformer(masked, new StateDictionary(fixture.weights(masked)));
		DiffusionTransformer without = new DiffusionTransformer(plain, new StateDictionary(fixture.weights(plain)));
		assertTrue("a model built without a mask exposes none", without.getPaddingMask() == null);

		PackedCollection input = new PackedCollection(shape(BATCH, ioChannels, audioSeqLen)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(BATCH, 1)).fill(0.5);
		PackedCollection globalCond = new PackedCollection(shape(BATCH, globalCondDim)).randnFill();

		PackedCollection maskedOutput = withMask.forward(input, timestep, null, globalCond).clone();
		PackedCollection plainOutput = without.forward(input, timestep, null, globalCond);
		assertTrue("the two models hold different random weights, so they must disagree",
				maxDifference(maskedOutput, plainOutput) > 1e-4);

		PackedCollection paddingMask = withMask.getPaddingMask();
		assertEquals(BATCH * audioSeqLen, paddingMask.getShape().getTotalSize());
		PackedCollection halfPadded = PackedCollection.of(1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0);
		cp(halfPadded).into(paddingMask.traverseEach()).evaluate();
		PackedCollection padded = withMask.forward(input, timestep, null, globalCond);

		assertTrue("padding half of the sequence must change the prediction",
				maxDifference(maskedOutput, padded) > 1e-6);
		for (int i = 0; i < padded.getShape().getTotalSize(); i++) {
			assertTrue(Double.isFinite(padded.toDouble(i)));
		}

		withMask.destroy();
		without.destroy();
	}

	/**
	 * Compiles an attention block and runs one forward pass.
	 *
	 * @param block the block under test
	 * @param input the model input
	 * @return the forward-pass output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		Model model = new Model(shape(BATCH, SEQ_LEN, DIM));
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input).clone();
	}

	/**
	 * The largest element-wise difference between two collections of the same size.
	 *
	 * @param a the first collection
	 * @param b the second collection
	 * @return the largest absolute difference
	 */
	private double maxDifference(PackedCollection a, PackedCollection b) {
		double diff = 0.0;
		for (int i = 0; i < a.getShape().getTotalSize(); i++) {
			diff = Math.max(diff, Math.abs(a.toDouble(i) - b.toDouble(i)));
		}
		return diff;
	}

	/**
	 * Asserts element-wise closeness of two collections of the same size.
	 *
	 * @param expected  the reference values
	 * @param actual    the values under test
	 * @param tolerance the permitted absolute difference
	 */
	private void assertClose(PackedCollection expected, PackedCollection actual, double tolerance) {
		assertEquals(expected.getShape().getTotalSize(), actual.getShape().getTotalSize());
		assertTrue("difference " + maxDifference(expected, actual), maxDifference(expected, actual) <= tolerance);
	}

	/** One set of random self-attention weights, shared between masked and unmasked blocks. */
	private class AttentionWeights {
		/** Fused query/key/value projection. */
		private final PackedCollection qkv = random(3 * DIM, DIM);

		/** Output projection. */
		private final PackedCollection out = random(DIM, DIM);

		/** Query normalization scale. */
		private final PackedCollection qNorm = new PackedCollection(shape(DIM / HEADS)).fill(1.0);

		/** Key normalization scale. */
		private final PackedCollection kNorm = new PackedCollection(shape(DIM / HEADS)).fill(1.0);

		/** Rotary inverse frequencies. */
		private final PackedCollection invFreq = random(DIM / HEADS / 4);

		/**
		 * Builds RMS-normalized self-attention over these weights with the given mask.
		 *
		 * @param paddingMask the padding mask producer, or {@code null}
		 * @return the attention block
		 */
		private Block attention(Producer<PackedCollection> paddingMask) {
			return sequenceAttention(BATCH, SEQ_LEN, DIM, HEADS, qkv, out,
					qNorm, null, kNorm, null, invFreq, ProjectionFactory.dense(),
					NormalizationType.RMS, paddingMask, null, 0.0);
		}

		/**
		 * A small random weight.
		 *
		 * @param dims the weight dimensions
		 * @return the weight
		 */
		private PackedCollection random(int... dims) {
			PackedCollection value = new PackedCollection(shape(dims)).randnFill();
			return cp(value).multiply(0.2).into(value.traverseEach()).evaluate();
		}
	}
}
