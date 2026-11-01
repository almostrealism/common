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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.ConcatenationBlock;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link ConcatenationBlock}, the block behind
 * {@link LayerRoutingFeatures#concatBlocks}: the forward pass is the flat concatenation of the
 * children applied to one input, in child order, and the backward pass gives each child the slice
 * of the output gradient for its segment (so the weights inside each concatenated part receive
 * their own gradient) and returns the sum of the children's input-gradients upstream.
 *
 * <p>The trainable children are bias-free dense layers {@code y_k = W_k·x} of <em>different</em>
 * output sizes, so the expected values are exact: the output is {@code [W_0·x | W_1·x | ...]}, the
 * gradient of {@code W_k} is the outer product {@code g_k·xᵀ} of that child's gradient slice with
 * the input, and the input gradient is {@code Σ_k W_kᵀ·g_k}. A {@code cache_read} child whose
 * output does not depend on the input is concatenated alongside them, to confirm an
 * input-independent part receives its segment of the gradient and contributes zero to the summed
 * input gradient.</p>
 */
public class ConcatenationBlockTests extends TestSuiteBase implements LayerRoutingFeatures, ModelTestFeatures {
	/** Input size every child receives. */
	private static final int SIZE = 4;
	/** Output size of the first trainable child. */
	private static final int NODES_A = 3;
	/** Output size of the second trainable child. */
	private static final int NODES_B = 2;
	/** Row size of the input-independent cache child. */
	private static final int CACHE = 2;
	/** Total concatenated output size. */
	private static final int TOTAL = NODES_A + NODES_B + CACHE;

	/** The forward pass is the flat concatenation of every child's output, in child order. */
	@Test(timeout = 120000)
	public void forwardConcatenatesChildren() {
		Random random = new Random(21);
		PackedCollection weightA = randn(shape(NODES_A, SIZE), 0.0, 1.0, random).evaluate();
		PackedCollection weightB = randn(shape(NODES_B, SIZE), 0.0, 1.0, random).evaluate();
		PackedCollection cache = randn(shape(1, CACHE), 0.0, 1.0, random).evaluate();
		PackedCollection input = randn(shape(SIZE), 0.0, 1.0, random).evaluate();

		Model model = new Model(shape(SIZE));
		model.add(concatBlocks(shape(SIZE), children(weightA, weightB, cache)));
		try (CompiledModel compiled = model.compile(false)) {
			PackedCollection out = compiled.forward(input);

			Assert.assertEquals(TOTAL, out.getShape().getTotalSize());
			for (int i = 0; i < NODES_A; i++) {
				Assert.assertEquals("segment a " + i, dot(weightA, i, input), out.toDouble(i), 1e-5);
			}
			for (int i = 0; i < NODES_B; i++) {
				Assert.assertEquals("segment b " + i, dot(weightB, i, input), out.toDouble(NODES_A + i), 1e-5);
			}
			for (int i = 0; i < CACHE; i++) {
				Assert.assertEquals("segment cache " + i, cache.toDouble(i),
						out.toDouble(NODES_A + NODES_B + i), 1e-5);
			}
		}
	}

	/**
	 * Every child's weights receive the slice of the output gradient for its own segment, the
	 * input-independent child contributes nothing to the input gradient, and the input gradient is
	 * the sum of the trainable children's input-gradients. A second backward pass with a different
	 * output gradient must report only its own gradients, which proves the shared accumulation
	 * buffer is cleared between passes rather than carrying the previous step's sum forward.
	 */
	@Test(timeout = 120000)
	public void backwardSlicesGradientToEveryChildAndSumsInputGradients() {
		Random random = new Random(22);
		PackedCollection weightA = randn(shape(NODES_A, SIZE), 0.0, 1.0, random).evaluate();
		PackedCollection weightB = randn(shape(NODES_B, SIZE), 0.0, 1.0, random).evaluate();
		PackedCollection cache = randn(shape(1, CACHE), 0.0, 1.0, random).evaluate();
		PackedCollection input = randn(shape(SIZE), 0.0, 1.0, random).evaluate();

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape(SIZE), gradientRecorder(recorded));
		model.add(concatBlocks(shape(SIZE), children(weightA, weightB, cache)));
		try (CompiledModel compiled = model.compile(true, true)) {
			// The cache child holds no weights, so only the two dense children record a gradient.
			Assert.assertEquals(2, recorded.size());
			PackedCollection gradientA = recorded.get(0);
			PackedCollection gradientB = recorded.get(1);

			for (int pass = 0; pass < 2; pass++) {
				PackedCollection outputGradient = randn(shape(TOTAL), 0.0, 1.0, random).evaluate();
				compiled.forward(input);
				PackedCollection inputGradient = compiled.backward(outputGradient);

				// Each dense weight gets the outer product of its gradient slice with the input.
				assertWeightGradient("a", weightA, gradientA, outputGradient, 0, input, pass);
				assertWeightGradient("b", weightB, gradientB, outputGradient, NODES_A, input, pass);

				// The input gradient is W_aᵀ·g_a + W_bᵀ·g_b; the cache child adds nothing.
				Assert.assertEquals(SIZE, inputGradient.getShape().getTotalSize());
				for (int j = 0; j < SIZE; j++) {
					double expected = 0.0;
					for (int i = 0; i < NODES_A; i++) {
						expected += weightA.toDouble(i * SIZE + j) * outputGradient.toDouble(i);
					}
					for (int i = 0; i < NODES_B; i++) {
						expected += weightB.toDouble(i * SIZE + j) * outputGradient.toDouble(NODES_A + i);
					}
					Assert.assertEquals("pass " + pass + " input " + j, expected, inputGradient.toDouble(j), 1e-5);
				}
			}
		}
	}

	/**
	 * A {@link Block} is allowed to have no backward cell ({@link Block#getBackward()} returns
	 * {@code null}, as a forward-only {@link DefaultBlock} does). Compiling a concatenation that
	 * mixes such a forward-only child with a trainable one for training must not fail: the
	 * forward-only child still contributes its segment to the forward output, is skipped in the
	 * backward pass, and contributes nothing to the summed input gradient, so the input gradient is
	 * exactly the trainable child's {@code W_aᵀ·g_a}.
	 */
	@Test(timeout = 120000)
	public void skipsChildrenWithNoBackwardCell() {
		Random random = new Random(25);
		PackedCollection weightA = randn(shape(NODES_A, SIZE), 0.0, 1.0, random).evaluate();
		PackedCollection constant = randn(shape(CACHE), 0.0, 1.0, random).evaluate();
		PackedCollection input = randn(shape(SIZE), 0.0, 1.0, random).evaluate();

		Block forwardOnly = forwardOnly(constant);
		Assert.assertNull("the forward-only child must have no backward cell", forwardOnly.getBackward());

		List<Block> children = new ArrayList<>();
		children.add(dense(shape(SIZE), weightA, false, false));
		children.add(forwardOnly);

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape(SIZE), gradientRecorder(recorded));
		model.add(concatBlocks(shape(SIZE), children));
		try (CompiledModel compiled = model.compile(true, true)) {
			// Only the dense child holds weights, so only it records a gradient.
			Assert.assertEquals(1, recorded.size());
			PackedCollection gradientA = recorded.get(0);

			PackedCollection out = compiled.forward(input);
			Assert.assertEquals(NODES_A + CACHE, out.getShape().getTotalSize());
			for (int i = 0; i < NODES_A; i++) {
				Assert.assertEquals("segment a " + i, dot(weightA, i, input), out.toDouble(i), 1e-5);
			}
			for (int i = 0; i < CACHE; i++) {
				Assert.assertEquals("segment constant " + i, constant.toDouble(i),
						out.toDouble(NODES_A + i), 1e-5);
			}

			PackedCollection outputGradient = randn(shape(NODES_A + CACHE), 0.0, 1.0, random).evaluate();
			PackedCollection inputGradient = compiled.backward(outputGradient);

			// The dense child receives only its own slice of the output gradient.
			assertWeightGradient("a", weightA, gradientA, outputGradient, 0, input, 0);

			// The forward-only child contributes nothing, so the input gradient is W_aᵀ·g_a alone.
			Assert.assertEquals(SIZE, inputGradient.getShape().getTotalSize());
			for (int j = 0; j < SIZE; j++) {
				double expected = 0.0;
				for (int i = 0; i < NODES_A; i++) {
					expected += weightA.toDouble(i * SIZE + j) * outputGradient.toDouble(i);
				}
				Assert.assertEquals("input " + j, expected, inputGradient.toDouble(j), 1e-5);
			}
		}
	}

	/**
	 * A forward-only block (no backward cell) wrapped in a {@link SequentialBlock} — the form a PDSL
	 * inline part takes — gives a chain whose own backward cell is non-null while its first or last
	 * child has none. Compiling such a concatenation for training must not fail: a chain that ends
	 * in the forward-only block produces no gradient, and a chain that starts with it still trains
	 * the weights after it ({@code W_b} receives {@code g_b·cᵀ} for the constant {@code c}) while
	 * contributing nothing upstream, so the input gradient is exactly {@code W_aᵀ·g_a}.
	 */
	@Test(timeout = 120000)
	public void skipsSequentialChildrenWithForwardOnlyEnds() {
		Random random = new Random(26);
		PackedCollection weightA = randn(shape(NODES_A, SIZE), 0.0, 1.0, random).evaluate();
		PackedCollection weightB = randn(shape(NODES_B, CACHE), 0.0, 1.0, random).evaluate();
		PackedCollection cache = randn(shape(CACHE), 0.0, 1.0, random).evaluate();
		PackedCollection input = randn(shape(SIZE), 0.0, 1.0, random).evaluate();

		SequentialBlock readOnly = new SequentialBlock(shape(SIZE));
		readOnly.add(forwardOnly(cache));
		Assert.assertNotNull(readOnly.getBackward());

		SequentialBlock readThenDense = new SequentialBlock(shape(SIZE));
		readThenDense.add(forwardOnly(cache));
		readThenDense.add(dense(shape(CACHE), weightB, false, false));

		List<Block> children = new ArrayList<>();
		children.add(dense(shape(SIZE), weightA, false, false));
		children.add(readOnly);
		children.add(readThenDense);

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape(SIZE), gradientRecorder(recorded));
		model.add(concatBlocks(shape(SIZE), children));
		try (CompiledModel compiled = model.compile(true, true)) {
			Assert.assertEquals(2, recorded.size());
			PackedCollection gradientA = recorded.get(0);
			PackedCollection gradientB = recorded.get(1);

			PackedCollection out = compiled.forward(input);
			Assert.assertEquals(NODES_A + CACHE + NODES_B, out.getShape().getTotalSize());
			for (int i = 0; i < CACHE; i++) {
				Assert.assertEquals("segment cache " + i, cache.toDouble(i), out.toDouble(NODES_A + i), 1e-5);
			}
			for (int i = 0; i < NODES_B; i++) {
				double expected = 0.0;
				for (int j = 0; j < CACHE; j++) {
					expected += weightB.toDouble(i * CACHE + j) * cache.toDouble(j);
				}
				Assert.assertEquals("segment b " + i, expected, out.toDouble(NODES_A + CACHE + i), 1e-5);
			}

			PackedCollection outputGradient = randn(shape(NODES_A + CACHE + NODES_B), 0.0, 1.0, random).evaluate();
			PackedCollection inputGradient = compiled.backward(outputGradient);

			assertWeightGradient("a", weightA, gradientA, outputGradient, 0, input, 0);

			// The dense after the cache read sees the cache row as its input.
			for (int i = 0; i < NODES_B; i++) {
				for (int j = 0; j < CACHE; j++) {
					Assert.assertEquals("weight b [" + i + ", " + j + "]",
							outputGradient.toDouble(NODES_A + CACHE + i) * cache.toDouble(j),
							gradientB.toDouble(i * CACHE + j), 1e-5);
				}
			}

			for (int j = 0; j < SIZE; j++) {
				double expected = 0.0;
				for (int i = 0; i < NODES_A; i++) {
					expected += weightA.toDouble(i * SIZE + j) * outputGradient.toDouble(i);
				}
				Assert.assertEquals("input " + j, expected, inputGradient.toDouble(j), 1e-5);
			}
		}
	}

	/**
	 * Under {@link ModelOptimizer}, the parameter update set on the model reaches the weights inside
	 * every concatenated part: training a concatenation of two dense parts toward a fixed linear
	 * target changes both weight matrices and lowers the loss.
	 */
	@Test(timeout = 120000)
	public void optimizerTrainsEveryConcatenatedPart() {
		Random random = new Random(27);
		PackedCollection weightA = randn(shape(NODES_A, SIZE), 0.0, 0.1, random).evaluate();
		PackedCollection weightB = randn(shape(NODES_B, SIZE), 0.0, 0.1, random).evaluate();
		PackedCollection target = randn(shape(NODES_A + NODES_B, SIZE), 0.0, 1.0, random).evaluate();
		double[] initialA = weightA.toArray();
		double[] initialB = weightB.toArray();

		List<Block> children = new ArrayList<>();
		children.add(dense(shape(SIZE), weightA, false, false));
		children.add(dense(shape(SIZE), weightB, false, false));

		Model model = new Model(shape(SIZE), 1e-2);
		model.add(concatBlocks(shape(SIZE), children));

		List<ValueTarget<PackedCollection>> samples = new ArrayList<>();
		for (int s = 0; s < 16; s++) {
			PackedCollection x = randn(shape(SIZE), 0.0, 1.0, random).evaluate();
			samples.add(ValueTarget.of(x, matmul(cp(target), cp(x)).evaluate()));
		}
		Dataset<PackedCollection> data = Dataset.of(samples);

		try (CompiledModel compiled = model.compile()) {
			double initialLoss = meanSquaredError(compiled, samples);

			ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> data);
			optimizer.setLossFunction(new MeanSquaredError(shape(NODES_A + NODES_B).traverseEach()));
			optimizer.setLogFrequency(0);
			optimizer.optimize(5);

			double finalLoss = meanSquaredError(compiled, samples);
			Assert.assertTrue("loss must fall: " + initialLoss + " -> " + finalLoss, finalLoss < initialLoss);
			Assert.assertFalse("part a weights must change", Arrays.equals(initialA, weightA.toArray()));
			Assert.assertFalse("part b weights must change", Arrays.equals(initialB, weightB.toArray()));
		}
	}

	/** A concatenation of nothing has no output and is rejected. */
	@Test(timeout = 60000)
	public void rejectsNoChildren() {
		try {
			new ConcatenationBlock(shape(SIZE), new ArrayList<>());
			Assert.fail("a ConcatenationBlock with no children must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("at least one block"));
		}
	}

	/**
	 * Every child receives the same input and contributes an input-gradient to one input-sized
	 * buffer, so a child expecting an input of a different size is rejected, and the message names
	 * both shapes.
	 */
	@Test(timeout = 60000)
	public void rejectsMismatchedInputShapes() {
		Random random = new Random(23);
		List<Block> children = new ArrayList<>();
		children.add(dense(shape(SIZE), randn(shape(NODES_A, SIZE), 0.0, 1.0, random).evaluate(), false, false));
		children.add(dense(shape(SIZE + 1), randn(shape(NODES_B, SIZE + 1), 0.0, 1.0, random).evaluate(), false, false));
		try {
			new ConcatenationBlock(shape(SIZE), children);
			Assert.fail("a child expecting a different input size must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("accept its input"));
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(String.valueOf(SIZE + 1)));
		}
	}

	/**
	 * Unlike an accumulation, a concatenation accepts children whose output shapes differ: each
	 * contributes its own segment, and the output shape is the sum of the segment sizes.
	 */
	@Test(timeout = 60000)
	public void acceptsChildrenOfDifferentOutputShapes() {
		Random random = new Random(24);
		PackedCollection weightA = randn(shape(NODES_A, SIZE), 0.0, 1.0, random).evaluate();
		PackedCollection weightB = randn(shape(NODES_B, SIZE), 0.0, 1.0, random).evaluate();
		List<Block> children = new ArrayList<>();
		children.add(dense(shape(SIZE), weightA, false, false));
		children.add(dense(shape(SIZE), weightB, false, false));

		ConcatenationBlock block = new ConcatenationBlock(shape(SIZE), children);
		Assert.assertEquals(shape(SIZE), block.getInputShape());
		Assert.assertEquals(NODES_A + NODES_B, block.getOutputShape().getTotalSize());
		Assert.assertEquals(2, block.getChildren().size());

		try {
			block.getChildren().clear();
			Assert.fail("getChildren() must be unmodifiable");
		} catch (UnsupportedOperationException expected) {
			Assert.assertEquals(2, block.getChildren().size());
		}
	}

	/**
	 * Compute requirements given to {@link LayerRoutingFeatures#concatBlocks} reach every child, and
	 * a concatenation built without requirements leaves the children untouched.
	 */
	@Test(timeout = 60000)
	public void concatBlocksPropagatesComputeRequirements() {
		List<RecordingBlock> recording = recordingChildren(new AtomicInteger());
		Block concat = concatBlocks(shape(SIZE), new ArrayList<>(recording), ComputeRequirement.CPU);
		Assert.assertTrue(concat instanceof ConcatenationBlock);
		for (RecordingBlock child : recording) {
			Assert.assertEquals(List.of(ComputeRequirement.CPU), child.requirements);
		}

		List<RecordingBlock> untouched = recordingChildren(new AtomicInteger());
		concatBlocks(shape(SIZE), new ArrayList<>(untouched));
		for (RecordingBlock child : untouched) {
			Assert.assertNull(child.requirements);
		}
	}

	/**
	 * The setup of a concatenation runs the setup of every child exactly once, since the children
	 * are driven by the concatenation rather than by an enclosing
	 * {@link org.almostrealism.model.SequentialBlock}.
	 */
	@Test(timeout = 60000)
	public void setupRunsEveryChildSetup() {
		AtomicInteger setups = new AtomicInteger();
		List<RecordingBlock> children = recordingChildren(setups);
		ConcatenationBlock block = new ConcatenationBlock(shape(SIZE), new ArrayList<>(children));
		Assert.assertEquals(0, setups.get());

		block.setup().get().run();
		Assert.assertEquals(children.size(), setups.get());
	}

	/**
	 * The gradient buffer is allocated only when a gradient is first pushed, so destroying a block
	 * used solely for inference must still destroy every child exactly once without touching the
	 * absent buffer, and destroying a block whose backward was built must do the same.
	 */
	@Test(timeout = 60000)
	public void destroyReleasesChildrenWithOrWithoutBackward() {
		List<RecordingBlock> inferenceOnly = recordingChildren(new AtomicInteger());
		ConcatenationBlock forwardBlock = new ConcatenationBlock(shape(SIZE), new ArrayList<>(inferenceOnly));
		Assert.assertNotNull(forwardBlock.getForward());
		forwardBlock.destroy();
		for (RecordingBlock child : inferenceOnly) {
			Assert.assertEquals(1, child.destroyed);
		}

		List<RecordingBlock> trained = recordingChildren(new AtomicInteger());
		ConcatenationBlock backwardBlock = new ConcatenationBlock(shape(SIZE), new ArrayList<>(trained));
		Assert.assertSame(backwardBlock.getBackward(), backwardBlock.getBackward());
		backwardBlock.destroy();
		for (RecordingBlock child : trained) {
			Assert.assertEquals(1, child.destroyed);
		}
	}

	/**
	 * Asserts a dense child's recorded weight gradient is the outer product of its output-gradient
	 * slice with the input.
	 *
	 * @param label          a label for the failure message
	 * @param weights        the child's weight matrix, shape {@code [nodes, SIZE]}
	 * @param gradient       the gradient recorded for {@code weights}
	 * @param outputGradient the full output gradient of the concatenation
	 * @param offset         the flat offset of this child's segment in the output gradient
	 * @param input          the shared input
	 * @param pass           the backward-pass index, for the failure message
	 */
	private void assertWeightGradient(String label, PackedCollection weights, PackedCollection gradient,
									  PackedCollection outputGradient, int offset, PackedCollection input, int pass) {
		int nodes = weights.getShape().length(0);
		for (int i = 0; i < nodes; i++) {
			for (int j = 0; j < SIZE; j++) {
				Assert.assertEquals("pass " + pass + " child " + label + " weight [" + i + ", " + j + "]",
						outputGradient.toDouble(offset + i) * input.toDouble(j),
						gradient.toDouble(i * SIZE + j), 1e-5);
			}
		}
	}

	/**
	 * The mean squared error of the model's forward output against every sample's target.
	 *
	 * @param compiled the compiled model
	 * @param samples  the input/target pairs
	 * @return the mean over samples and output elements of the squared error
	 */
	private double meanSquaredError(CompiledModel compiled, List<ValueTarget<PackedCollection>> samples) {
		double sum = 0.0;
		int count = 0;
		for (ValueTarget<PackedCollection> sample : samples) {
			double[] out = compiled.forward(sample.getInput()).toArray();
			double[] expected = sample.getExpectedOutput().toArray();
			for (int i = 0; i < out.length; i++) {
				double d = out[i] - expected[i];
				sum += d * d;
				count++;
			}
		}
		return sum / count;
	}

	/**
	 * The dot product of row {@code row} of a {@code [nodes, SIZE]} weight matrix with the input.
	 *
	 * @param weights the weight matrix
	 * @param row     the row index
	 * @param input   the input vector of {@link #SIZE} values
	 * @return the dot product
	 */
	private double dot(PackedCollection weights, int row, PackedCollection input) {
		double sum = 0.0;
		for (int j = 0; j < SIZE; j++) {
			sum += weights.toDouble(row * SIZE + j) * input.toDouble(j);
		}
		return sum;
	}

	/**
	 * Creates a forward-only child of {@link #SIZE} inputs: it pushes {@code constant} as its output
	 * segment, ignoring the input, and has no backward cell.
	 *
	 * @param constant the {@code [CACHE]} values the child outputs
	 * @return the forward-only child
	 */
	private Block forwardOnly(PackedCollection constant) {
		Cell<PackedCollection> forwardCell = Cell.of((in, next) ->
				next == null ? new OperationList() : next.push(cp(constant)));
		return new DefaultBlock(shape(SIZE), shape(CACHE), forwardCell, null);
	}

	/**
	 * Creates the three children of the gradient tests: two bias-free dense children of different
	 * output sizes and an input-independent {@code cache_read} child reading row 0 of {@code cache}.
	 *
	 * @param weightA the first dense child's weights, shape {@code [NODES_A, SIZE]}
	 * @param weightB the second dense child's weights, shape {@code [NODES_B, SIZE]}
	 * @param cache   the {@code [1, CACHE]} cache the third child reads
	 * @return the children, in concatenation order
	 */
	private List<Block> children(PackedCollection weightA, PackedCollection weightB, PackedCollection cache) {
		List<Block> children = new ArrayList<>();
		children.add(dense(shape(SIZE), weightA, false, false));
		children.add(dense(shape(SIZE), weightB, false, false));
		children.add(cacheRead(shape(SIZE), cp(cache), c(0.0).reshape(shape(1))));
		return children;
	}

	/**
	 * Creates three recording children of differing output sizes whose setup increments
	 * {@code setups}.
	 *
	 * @param setups counter incremented each time any child's setup runs
	 * @return the recording children
	 */
	private List<RecordingBlock> recordingChildren(AtomicInteger setups) {
		List<RecordingBlock> children = new ArrayList<>();
		children.add(new RecordingBlock(setups, NODES_A));
		children.add(new RecordingBlock(setups, NODES_B));
		children.add(new RecordingBlock(setups, CACHE));
		return children;
	}

	/**
	 * A pass-through child that records the compute requirements it is given, counts the executions
	 * of its setup, and may be given any output size so a concatenation of differing segments can be
	 * built.
	 */
	private static class RecordingBlock extends DefaultBlock {
		/** The requirements most recently given to this block, or {@code null} if none were. */
		private List<ComputeRequirement> requirements;

		/** The number of times this block has been destroyed. */
		private int destroyed;

		/**
		 * Creates a block mapping {@link #SIZE} inputs to {@code nodes} outputs.
		 *
		 * @param setups counter incremented each time this block's setup runs
		 * @param nodes  this block's output size
		 */
		RecordingBlock(AtomicInteger setups, int nodes) {
			super(new TraversalPolicy(SIZE),
					new TraversalPolicy(nodes), null, null,
					() -> setups::incrementAndGet);
		}

		/** {@inheritDoc} */
		@Override
		public void setComputeRequirements(List<ComputeRequirement> requirements) {
			this.requirements = requirements;
		}

		/** {@inheritDoc} */
		@Override
		public void destroy() {
			destroyed++;
		}
	}
}
