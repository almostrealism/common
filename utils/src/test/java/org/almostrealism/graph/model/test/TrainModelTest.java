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

import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.model.KernelBlock;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class TrainModelTest implements TestFeatures {
	private int convSize = 3;
	private int poolSize = 2;
	private int w = 10;
	private int h = 10;
	private TraversalPolicy inputShape = shape(h, w);

	protected Model model() {
		Model model = new Model(inputShape);
		model.addBlock(convolution2d(convSize, 8));
		model.addBlock(pool2d(poolSize));
		return model;
	}


	@Test
	public void conv() {
		Model model = model();

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.setup().get().run();
		model.forward(input);

		PackedCollection<?> filter = model.getBlocks().get(0).getWeights();
		TraversalPolicy filterShape = filter.getShape();

		PackedCollection<?> output = model.getInputs().get(1);
		TraversalPolicy outputShape = output.getShape();

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					double expected = 0;

					for (int x = 0; x < convSize; x++) {
						for (int y = 0; y < convSize; y++) {
							expected += filter.toDouble(filterShape.index(r, x, y)) * input.toDouble(inputShape.index(p + x, q + y));
						}
					}

					double actual = output.toDouble(outputShape.index(p, q, r));
					System.out.println("TrainModelTest: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}

		input = model.getInputs().get(1);
		inputShape = input.getShape();

		output = model.getInputs().get(2);
		outputShape = output.getShape();

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					int x0 = p * poolSize;
					int y0 = q * poolSize;

					double expected = input.toDouble(inputShape.index(x0, y0, r));

					for (int x = 0; x < poolSize; x++) {
						for (int y = 0; y < poolSize; y++) {
							expected = Math.max(expected, input.toDouble(inputShape.index(x0 + x, y0 + y, r)));
						}
					}

					double actual = output.toDouble(outputShape.index(p, q, r));
					System.out.println("TrainModelTest: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void train() {
		Model model = model();

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.setup().get().run();
		model.forward(input);
	}
}
