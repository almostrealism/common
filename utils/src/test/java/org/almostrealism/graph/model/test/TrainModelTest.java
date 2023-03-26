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
	@Test
	public void conv() {
		int size = 3;
		int w = 10;
		int h = 10;

		TraversalPolicy inputShape = shape(h, w);
		Model model = new Model(inputShape);
		KernelBlock conv2d = model.addBlock(convolution2d(w, h, 8, size));

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.setup().get().run();
		model.forward(input);

		PackedCollection<?> filter = conv2d.getWeights();
		TraversalPolicy filterShape = filter.getShape();
		TraversalPolicy outputShape = model.getShape();

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					double expected = 0;

					for (int x = 0; x < size; x++) {
						for (int y = 0; y < size; y++) {
							expected += filter.toDouble(filterShape.index(r, x, y)) * input.toDouble(inputShape.index(p + x, q + y));
						}
					}

					double actual = model.getInputs().get(1).toDouble(outputShape.index(p, q, r));
					System.out.println("PackedCollectionSubsetTests: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void train() {
		int size = 3;
		int w = 10;
		int h = 10;

		TraversalPolicy inputShape = shape(h, w);
		Model model = new Model(inputShape);
		model.addBlock(convolution2d(w, h, 4, size));

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.setup().get().run();
		model.forward(input);
	}
}
