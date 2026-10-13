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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Standalone tests of the {@code add(other)} PDSL primitive, each exercised by a small fixture layer
 * of {@code /pdsl/test_add_primitives.pdsl} and compared against values computed on the host. The
 * operand is either a tensor the caller binds or a branch the layer splits off from its own signal;
 * for a branch, the tests pin the backward pass as well as the forward one: the input receives the
 * gradient of both paths, and a weight inside the branch receives its own gradient. These hold the
 * primitive to its contract independently of the differential attention migration that motivated it.
 */
public class AddPrimitiveTest extends TestSuiteBase implements ModelTestFeatures {

	/** Classpath location of the {@code add} fixtures. */
	private static final String FIXTURE = "/pdsl/test_add_primitives.pdsl";

	/** Rows of the inputs the element-wise fixtures are run over. */
	private static final int ROWS = 2;

	/** Features per row of the inputs. */
	private static final int SIZE = 3;

	/** Width of the vector the dense-branch fixture projects. */
	private static final int WIDTH = 4;

	/** The factor the main path of {@code add_branch} scales its input by. */
	private static final double MAIN_FACTOR = 3.0;

	/** The factor the branch of {@code add_branch} scales its input by. */
	private static final double BRANCH_FACTOR = 2.0;

	/** Agreement required between a computed value and its host expectation. */
	private static final double TOLERANCE = 1e-5;

	/** A tensor operand is added to the input element by element. */
	@Test(timeout = 120000)
	public void tensorOperandIsAddedElementwise() {
		PackedCollection input = new PackedCollection(shape(ROWS, SIZE)).randnFill();
		PackedCollection other = new PackedCollection(shape(ROWS, SIZE)).randnFill();
		double[] x = input.doubleStream().toArray();
		double[] o = other.doubleStream().toArray();

		double[] actual = forward("add_tensor", shape(ROWS, SIZE), arguments("other", other), input);
		assertEquals(x.length, actual.length);
		for (int i = 0; i < x.length; i++) {
			assertEquals("element " + i, x[i] + o[i], actual[i], TOLERANCE);
		}
	}

	/**
	 * A branch operand is added to the main path: with the main path scaled by three and the branch by
	 * two, every output element is five times its input.
	 */
	@Test(timeout = 120000)
	public void branchOperandIsAddedToTheMainPath() {
		PackedCollection input = new PackedCollection(shape(ROWS, SIZE)).randnFill();
		double[] x = input.doubleStream().toArray();

		double[] actual = forward("add_branch", shape(ROWS, SIZE), factors(), input);
		assertEquals(x.length, actual.length);
		for (int i = 0; i < x.length; i++) {
			assertEquals("element " + i, (MAIN_FACTOR + BRANCH_FACTOR) * x[i], actual[i], TOLERANCE);
		}
	}

	/**
	 * A backward pass carries the sum's gradient into the branch as well as along the main path: for
	 * {@code y = 3x + 2x} the input receives {@code 5 g}. A sum whose operand were a constant copy of
	 * the branch would deliver the main path's {@code 3 g} alone.
	 */
	@Test(timeout = 300000)
	public void branchOperandCarriesTheGradientIntoTheBranch() {
		TraversalPolicy inputShape = shape(ROWS, SIZE);
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection outputGradient = new PackedCollection(inputShape).randnFill();
		double[] g = outputGradient.doubleStream().toArray();

		PdslLoader loader = new PdslLoader();
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(FIXTURE), "add_branch", inputShape, factors()));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] actual = compiled.backward(outputGradient).doubleStream().toArray();
			assertEquals(g.length, actual.length);
			for (int i = 0; i < g.length; i++) {
				assertEquals("input gradient element " + i,
						(MAIN_FACTOR + BRANCH_FACTOR) * g[i], actual[i], TOLERANCE);
			}
		}
	}

	/**
	 * A weight inside a branch operand is trained through the sum. For {@code y = x + W x} and an
	 * output gradient {@code g}, the input receives {@code g + Wᵀ g} and the weight receives
	 * {@code g xᵀ}; both are compared against their host values.
	 */
	@Test(timeout = 300000)
	public void weightsInsideTheBranchReceiveTheirGradient() {
		TraversalPolicy inputShape = shape(1, WIDTH);
		PackedCollection weights = new PackedCollection(shape(WIDTH, WIDTH)).randnFill();
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection outputGradient = new PackedCollection(inputShape).randnFill();
		double[] w = weights.doubleStream().toArray();
		double[] x = input.doubleStream().toArray();
		double[] g = outputGradient.doubleStream().toArray();

		List<PackedCollection> recorded = new ArrayList<>();
		PdslLoader loader = new PdslLoader();
		Model model = new Model(inputShape, gradientRecorder(recorded));
		model.add(loader.buildLayer(loader.parseResource(FIXTURE), "add_dense_branch", inputShape,
				arguments("weights", weights)));
		try (CompiledModel compiled = model.compile(true, true)) {
			double[] output = compiled.forward(input).doubleStream().toArray();
			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();
			assertEquals("one recorded gradient, for the branch's weights", 1, recorded.size());
			double[] weightGradient = recorded.get(0).doubleStream().toArray();

			for (int o = 0; o < WIDTH; o++) {
				double projected = 0.0;
				double transposed = 0.0;
				for (int i = 0; i < WIDTH; i++) {
					projected += w[o * WIDTH + i] * x[i];
					transposed += w[i * WIDTH + o] * g[i];
					assertEquals("weight gradient [" + o + ", " + i + "]",
							g[o] * x[i], weightGradient[o * WIDTH + i], TOLERANCE);
				}
				assertEquals("output element " + o, x[o] + projected, output[o], TOLERANCE);
				assertEquals("input gradient element " + o, g[o] + transposed, inputGradient[o], TOLERANCE);
			}
		}
	}

	/**
	 * A branch feeds one stage: reading the same branch from a second {@code add} would silently
	 * displace the first stage's consumer, so the second use is rejected.
	 */
	@Test(timeout = 60000)
	public void reuseOfABranchIsRejected() {
		Map<String, Object> args = new HashMap<>();
		args.put("factor", BRANCH_FACTOR);
		assertRejected("add_branch_reused", shape(ROWS, SIZE), args);
	}

	/** A branch whose output does not have the input's extent is rejected rather than reshaped. */
	@Test(timeout = 60000)
	public void branchOfAnotherShapeIsRejected() {
		Map<String, Object> args = new HashMap<>();
		args.put("size", SIZE);
		assertRejected("add_branch_wrong_shape", shape(ROWS, SIZE), args);
	}

	/**
	 * A tensor with the input's element count but another extent is rejected rather than read in
	 * memory order.
	 */
	@Test(timeout = 60000)
	public void tensorOfAnotherShapeIsRejected() {
		assertRejected("add_tensor", shape(ROWS, SIZE),
				arguments("other", new PackedCollection(shape(SIZE, ROWS))));
	}

	/** {@code add} requires exactly one operand. */
	@Test(timeout = 60000)
	public void addWithoutAnOperandIsRejected() {
		assertRejected("add_without_operand", shape(ROWS, SIZE), new HashMap<>());
	}

	/**
	 * Builds one fixture layer, compiles it without a backward pass and forwards {@code input}.
	 *
	 * @param layer      the fixture layer
	 * @param inputShape the layer's input shape
	 * @param args       the layer's arguments
	 * @param input      the input to forward
	 * @return the output, flattened
	 */
	private double[] forward(String layer, TraversalPolicy inputShape, Map<String, Object> args,
							 PackedCollection input) {
		PdslLoader loader = new PdslLoader();
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(FIXTURE), layer, inputShape, args));
		try (CompiledModel compiled = model.compile(false)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/**
	 * Asserts that building one fixture layer is rejected with a {@link PdslParseException}.
	 *
	 * @param layer      the fixture layer
	 * @param inputShape the layer's input shape
	 * @param args       the layer's arguments
	 */
	private void assertRejected(String layer, TraversalPolicy inputShape, Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		try {
			loader.buildLayer(loader.parseResource(FIXTURE), layer, inputShape, args);
			Assert.fail(layer + " should be rejected");
		} catch (PdslParseException expected) {
			log(layer + " rejected: " + expected.getMessage());
		}
	}

	/**
	 * The arguments of {@code add_branch}: the factors of its main path and of its branch.
	 *
	 * @return the argument map
	 */
	private static Map<String, Object> factors() {
		Map<String, Object> args = new HashMap<>();
		args.put("main_factor", MAIN_FACTOR);
		args.put("branch_factor", BRANCH_FACTOR);
		return args;
	}

	/**
	 * A single-entry argument map.
	 *
	 * @param name  the parameter name
	 * @param value the bound tensor
	 * @return the argument map
	 */
	private static Map<String, Object> arguments(String name, PackedCollection value) {
		Map<String, Object> args = new HashMap<>();
		args.put(name, value);
		return args;
	}
}
