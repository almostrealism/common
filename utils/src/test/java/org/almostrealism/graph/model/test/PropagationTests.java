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
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class PropagationTests implements TestFeatures {

	@Test
	public void denseBackwards() {
		if (testProfileIs(TestUtils.PIPELINE)) return;
		if (skipLongTests) return;
		if (skipKnownIssues) return;

		int size = 12;
		int nodes = 5;

		Model model = new Model(shape(size), 1e-1);
		CellularLayer dense = dense(size, nodes);
		CellularLayer softmax = softmax(nodes);
		model.addLayer(dense);
		model.addLayer(softmax);

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
		model.addLayer(pool);

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
		CellularLayer conv = convolution2d(inputShape, 8, convSize);

		model.addLayer(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		CompiledModel runner = model.compile();
		runner.forward(input);

		TraversalPolicy filterShape = conv.getWeights().get(0).getShape();
		PackedCollection<?> originalFilter = new PackedCollection<>(filterShape);
		originalFilter.setMem(0, conv.getWeights().get(0), 0, conv.getWeights().get(0).getMemLength());

		TraversalPolicy gradientShape = model.getShape();
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
}
