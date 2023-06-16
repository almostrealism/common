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
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.KernelLayer;
import org.almostrealism.layers.PropagationCell;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class PropagationTests implements TestFeatures {
	@Test
	public void softmaxBackwards() {
		PackedCollection<?> input = new PackedCollection(10);
		IntStream.range(0, 10).forEach(i -> input.setMem(i, i + 1.0));

		PackedCollection<?> gradient = new PackedCollection<>(10);
		gradient.setMem(3, 1.0);

		System.out.println("Input: " + Arrays.toString(input.toArray(0, input.getMemLength())));
		System.out.println("Gradient: " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double result[] = new double[10];

		CellularLayer layer = softmax(10);
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				System.out.println(Arrays.toString(out.toArray(0, out.getMemLength())));

				out.getMem(0, result, 0, result.length);
			};
		});
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().push(p(gradient)).get().run();

		double expected[] = new double[] { -1.22242448e-07, -3.32289424e-07, -9.03256303e-07,  1.56203074e-03,
				-6.67421149e-06, -1.81423878e-05, -4.93161231e-05, -1.34055121e-04,
				-3.64399601e-04, -9.90540812e-04 };

		for (int i = 0; i < result.length; i++) {
			Assert.assertEquals(expected[i], result[i], 1e-6);
		}
	}

	@Test
	public void denseBackwards() {
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
				weights.setMem(weights.getShape().index(i, j), 1 + (i + j) * 0.1);
			}
		}

		PackedCollection<?> biases = dense.getWeights().get(1);
		for (int i = 0; i < nodes; i++) {
			biases.setMem(i, 1 + i * 0.1);
		}

		PackedCollection<?> input = new PackedCollection<>(size);
		IntStream.range(0, size).forEach(i -> input.setMem(i, i));

		PackedCollection<?> output = model.forward(input);
		System.out.println("Output: " + Arrays.toString(output.toArray(0, output.getMemLength())));

		double expected[] = new double[] { 2.29283592e-12, 1.86271326e-09, 1.51327910e-06, 1.22939676e-03, 9.98769088e-01 };
		for (int i = 0; i < output.getMemLength(); i++) {
			Assert.assertEquals(expected[i], output.valueAt(i), 1e-6);
		}

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
		model.backward(gradient);

		System.out.println("Weights: " + Arrays.toString(weights.toArray(0, weights.getMemLength())));
		System.out.println("Biases: " + Arrays.toString(biases.toArray(0, biases.getMemLength())));
		System.out.println("Output Gradient: " + Arrays.toString(result));

		expected = new double[] { -0.00012475, -0.0001249,  -0.00012506, -0.00012521, -0.00012536, -0.00012551,
				-0.00012566, -0.00012581, -0.00012596, -0.00012611, -0.00012626, -0.00012642 };

		for (int i = 0; i < result.length; i++) {
			Assert.assertEquals(expected[i], result[i], 1e-6);
		}
	}

	@Test
	public void pool2dBackwards() {
		int w = 16;
		int h = 12;
		int size = 2;

		TraversalPolicy inputShape = shape(h, w, 1);
		TraversalPolicy outputShape = shape(h / size, w / size, 1);

		Model model = new Model(inputShape, 1e-1);
		CellularLayer pool = pool2d(inputShape, size);
		model.addLayer(pool);

		PackedCollection<?> input = new PackedCollection<>(inputShape);
		input.fill(pos -> (double) (int) (100 * Math.random()));

		PackedCollection<?> output = model.forward(input);

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
		model.backward(gradient);

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
		CellularLayer conv = convolution2d(inputShape, convSize, 8);

		model.addLayer(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.setup().get().run();
		model.forward(input);

		TraversalPolicy filterShape = conv.getWeights().get(0).getShape();
		PackedCollection<?> originalFilter = new PackedCollection<>(filterShape);
		originalFilter.setMem(0, conv.getWeights().get(0), 0, conv.getWeights().get(0).getMemLength());

		TraversalPolicy gradientShape = model.getShape();
		PackedCollection<?> gradient = new PackedCollection<>(gradientShape);
		gradient.fill(pos -> Math.random());
		model.backward(gradient);

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
