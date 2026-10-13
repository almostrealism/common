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

package org.almostrealism.layers.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.model.AccumulationBlock;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for {@link AccumulationBlock}, the block behind
 * {@link LayerRoutingFeatures#accumBlocks}: the forward pass is the element-wise sum of the
 * children applied to one input, and the backward pass delivers the full output gradient to every
 * child (so the weights inside each summed path receive their own gradient) and returns the sum of
 * the children's input-gradients upstream.
 *
 * <p>The children are bias-free dense layers {@code y_k = W_k·x}, so the expected values are exact:
 * the output is {@code Σ_k W_k·x}, the gradient of {@code W_k} is the outer product
 * {@code g·xᵀ} for every child, and the input gradient is {@code Σ_k W_kᵀ·g}.</p>
 */
public class AccumulationBlockTests extends TestSuiteBase implements LayerRoutingFeatures, ModelTestFeatures {
	/** Input size of every child. */
	private static final int SIZE = 4;
	/** Output size of every child. */
	private static final int NODES = 3;
	/** Number of summed children. */
	private static final int CHILDREN = 3;

	/** The forward pass is the sum of every child's output for the shared input. */
	@Test(timeout = 120000)
	public void forwardSumsChildren() {
		Random random = new Random(11);
		List<PackedCollection> weights = weights(random);
		PackedCollection input = randn(shape(SIZE), 0.0, 1.0, random).evaluate();

		Model model = new Model(shape(SIZE));
		model.add(accumBlocks(shape(SIZE), children(weights)));
		PackedCollection out = model.compile(false).forward(input);

		Assert.assertEquals(NODES, out.getShape().getTotalSize());
		for (int i = 0; i < NODES; i++) {
			double expected = 0.0;
			for (PackedCollection w : weights) {
				for (int j = 0; j < SIZE; j++) {
					expected += w.toDouble(i * SIZE + j) * input.toDouble(j);
				}
			}

			Assert.assertEquals("output " + i, expected, out.toDouble(i), 1e-5);
		}
	}

	/**
	 * Every child's weights receive the full output gradient, and the input gradient is the sum of
	 * every child's input-gradient. A second backward pass with a different output gradient must
	 * report only its own gradients, which proves the shared accumulation buffer is cleared between
	 * passes rather than carrying the previous step's sum forward.
	 */
	@Test(timeout = 120000)
	public void backwardFeedsEveryChildAndSumsInputGradients() {
		Random random = new Random(12);
		List<PackedCollection> weights = weights(random);
		PackedCollection input = randn(shape(SIZE), 0.0, 1.0, random).evaluate();

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape(SIZE), gradientRecorder(recorded));
		model.add(accumBlocks(shape(SIZE), children(weights)));
		CompiledModel compiled = model.compile(true, true);
		Assert.assertEquals(CHILDREN, recorded.size());

		for (int pass = 0; pass < 2; pass++) {
			PackedCollection outputGradient = randn(shape(NODES), 0.0, 1.0, random).evaluate();
			compiled.forward(input);
			PackedCollection inputGradient = compiled.backward(outputGradient);

			for (int k = 0; k < CHILDREN; k++) {
				for (int i = 0; i < NODES; i++) {
					for (int j = 0; j < SIZE; j++) {
						Assert.assertEquals("pass " + pass + " child " + k + " weight [" + i + ", " + j + "]",
								outputGradient.toDouble(i) * input.toDouble(j),
								recorded.get(k).toDouble(i * SIZE + j), 1e-5);
					}
				}
			}

			Assert.assertEquals(SIZE, inputGradient.getShape().getTotalSize());
			for (int j = 0; j < SIZE; j++) {
				double expected = 0.0;
				for (PackedCollection w : weights) {
					for (int i = 0; i < NODES; i++) {
						expected += w.toDouble(i * SIZE + j) * outputGradient.toDouble(i);
					}
				}

				Assert.assertEquals("pass " + pass + " input " + j, expected, inputGradient.toDouble(j), 1e-5);
			}
		}
	}

	/** An accumulation of nothing has no output shape and is rejected. */
	@Test(timeout = 60000)
	public void rejectsNoChildren() {
		try {
			new AccumulationBlock(shape(SIZE), new ArrayList<>());
			Assert.fail("an AccumulationBlock with no children must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("at least one block"));
		}
	}

	/** Children whose outputs differ in size cannot be summed and are rejected. */
	@Test(timeout = 60000)
	public void rejectsMismatchedOutputShapes() {
		Random random = new Random(13);
		List<Block> children = new ArrayList<>();
		children.add(dense(shape(SIZE), randn(shape(NODES, SIZE), 0.0, 1.0, random).evaluate(), false, false));
		children.add(dense(shape(SIZE), randn(shape(NODES + 1, SIZE), 0.0, 1.0, random).evaluate(), false, false));
		try {
			new AccumulationBlock(shape(SIZE), children);
			Assert.fail("children with different output sizes must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("share an output shape"));
		}
	}

	/**
	 * The children are returned as an unmodifiable view, so the summed paths cannot be changed
	 * after construction.
	 */
	@Test(timeout = 60000)
	public void childrenAreUnmodifiable() {
		AccumulationBlock block = new AccumulationBlock(shape(SIZE), children(weights(new Random(14))));
		Assert.assertEquals(CHILDREN, block.getChildren().size());
		Assert.assertEquals(shape(SIZE), block.getInputShape());
		Assert.assertEquals(NODES, block.getOutputShape().getTotalSize());

		try {
			block.getChildren().clear();
			Assert.fail("getChildren() must be unmodifiable");
		} catch (UnsupportedOperationException expected) {
			Assert.assertEquals(CHILDREN, block.getChildren().size());
		}
	}

	/**
	 * Creates one random {@code [NODES, SIZE]} weight matrix per child.
	 *
	 * @param random source of the weights
	 * @return the weight matrices
	 */
	private List<PackedCollection> weights(Random random) {
		List<PackedCollection> weights = new ArrayList<>();
		for (int k = 0; k < CHILDREN; k++) {
			weights.add(randn(shape(NODES, SIZE), 0.0, 1.0, random).evaluate());
		}
		return weights;
	}

	/**
	 * Creates a bias-free dense child for each weight matrix.
	 *
	 * @param weights the weight matrices
	 * @return the dense children
	 */
	private List<Block> children(List<PackedCollection> weights) {
		List<Block> children = new ArrayList<>();
		for (PackedCollection w : weights) {
			children.add(dense(shape(SIZE), w, false, false));
		}
		return children;
	}
}
