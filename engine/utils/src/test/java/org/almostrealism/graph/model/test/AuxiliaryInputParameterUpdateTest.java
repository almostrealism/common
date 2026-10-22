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

package org.almostrealism.graph.model.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.Block;
import org.almostrealism.model.BranchBlock;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Verifies that the {@link ParameterUpdate} of a {@link Model} reaches a weight computed on a
 * branch of an auxiliary input, the way the key/value projection of cross-attention is.
 *
 * <p>Each model has the shape of cross-attention's context path: a dense weight {@code W} of shape
 * {@code (2 SIZE, SIZE)} projects the auxiliary input {@code c} on a branch, the projection is split
 * into halves {@code a = W[0:SIZE] c} and {@code b = W[SIZE:2 SIZE] c}, and the main chain reads each
 * half as a composed operand, computing {@code out = x * a * b}. The composed operands are the split
 * halves, which carry no weight, so the only route by which the update reaches {@code W} is the one
 * under test: {@link Model#setParameterUpdate} to the auxiliary input, and {@link BranchBlock} to its
 * children.</p>
 *
 * <p>For {@code L = sum(out * g)} the gradient is {@code dL/dW[i][j] = g[i] x[i] b[i] c[j]} for the
 * first half and {@code g[k] x[k] a[k] c[j]} with {@code k = i - SIZE} for the second. The update is
 * a {@link ModelTestFeatures#gradientRecorder(List) recorder}, so a weight the update never reached
 * records nothing.</p>
 */
public class AuxiliaryInputParameterUpdateTest extends TestSuiteBase implements ModelTestFeatures {
	/** Size of the main input, the auxiliary input and each half of the projection. */
	private static final int SIZE = 5;

	/** Tolerance on each element of the recorded gradient. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * An update installed with {@link Model#setParameterUpdate} after the auxiliary input and its
	 * branch are built replaces the update the model was constructed with, on the auxiliary input as
	 * well as on the main chain.
	 */
	@Test(timeout = 5 * 60000)
	public void replacedUpdateReachesAuxiliaryBranch() {
		TraversalPolicy shape = shape(SIZE);
		Model model = new Model(shape, ParameterUpdate.disabled());
		SequentialBlock context = new SequentialBlock(shape);
		model.addInput(context);

		PackedCollection weights = randomWeights(new Random(11));
		attendToProjection(model, context.branch(), weights);

		List<PackedCollection> captured = new ArrayList<>();
		model.setParameterUpdate(gradientRecorder(captured));

		assertRecordedGradient(model, weights, captured, new Random(12));
	}

	/**
	 * A {@link BranchBlock} added to an auxiliary input that already holds an update passes it to
	 * the children it already has, so a weight added inside such a child afterwards trains.
	 */
	@Test(timeout = 5 * 60000)
	public void branchPassesUpdateToExistingChildren() {
		TraversalPolicy shape = shape(SIZE);
		List<PackedCollection> captured = new ArrayList<>();
		Model model = new Model(shape, gradientRecorder(captured));
		SequentialBlock context = new SequentialBlock(shape);
		model.addInput(context);

		PackedCollection weights = randomWeights(new Random(21));
		attendToProjection(model, context.branch(), weights);

		assertRecordedGradient(model, weights, captured, new Random(22));
	}

	/**
	 * A child appended to a {@link BranchBlock} that already holds an update inherits it, so a weight
	 * added inside that child afterwards trains.
	 */
	@Test(timeout = 5 * 60000)
	public void childAppendedAfterUpdateInheritsIt() {
		TraversalPolicy shape = shape(SIZE);
		List<PackedCollection> captured = new ArrayList<>();
		Model model = new Model(shape, gradientRecorder(captured));
		SequentialBlock context = new SequentialBlock(shape);
		model.addInput(context);

		BranchBlock split = new BranchBlock(shape);
		context.add(split);
		SequentialBlock branch = split.append(new SequentialBlock(shape));

		PackedCollection weights = randomWeights(new Random(31));
		attendToProjection(model, branch, weights);

		assertRecordedGradient(model, weights, captured, new Random(32));
	}

	/**
	 * Projects {@code branch} with {@code weights}, splits the projection into halves, and multiplies
	 * the main chain of {@code model} by each half in turn.
	 *
	 * <p>As in cross-attention's key/value split, the first half stays on the branch's own chain and
	 * the second is a child of the split, so the second half's gradient is accumulated by the split and
	 * released into the projection when the first half's backward pass runs through it. The main chain
	 * reads the first half first, so its backward pass reaches the first half last.</p>
	 *
	 * @param model   the model whose main chain reads the halves
	 * @param branch  the branch of the auxiliary input to project
	 * @param weights the {@code (2 SIZE, SIZE)} projection
	 */
	private void attendToProjection(Model model, SequentialBlock branch, PackedCollection weights) {
		branch.add(dense(weights));
		branch.reshape(2, SIZE);
		List<Block> halves = branch.split(shape(1, SIZE), 0);
		Block a = halves.get(0).reshape(SIZE);
		Block b = halves.get(1).reshape(SIZE);

		SequentialBlock main = model.sequential();
		main.add(compose("a", a, (in, other) -> multiply(in, other)));
		main.add(compose("b", b, (in, other) -> multiply(in, other)));
	}

	/**
	 * Creates the {@code (2 SIZE, SIZE)} dense weight of the branch.
	 *
	 * @param random the source of the values
	 * @return the weight
	 */
	private PackedCollection randomWeights(Random random) {
		return randn(shape(2 * SIZE, SIZE), 0.0, 1.0, random).evaluate();
	}

	/**
	 * Runs one forward and backward pass and asserts that exactly one gradient, the projection's,
	 * was recorded and that it equals the analytic gradient of {@code sum(x * a * b * g)}.
	 *
	 * @param model    the model built by {@link #attendToProjection}
	 * @param weights  the projection, as it was before the pass
	 * @param captured the list the model's update records into
	 * @param random   the source of the inputs and the output gradient
	 */
	private void assertRecordedGradient(Model model, PackedCollection weights,
										List<PackedCollection> captured, Random random) {
		TraversalPolicy shape = shape(SIZE);
		PackedCollection x = randn(shape, 0.0, 1.0, random).evaluate();
		PackedCollection c = randn(shape, 0.0, 1.0, random).evaluate();
		PackedCollection g = randn(shape, 0.0, 1.0, random).evaluate();

		double[] projection = new double[2 * SIZE];
		for (int i = 0; i < 2 * SIZE; i++) {
			for (int j = 0; j < SIZE; j++) {
				projection[i] += weights.toDouble(i * SIZE + j) * c.toDouble(j);
			}
		}

		CompiledModel compiled = model.compile(true, true);
		try {
			compiled.forward(x, c);
			compiled.backward(g);

			Assert.assertEquals("recorded gradients", 1, captured.size());
			PackedCollection gradient = captured.get(0);
			Assert.assertEquals(2 * SIZE * SIZE, gradient.getShape().getTotalSize());

			double maxAbs = 0.0;
			for (int i = 0; i < 2 * SIZE; i++) {
				int k = i % SIZE;
				double other = i < SIZE ? projection[k + SIZE] : projection[k];
				for (int j = 0; j < SIZE; j++) {
					double expected = g.toDouble(k) * x.toDouble(k) * other * c.toDouble(j);
					maxAbs = Math.max(maxAbs, Math.abs(expected));
					Assert.assertEquals("dL/dW[" + i + "][" + j + "]", expected,
							gradient.toDouble(i * SIZE + j), TOLERANCE);
				}
			}

			Assert.assertTrue("gradient is trivially zero", maxAbs > 1e-3);
		} finally {
			compiled.destroy();
		}
	}
}
