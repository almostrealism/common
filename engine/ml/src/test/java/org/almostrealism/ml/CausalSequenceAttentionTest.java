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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Forward tests of the causal option of full-sequence attention: no output position may be
 * influenced by a later position, the last position (where the mask removes nothing) matches
 * bidirectional attention, the option reaches {@link TransformerBlockFeatures#transformerBlock},
 * and the differential variant rejects it explicitly.
 */
public class CausalSequenceAttentionTest extends TestSuiteBase implements TransformerBlockFeatures {
	/** Sequence length. */
	private static final int SEQ_LEN = 5;
	/** Model dimension. */
	private static final int DIM = 8;
	/** Number of heads. */
	private static final int HEADS = 2;
	/** Position whose value vector is replaced by a sentinel. */
	private static final int FUTURE = 3;

	/**
	 * With causal masking, a huge sentinel value vector at a later key position cannot change
	 * the attention output at any earlier query position, while it does change the output at its
	 * own position.
	 */
	@Test(timeout = 120000)
	public void futureValueSentinelDoesNotLeak() {
		int dimHead = DIM / HEADS;
		TraversalPolicy headShape = shape(1, HEADS, SEQ_LEN, dimHead);
		Random random = new Random(11);

		PackedCollection q = randn(headShape, 0.0, 1.0, random).evaluate();
		PackedCollection k = randn(headShape, 0.0, 1.0, random).evaluate();
		PackedCollection v = randn(headShape, 0.0, 1.0, random).evaluate();
		PackedCollection sentinel = new PackedCollection(v);
		for (int h = 0; h < HEADS; h++) {
			for (int d = 0; d < dimHead; d++) {
				sentinel.setValueAt(1e6, 0, h, FUTURE, d);
			}
		}

		PackedCollection clean = forward(scaledDotProductAttention(1, SEQ_LEN, SEQ_LEN, HEADS, dimHead,
				k, v, null, 0.0, null, true), q);
		PackedCollection poisoned = forward(scaledDotProductAttention(1, SEQ_LEN, SEQ_LEN, HEADS, dimHead,
				k, sentinel, null, 0.0, null, true), q);

		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < SEQ_LEN; i++) {
				for (int d = 0; d < dimHead; d++) {
					double a = clean.valueAt(0, h, i, d);
					double b = poisoned.valueAt(0, h, i, d);
					if (i < FUTURE) {
						Assert.assertEquals("head " + h + " position " + i, a, b, 1e-5);
					} else if (i == FUTURE) {
						Assert.assertTrue("sentinel must reach its own position", Math.abs(b) > 1e3);
					}
				}
			}
		}
	}

	/**
	 * Causal and bidirectional sequence attention agree at the last position, where the causal
	 * mask removes nothing, and disagree at the first position.
	 */
	@Test(timeout = 120000)
	public void lastPositionMatchesBidirectional() {
		Random random = new Random(3);
		PackedCollection qkv = randn(shape(3 * DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection out = randn(shape(DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection invFreq = new PackedCollection(shape(DIM / HEADS / 2)).fill(0.5);
		PackedCollection input = randn(shape(1, SEQ_LEN, DIM), 0.0, 1.0, random).evaluate();

		PackedCollection causal = forward(sequenceAttention(1, SEQ_LEN, DIM, HEADS, qkv, out,
				null, null, null, null, invFreq, ProjectionFactory.dense(),
				NormalizationType.LAYER, null, null, 0.0, true), input);
		PackedCollection bidirectional = forward(sequenceAttention(1, SEQ_LEN, DIM, HEADS, qkv, out,
				null, null, null, null, invFreq, ProjectionFactory.dense(),
				NormalizationType.LAYER, null, null, 0.0, false), input);

		double firstDifference = 0.0;
		for (int d = 0; d < DIM; d++) {
			Assert.assertEquals("dimension " + d, bidirectional.valueAt(0, SEQ_LEN - 1, d),
					causal.valueAt(0, SEQ_LEN - 1, d), 1e-5);
			firstDifference = Math.max(firstDifference,
					Math.abs(bidirectional.valueAt(0, 0, d) - causal.valueAt(0, 0, d)));
		}

		Assert.assertTrue("causal masking must change the first position", firstDifference > 1e-4);
	}

	/**
	 * The causal option reaches self-attention through {@code transformerBlock}: perturbing the
	 * input at a later position leaves every earlier output of a causal block unchanged.
	 */
	@Test(timeout = 120000)
	public void transformerBlockIsCausal() {
		Random random = new Random(5);
		PackedCollection ones = new PackedCollection(shape(DIM)).fill(1.0);
		PackedCollection qkv = randn(shape(3 * DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection wo = randn(shape(DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection w1 = randn(shape(4 * DIM, DIM), 0.0, 0.5, random).evaluate();
		PackedCollection w2 = randn(shape(DIM, 2 * DIM), 0.0, 0.5, random).evaluate();
		PackedCollection invFreq = new PackedCollection(shape(DIM / HEADS / 2)).fill(0.5);
		PackedCollection input = randn(shape(1, SEQ_LEN, DIM), 0.0, 1.0, random).evaluate();

		PackedCollection perturbed = new PackedCollection(input);
		for (int d = 0; d < DIM; d++) {
			perturbed.setValueAt(perturbed.valueAt(0, FUTURE, d) + 3.0, 0, FUTURE, d);
		}

		Block block = transformerBlock(1, DIM, SEQ_LEN, HEADS, false, 0, null,
				ones, null, qkv, wo, null, null, null, null, invFreq,
				null, null, null, null, null, null, null, null, null,
				ones, null, w1, w2, null, null,
				null, ProjectionFactory.dense(), AttentionVariant.STANDARD, null, null, null,
				NormalizationType.RMS, null, true);
		Model model = new Model(shape(1, SEQ_LEN, DIM));
		model.add(block);
		CompiledModel compiled = model.compile(false);
		PackedCollection a = new PackedCollection(compiled.forward(input));
		PackedCollection b = new PackedCollection(compiled.forward(perturbed));

		for (int i = 0; i < SEQ_LEN; i++) {
			double diff = 0.0;
			for (int d = 0; d < DIM; d++) {
				diff = Math.max(diff, Math.abs(a.valueAt(0, i, d) - b.valueAt(0, i, d)));
			}

			if (i < FUTURE) {
				Assert.assertEquals("position " + i + " must not see the later perturbation", 0.0, diff, 1e-5);
			} else {
				Assert.assertTrue("position " + i + " must see the perturbation", diff > 1e-4);
			}
		}
	}

	/** The differential attention variant rejects causal masking rather than ignoring it. */
	@Test(timeout = 60000)
	public void differentialRejectsCausal() {
		DifferentialAttentionFeatures features = new DifferentialAttentionFeatures() { };
		PackedCollection qkv = new PackedCollection(shape(5 * DIM, DIM));
		PackedCollection out = new PackedCollection(shape(DIM, DIM));
		PackedCollection invFreq = new PackedCollection(shape(DIM / HEADS / 2));

		try {
			features.selfAttention(1, SEQ_LEN, DIM, HEADS, AttentionVariant.DIFFERENTIAL,
					qkv, out, null, null, null, null, invFreq, null,
					ProjectionFactory.dense(), NormalizationType.RMS, null, true);
			Assert.fail("Differential attention must reject causal masking");
		} catch (UnsupportedOperationException expected) {
			log("rejected: " + expected.getMessage());
		}
	}

	/**
	 * Runs a block forward on the given input at the test boundary.
	 *
	 * @param block the block
	 * @param input the input, of the block's input shape
	 * @return a copy of the output
	 */
	private PackedCollection forward(Block block, PackedCollection input) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		return new PackedCollection(model.compile(false).forward(input));
	}
}
