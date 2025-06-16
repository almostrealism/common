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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class BackPropagationTests implements TestFeatures {

	@Test
	public void denseBackwards() {
		if (testProfileIs(TestUtils.PIPELINE)) return;
		if (skipKnownIssues) return;

		int size = 12;
		int nodes = 5;

		Model model = new Model(shape(size), 1e-1);
		CellularLayer dense = dense(size, nodes).apply(shape(size));
		CellularLayer softmax = softmax(nodes);
		model.add(dense);
		model.add(softmax);

		PackedCollection<?> weights = dense.getWeights().get(0);

		for (int i = 0; i < size; i++) {
			for (int j = 0; j < nodes; j++) {
//				weights.setMem(weights.getShape().index(i, j), 1 + (i + j) * 0.1);
				weights.setMem(weights.getShape().index(i, j), (i + j) * 0.01);
			}
		}

		PackedCollection<?> biases = dense.getWeights().get(1);
		for (int i = 0; i < nodes; i++) {
			biases.setMem(i, 0.1 + i * 0.01);
		}

		PackedCollection<?> input = new PackedCollection<>(size);
		IntStream.range(0, size).forEach(i -> input.setMem(i, (double) i));

		CompiledModel runner = model.compile();

		verboseLog(() -> {
			PackedCollection<?> output = runner.forward(input);
			System.out.println("Output: " + Arrays.toString(output.toArray(0, output.getMemLength())));

			// double expected[] = new double[]{2.29283592e-12, 1.86271326e-09, 1.51327910e-06, 1.22939676e-03, 9.98769088e-01};
			double expected[] = new double[]{0.034696079790592194, 0.06780441105365753, 0.13250578939914703, 0.2589479088783264, 0.5060457587242126};
			for (int i = 0; i < output.getMemLength(); i++) {
				assertEquals(expected[i], output.valueAt(i));
			}
		});

		double result[] = new double[size];

		dense.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				System.out.println(Arrays.toString(out.toArray(0, out.getMemLength())));

				out.getMem(0, result, 0, result.length);
			};
		});

		PackedCollection<?> gradient = new PackedCollection<>(shape(nodes));
		gradient.setMem(3, 1.0);
		runner.backward(gradient);

		System.out.println("Weights: " + Arrays.toString(weights.toArray(0, weights.getMemLength())));
		System.out.println("Biases: " + Arrays.toString(biases.toArray(0, biases.getMemLength())));
		System.out.println("Output Gradient: " + Arrays.toString(result));

//		double expected[] = new double[] { -0.00012475, -0.0001249,  -0.00012506, -0.00012521, -0.00012536, -0.00012551,
//				-0.00012566, -0.00012581, -0.00012596, -0.00012611, -0.00012626, -0.00012642 };

		double expected[] = new double[] { -0.0023582035209983587, -0.003028743900358677, -0.0036992833483964205,
				-0.004369824193418026, -0.005040363874286413, -0.005710904952138662, -0.006381443701684475,
				-0.007051984313875437, -0.007722523529082537, -0.008393064141273499, -0.009063605219125748, -0.009734145365655422 };
		for (int i = 0; i < result.length; i++) {
			Assert.assertEquals(expected[i], result[i], 1e-6);
		}
	}

	@Test
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

		PackedCollection<?> input = new PackedCollection<>(inputShape);
		input.fill(pos -> (double) (int) (100 * Math.random()));

		PackedCollection<?> output = model.compile().forward(input);

		PackedCollection<?> result = new PackedCollection(inputShape);

		model.backward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				System.out.println("Gradient shape vs input shape: " + out.getShape() + " / " + inputShape);

				System.out.println(Arrays.toString(out.toArray(0, out.getMemLength())));

				result.setMem(0, out, 0, out.getMemLength());
			};
		});

		PackedCollection<?> gradient = new PackedCollection<>(outputShape);
		gradient.fill(pos -> Math.random());
		model.compile().backward(gradient);

		for (int i = 0; i < h; i++) {
			for (int j = 0; j < w; j++) {
				System.out.println("Input = " + input.valueAt(i, j, 0) + ", Output = " + output.valueAt(i / size, j / size, 0));
				if (input.valueAt(i, j, 0) == output.valueAt(i / size, j / size, 0)) {
					System.out.println("Expected = " + gradient.valueAt(i / size, j / size, 0) + ", Actual = " + result.valueAt(i, j, 0));
					Assert.assertEquals(gradient.valueAt(i / size, j / size, 0), result.valueAt(i, j, 0), 1e-6);
				} else {
					Assert.assertEquals(0, result.valueAt(i, j, 0), 1e-6);
				}
			}
		}
	}

	// @Test
	public void convBackwards() {
		int convSize = 3;
		int w = 10;
		int h = 10;
		TraversalPolicy inputShape = shape(h, w);

		Model model = new Model(inputShape, 1e-2);
		CellularLayer conv = (CellularLayer) convolution2d(inputShape, 8, convSize, false);

		model.add(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		CompiledModel runner = model.compile();
		runner.forward(input);

		TraversalPolicy filterShape = conv.getWeights().get(0).getShape();
		PackedCollection<?> originalFilter = new PackedCollection<>(filterShape);
		originalFilter.setMem(0, conv.getWeights().get(0), 0, conv.getWeights().get(0).getMemLength());

		TraversalPolicy gradientShape = model.getOutputShape();
		PackedCollection<?> gradient = new PackedCollection<>(gradientShape);
		gradient.fill(pos -> Math.random());
		runner.backward(gradient);

		PackedCollection<?> adjustedFilter = conv.getWeights().get(0);

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
					System.out.println("PropagationTest: " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 1e-6);
				}
			}
		}
	}

	@Test
	public void compositionBackwards() {
		SequentialBlock block = new SequentialBlock(shape(3));

		SequentialBlock alt = block.branch();
		alt.add(layer("scale x2", in -> multiply(in, c(2))));

		block.add(layer("scale x3", in -> multiply(in, c(3))));
		block.add(compose("multiply", shape(3), alt, this::multiply));

		PackedCollection<?> input = pack(2, 3, 4);
		PackedCollection<?> gradient = pack(5, 4, 1);

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

	@Test
	public void splitBackwardsRepeat() {
		SequentialBlock block = new SequentialBlock(shape(3, 2));

		List<Block> branches =  block.split(shape(1, 2));
		Block a = branches.get(0).andThen(layer("scale x2", in -> multiply(in, c(2))));
		Block b = branches.get(1).andThen(layer("scale x3", in -> multiply(in, c(3))));
		Block c = branches.get(2).andThen(layer("scale x4", in -> multiply(in, c(4))));

		block.add(compose("replace", b, (x, y) ->
				repeat(3, y).reshape(3, 2)));

		PackedCollection<?> input = pack(2, 3, 4, 5, 6, 7)
										.reshape(3, 2);
		PackedCollection<?> gradient = pack(5, 4, 1.5, 3, 2, -4)
										.reshape(3, 2);

		CompiledModel model = new Model(shape(3, 2))
				.add(block)
				.compile(true, true);
		model.forward(input).print();

		PackedCollection<?> result = model.backward(gradient);
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

	@Test
	public void splitBackwardsAdd() {
		SequentialBlock block = new SequentialBlock(shape(3, 2));

		List<Block> branches =  block.split(shape(1, 2));
		Block a = branches.get(0).andThen(layer("scale x2", in -> multiply(in, c(2))));
		Block b = branches.get(1).andThen(layer("scale x3", in -> multiply(in, c(3))));
		Block c = branches.get(2).andThen(layer("scale x4", in -> multiply(in, c(4))));

		block.add(compose("add", b, (x, y) -> add(x, y)));

		PackedCollection<?> input = pack(2, 3, 4, 5, 6, 7)
				.reshape(3, 2);
		PackedCollection<?> gradient = pack(5, 4, 1.5, 3, 2, -4)
				.reshape(3, 2);

		CompiledModel model = new Model(shape(3, 2))
				.add(block)
				.compile(true, true);
		model.forward(input).print();

		log("Running backward pass on gradient: ");
		gradient.print();

		PackedCollection<?> result = model.backward(gradient);
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

	@Test
	public void splitBackwardsChildIndex() {
		SequentialBlock block = new SequentialBlock(shape(3, 2));

		List<Block> branches =  block.split(shape(1, 2), 0);
		Block a = branches.get(0).andThen(layer("scale x2", in -> multiply(in, c(2))));
		Block b = branches.get(1).andThen(layer("scale x3", in -> multiply(in, c(3))));
		Block c = branches.get(2).andThen(layer("scale x4", in -> multiply(in, c(4))));

		block.add(compose("add", b, (x, y) -> add(x, y)));

		PackedCollection<?> input = pack(2, 3, 4, 5, 6, 7)
				.reshape(3, 2);
		PackedCollection<?> gradient = pack(5, -4)
				.reshape(1, 2);

		CompiledModel model = new Model(shape(3, 2))
				.add(block)
				.compile(true, true);
		PackedCollection<?> out = model.forward(input);
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

		PackedCollection<?> result = model.backward(gradient);
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
