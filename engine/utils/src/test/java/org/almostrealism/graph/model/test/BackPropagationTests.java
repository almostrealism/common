/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for backpropagation and gradient computation in neural network models.
 */
public class BackPropagationTests extends TestSuiteBase {

	/**
	 * Tests the dense layer backward pass against an analytically derived gradient.
	 *
	 * <p>The layer is built from the no-initialisation {@code dense(weights, biases)}
	 * factory with deterministic parameters, so the values survive {@code compile()},
	 * which would otherwise randomise the weights and zero the biases during setup.
	 * Weights are stored as {@code [nodes, size]} with
	 * {@code weights[i, j] = (i + 1) * (j + 1) * 0.01}; the product form keeps the
	 * Jacobian's dependence on the input index from cancelling in the backward pass.</p>
	 *
	 * <p>The expected forward output is {@code softmax(weights @ input + biases)}, and for
	 * an upstream gradient that is a unit spike at the target output the expected input
	 * gradient is {@code dx_j = sum_k weights[k, j] * s_target * (delta(target, k) - s_k)}.
	 * Both are computed here from those definitions rather than copied from the layer.</p>
	 */
	@Test(timeout = 120000)
	public void denseBackwards() {

		int size = 12;
		int nodes = 5;

		PackedCollection weights = new PackedCollection(shape(nodes, size));
		int wn = nodes * size;
		floor(integers(0, wn).divide(size)).add(c(1.0))
				.multiply(integers(0, wn).mod(size).add(c(1.0)))
				.multiply(0.01)
				.into(weights.traverseEach()).evaluate();

		PackedCollection biases = new PackedCollection(shape(nodes));
		integers(0, nodes).multiply(0.01).add(0.1).into(biases.traverseEach()).evaluate();

		Model model = new Model(shape(size), 1e-1);
		CellularLayer dense = dense(weights, biases).apply(shape(size));
		CellularLayer softmax = softmax(nodes);
		model.add(dense);
		model.add(softmax);

		PackedCollection input = new PackedCollection(size);
		integers(0, size).into(input.traverseEach()).evaluate();

		CompiledModel runner = model.compile(true, true);

		double[] x = new double[size];
		for (int j = 0; j < size; j++) x[j] = input.valueAt(j);

		double[][] w = new double[nodes][size];
		double[] b = new double[nodes];
		for (int i = 0; i < nodes; i++) {
			b[i] = biases.valueAt(i);
			for (int j = 0; j < size; j++) w[i][j] = weights.valueAt(i, j);
		}

		double[] softmaxOut = new double[nodes];
		double expSum = 0;
		for (int i = 0; i < nodes; i++) {
			double z = b[i];
			for (int j = 0; j < size; j++) z += w[i][j] * x[j];
			softmaxOut[i] = Math.exp(z);
			expSum += softmaxOut[i];
		}
		for (int i = 0; i < nodes; i++) softmaxOut[i] /= expSum;

		verboseLog(() -> {
			PackedCollection output = runner.forward(input);
			log("Output: " + Arrays.toString(output.toArray(0, output.getMemLength())));

			for (int i = 0; i < output.getMemLength(); i++) {
				assertEquals(softmaxOut[i], output.valueAt(i));
			}
		});

		int target = 3;
		PackedCollection gradient = new PackedCollection(shape(nodes));
		gradient.setMem(target, 1.0);
		PackedCollection result = runner.backward(gradient);

		log("Weights: " + Arrays.toString(weights.toArray(0, weights.getMemLength())));
		log("Biases: " + Arrays.toString(biases.toArray(0, biases.getMemLength())));
		log("Output Gradient: " + Arrays.toString(result.toArray(0, result.getMemLength())));

		double[] dz = new double[nodes];
		for (int k = 0; k < nodes; k++) {
			dz[k] = softmaxOut[target] * ((k == target ? 1.0 : 0.0) - softmaxOut[k]);
		}

		for (int j = 0; j < size; j++) {
			double expected = 0;
			for (int k = 0; k < nodes; k++) expected += w[k][j] * dz[k];
			Assert.assertEquals(expected, result.valueAt(j), 1e-6);
		}
	}

	/**
	 * Tests 2D pooling layer backward pass.
	 */
	@Test(timeout = 120000)
	public void pool2dBackwards() {
		if (skipLongTests || skipKnownIssues) return;

//		int w = 16;
//		int h = 12;
		int w = 4;
		int h = 4;
		int size = 2;

		TraversalPolicy inputShape = shape(h, w, 1);
		TraversalPolicy outputShape = shape(h / size, w / size, 1);

		Model model = new Model(inputShape, 1e-1);
		CellularLayer pool = pool2d(inputShape, size);
		model.add(pool);

		PackedCollection input = new PackedCollection(inputShape);
		floor(rand(input.getShape()).multiply(100.0)).into(input.traverseEach()).evaluate();

		PackedCollection output = model.compile().forward(input);

		PackedCollection result = new PackedCollection(inputShape);

		model.backward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection> gr = grad.get();

			return () -> {
				PackedCollection out = gr.evaluate();
				log("Gradient shape vs input shape: " + out.getShape() + " / " + inputShape);

				log(Arrays.toString(out.toArray(0, out.getMemLength())));

				result.setFrom(0, out, 0, out.getMemLength());
			};
		});

		PackedCollection gradient = new PackedCollection(outputShape);
		gradient.randFill();
		model.compile().backward(gradient);

		for (int i = 0; i < h; i++) {
			for (int j = 0; j < w; j++) {
				log("Input = " + input.valueAt(i, j, 0) + ", Output = " + output.valueAt(i / size, j / size, 0));
				if (input.valueAt(i, j, 0) == output.valueAt(i / size, j / size, 0)) {
					log("Expected = " + gradient.valueAt(i / size, j / size, 0) + ", Actual = " + result.valueAt(i, j, 0));
					Assert.assertEquals(gradient.valueAt(i / size, j / size, 0), result.valueAt(i, j, 0), 1e-6);
				} else {
					Assert.assertEquals(0, result.valueAt(i, j, 0), 1e-6);
				}
			}
		}
	}

	// @Test(timeout = 120000)
	/**
	 * Tests convolution backward pass.
	 */
	private void convBackwards() {
		int convSize = 3;
		int w = 10;
		int h = 10;
		TraversalPolicy inputShape = shape(h, w);

		Model model = new Model(inputShape, 1e-2);
		CellularLayer conv = (CellularLayer) convolution2d(inputShape, 8, convSize, false);

		model.add(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection input = t.pack();

		CompiledModel runner = model.compile();
		runner.forward(input);

		TraversalPolicy filterShape = conv.getWeights().get(0).getShape();
		PackedCollection originalFilter = new PackedCollection(filterShape);
		originalFilter.setFrom(0, conv.getWeights().get(0), 0, conv.getWeights().get(0).getMemLength());

		TraversalPolicy gradientShape = model.getOutputShape();
		PackedCollection gradient = new PackedCollection(gradientShape);
		gradient.randFill();
		runner.backward(gradient);

		PackedCollection adjustedFilter = conv.getWeights().get(0);

		for (int f = 0; f < filterShape.length(0); f++) {
			for (int xf = 0; xf < filterShape.length(1); xf++) {
				for (int yf = 0; yf < filterShape.length(2); yf++) {
					double expected = 0;

					for (int x = 0; x < gradientShape.length(0); x++) {
						for (int y = 0; y < gradientShape.length(1); y++) {
							double g = gradient.toDouble(gradientShape.index(x, y, f));
							double v = input.toDouble(inputShape.index(x + xf, y + yf));
							expected += g * v;
						}
					}

					expected *= 1e-2;

					double actual = originalFilter.toDouble(filterShape.index(f, xf, yf)) - adjustedFilter.toDouble(filterShape.index(f, xf, yf));
					log("PropagationTest: " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 1e-6);
				}
			}
		}
	}

	/**
	 * Tests composition backward pass.
	 */
	@Test(timeout = 120000)
	public void compositionBackwards() {
		SequentialBlock block = new SequentialBlock(shape(3));

		SequentialBlock alt = block.branch();
		alt.add(layer("scale x2", in -> multiply(in, c(2))));

		block.add(layer("scale x3", in -> multiply(in, c(3))));
		block.add(compose("multiply", shape(3), alt, this::multiply));

		PackedCollection input = pack(2, 3, 4);
		PackedCollection gradient = pack(5, 4, 1);

		CompiledModel model = new Model(shape(3), 1e-1)
				.add(block)
				.compile(true, true);
		model.forward(input);
		gradient = model.backward(gradient);
		gradient.print();
		assertEquals(120.0, gradient.toDouble(0));
		assertEquals(144.0, gradient.toDouble(1));
		assertEquals(48.0, gradient.toDouble(2));
	}

	/**
	 * Tests split backward pass with repeat operation.
	 */
	@Test(timeout = 120000)
	public void splitBackwardsRepeat() {
		SequentialBlock block = new SequentialBlock(shape(3, 2));

		List<Block> branches = block.split(shape(1, 2));
		branches.get(0).andThen(layer("scale x2", in -> multiply(in, c(2))));
		Block b = branches.get(1).andThen(layer("scale x3", in -> multiply(in, c(3))));
		branches.get(2).andThen(layer("scale x4", in -> multiply(in, c(4))));

		block.add(compose("replace", b, (x, y) ->
				repeat(3, y).reshape(3, 2)));

		PackedCollection input = pack(2, 3, 4, 5, 6, 7)
				.reshape(3, 2);
		PackedCollection gradient = pack(5, 4, 1.5, 3, 2, -4)
				.reshape(3, 2);

		CompiledModel model = new Model(shape(3, 2))
				.add(block)
				.compile(true, true);
		model.forward(input).print();

		PackedCollection result = model.backward(gradient);
		result.print();

		for (int i = 0; i < result.getMemLength(); i++) {
			if (i == 2) {
				double total = gradient.toDouble(0) +
						gradient.toDouble(2) +
						gradient.toDouble(4);
				assertEquals(3 * total, result.toDouble(i));
			} else if (i == 3) {
				double total = gradient.toDouble(1) +
						gradient.toDouble(3) +
						gradient.toDouble(5);
				assertEquals(3 * total, result.toDouble(i));
			} else {
				assertEquals(0.0, result.toDouble(i));
			}
		}
	}

	/**
	 * Tests split backward pass with add operation.
	 */
	@Test(timeout = 120000)
	public void splitBackwardsAdd() {
		SequentialBlock block = new SequentialBlock(shape(3, 2));

		List<Block> branches = block.split(shape(1, 2));
		branches.get(0).andThen(layer("scale x2", in -> multiply(in, c(2))));
		Block b = branches.get(1).andThen(layer("scale x3", in -> multiply(in, c(3))));
		branches.get(2).andThen(layer("scale x4", in -> multiply(in, c(4))));

		block.add(compose("add", b, (x, y) -> add(x, y)));

		PackedCollection input = pack(2, 3, 4, 5, 6, 7)
				.reshape(3, 2);
		PackedCollection gradient = pack(5, 4, 1.5, 3, 2, -4)
				.reshape(3, 2);

		CompiledModel model = new Model(shape(3, 2))
				.add(block)
				.compile(true, true);
		model.forward(input).print();

		log("Running backward pass on gradient: ");
		gradient.print();

		PackedCollection result = model.backward(gradient);
		log("Result of backward pass: ");
		result.print();

		for (int i = 0; i < result.getMemLength(); i++) {
			double direct = gradient.toDouble(i);

			if (i == 2) {
				double total = gradient.toDouble(0) +
						gradient.toDouble(2) +
						gradient.toDouble(4);
				assertEquals(direct + 3 * total, result.toDouble(i));
			} else if (i == 3) {
				double total = gradient.toDouble(1) +
						gradient.toDouble(3) +
						gradient.toDouble(5);
				assertEquals(direct + 3 * total, result.toDouble(i));
			} else {
				assertEquals(direct, result.toDouble(i));
			}
		}
	}

	/**
	 * Tests split backward pass with child index.
	 */
	@Test(timeout = 120000)
	public void splitBackwardsChildIndex() {
		SequentialBlock block = new SequentialBlock(shape(3, 2));

		List<Block> branches = block.split(shape(1, 2), 0);
		branches.get(0).andThen(layer("scale x2", in -> multiply(in, c(2))));
		Block b = branches.get(1).andThen(layer("scale x3", in -> multiply(in, c(3))));
		branches.get(2).andThen(layer("scale x4", in -> multiply(in, c(4))));

		block.add(compose("add", b, (x, y) -> add(x, y)));

		PackedCollection input = pack(2, 3, 4, 5, 6, 7)
				.reshape(3, 2);
		PackedCollection gradient = pack(5, -4)
				.reshape(1, 2);

		CompiledModel model = new Model(shape(3, 2))
				.add(block)
				.compile(true, true);
		PackedCollection out = model.forward(input);
		out.print();

		for (int i = 0; i < 2; i++) {
			double total = 0.0;

			for (int j = 0; j < 6; j++) {
				double factor = j % 2 == i ? 1.0 : 0.0;

				if (j == 0 || j == 1) {
					factor *= 2.0;
				} else if (j == 2 || j == 3) {
					factor *= 3.0;
				} else {
					factor = 0.0;
				}

				total += factor * input.toDouble(j);
			}

			assertEquals(total, out.toDouble(i));
		}

		PackedCollection result = model.backward(gradient);
		result.print();

		for (int i = 0; i < result.getMemLength(); i++) {
			double factor = 0.0;

			if (i == 0 || i == 1) {
				factor = 2.0;
			} else if (i == 2 || i == 3) {
				factor = 3.0;
			}

			assertEquals(factor * gradient.toDouble(i % 2), result.toDouble(i));
		}
	}
}