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
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for the N-ary routing steps of {@link SequentialBlock}:
 * {@link SequentialBlock#accum(List, io.almostrealism.compute.ComputeRequirement...)} and
 * {@link SequentialBlock#concat(List, io.almostrealism.compute.ComputeRequirement...)}.
 * Each applies its blocks to the sequential block's <em>current</em> output, so every test
 * first appends a bias-free dense layer that changes the shape, which proves the new step is
 * built over that output rather than over the sequential block's input.
 */
public class SequentialBlockRoutingTests extends TestSuiteBase implements LayerRoutingFeatures {
	/** Input size of the sequential block. */
	private static final int INPUT = 5;
	/** Output size of the leading dense layer, and input size of every routed block. */
	private static final int HIDDEN = 4;
	/** Output size of the routed blocks. */
	private static final int NODES = 3;

	/**
	 * {@code accum} appends one {@link AccumulationBlock} that takes the current output and
	 * whose forward pass is the sum of every block's output for that output.
	 */
	@Test(timeout = 120000)
	public void accumSumsBlocksOverCurrentOutput() {
		Random random = new Random(21);
		PackedCollection lead = randn(shape(HIDDEN, INPUT), 0.0, 1.0, random).evaluate();
		List<PackedCollection> weights = List.of(
				randn(shape(NODES, HIDDEN), 0.0, 1.0, random).evaluate(),
				randn(shape(NODES, HIDDEN), 0.0, 1.0, random).evaluate());
		PackedCollection input = randn(shape(INPUT), 0.0, 1.0, random).evaluate();

		SequentialBlock block = new SequentialBlock(shape(INPUT));
		block.add(dense(shape(INPUT), lead, false, false));
		block.accum(denseBlocks(weights));

		Assert.assertEquals(2, block.getBlocks().size());
		Block last = block.getBlocks().get(1);
		Assert.assertTrue(last instanceof AccumulationBlock);
		Assert.assertEquals(HIDDEN, last.getInputShape().getTotalSize());
		Assert.assertEquals(INPUT, block.getInputShape().getTotalSize());
		Assert.assertEquals(NODES, block.getOutputShape().getTotalSize());

		double[] hidden = multiply(lead, input, HIDDEN, INPUT);
		PackedCollection out = forward(block, input);
		Assert.assertEquals(NODES, out.getShape().getTotalSize());
		for (int i = 0; i < NODES; i++) {
			double expected = 0.0;
			for (PackedCollection w : weights) {
				for (int j = 0; j < HIDDEN; j++) {
					expected += w.toDouble(i * HIDDEN + j) * hidden[j];
				}
			}
			Assert.assertEquals("output " + i, expected, out.toDouble(i), 1e-5);
		}
	}

	/** {@code accum} over no blocks has no output shape and is rejected without changing the block. */
	@Test(timeout = 60000)
	public void accumRejectsNoBlocks() {
		SequentialBlock block = new SequentialBlock(shape(INPUT));
		try {
			block.accum(new ArrayList<>());
			Assert.fail("accum over no blocks must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("at least one block"));
		}
		Assert.assertTrue(block.getBlocks().isEmpty());
		Assert.assertEquals(shape(INPUT), block.getOutputShape());
	}

	/**
	 * {@code concat} appends a step that takes the current output and produces every block's
	 * output, in order, as one flat vector whose size is the sum of the blocks' output sizes.
	 */
	@Test(timeout = 120000)
	public void concatJoinsBlocksOverCurrentOutputInOrder() {
		Random random = new Random(22);
		PackedCollection lead = randn(shape(HIDDEN, INPUT), 0.0, 1.0, random).evaluate();
		List<PackedCollection> weights = List.of(
				randn(shape(NODES, HIDDEN), 0.0, 1.0, random).evaluate(),
				randn(shape(NODES + 1, HIDDEN), 0.0, 1.0, random).evaluate());
		PackedCollection input = randn(shape(INPUT), 0.0, 1.0, random).evaluate();

		SequentialBlock block = new SequentialBlock(shape(INPUT));
		block.add(dense(shape(INPUT), lead, false, false));
		block.concat(denseBlocks(weights));

		Assert.assertEquals(2, block.getBlocks().size());
		Assert.assertEquals(HIDDEN, block.getBlocks().get(1).getInputShape().getTotalSize());
		Assert.assertEquals(2 * NODES + 1, block.getOutputShape().getTotalSize());

		double[] hidden = multiply(lead, input, HIDDEN, INPUT);
		PackedCollection out = forward(block, input);
		Assert.assertEquals(2 * NODES + 1, out.getShape().getTotalSize());
		int offset = 0;
		for (PackedCollection w : weights) {
			int rows = w.getShape().length(0);
			double[] expected = multiply(w, hidden, rows, HIDDEN);
			for (int i = 0; i < rows; i++) {
				Assert.assertEquals("output " + (offset + i), expected[i], out.toDouble(offset + i), 1e-5);
			}
			offset += rows;
		}
	}

	/**
	 * Compiles a model containing only the given block and runs one forward pass.
	 *
	 * @param block the block under test
	 * @param input the model input
	 * @return a copy of the model output
	 */
	private PackedCollection forward(SequentialBlock block, PackedCollection input) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		try (CompiledModel compiled = model.compile(false)) {
			return new PackedCollection(compiled.forward(input));
		}
	}

	/**
	 * Computes the reference matrix-vector product {@code W·x} on the host.
	 *
	 * @param w    the {@code [rows, cols]} weight matrix
	 * @param x    the vector of length {@code cols}
	 * @param rows the number of rows of {@code w}
	 * @param cols the number of columns of {@code w}
	 * @return the product, of length {@code rows}
	 */
	private static double[] multiply(PackedCollection w, PackedCollection x, int rows, int cols) {
		return multiply(w, x.toArray(0, cols), rows, cols);
	}

	/**
	 * Computes the reference matrix-vector product {@code W·x} on the host.
	 *
	 * @param w    the {@code [rows, cols]} weight matrix
	 * @param x    the vector of length {@code cols}
	 * @param rows the number of rows of {@code w}
	 * @param cols the number of columns of {@code w}
	 * @return the product, of length {@code rows}
	 */
	private static double[] multiply(PackedCollection w, double[] x, int rows, int cols) {
		double[] result = new double[rows];
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < cols; j++) {
				result[i] += w.toDouble(i * cols + j) * x[j];
			}
		}
		return result;
	}

	/**
	 * Creates a bias-free dense block over {@link #HIDDEN} inputs for each weight matrix.
	 *
	 * @param weights the weight matrices
	 * @return the dense blocks
	 */
	private List<Block> denseBlocks(List<PackedCollection> weights) {
		List<Block> blocks = new ArrayList<>();
		for (PackedCollection w : weights) {
			blocks.add(dense(shape(HIDDEN), w, false, false));
		}
		return blocks;
	}
}
