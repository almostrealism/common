/*
 * Copyright 2024 Michael Murray
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
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.model.Model;
import org.almostrealism.model.ModelFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class ConvolutionModelTests implements ModelFeatures, TestFeatures, KernelAssertions {
	private int convSize = 3;
	private int n = 2;
	private int c = 4;
	private int h = 10;
	private int w = 10;

	@Test
	public void conv() {
		TraversalPolicy inputShape = shape(h, w);
		Model model = new Model(inputShape);
		CellularLayer conv = convolution2d(inputShape, 8, convSize);

		model.addLayer(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.compile().forward(input);

		PackedCollection<?> filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();

		PackedCollection<?> output = ((DefaultCellularLayer) conv).getOutput();
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
					log("[" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					assertEquals(expected, actual);
				}
			}
		}
	}

	@Test
	public void convMultiChannel() {
		int filterCount = 6;
		TraversalPolicy inputShape = shape(n, c, h, w);
		Model model = new Model(inputShape);
		CellularLayer conv = convolution2dMultiChannel(inputShape, filterCount, convSize);

		model.addLayer(conv);

//		Tensor<Double> t = tensor(inputShape);
//		PackedCollection<?> input = t.pack();
		PackedCollection<?> input = new PackedCollection<>(inputShape).randFill();

		model.compile().forward(input);

		PackedCollection<?> filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();
		Assert.assertEquals(filterCount, filterShape.length(0));
		Assert.assertEquals(c, filterShape.length(1));
		Assert.assertEquals(convSize, filterShape.length(2));
		Assert.assertEquals(convSize, filterShape.length(3));

		PackedCollection<?> output = ((DefaultCellularLayer) conv).getOutput();
		TraversalPolicy outputShape = output.getShape();

		for (int np = 0; np < n; np++) {
			for (int f = 0; f < outputShape.length(1); f++) {
				for (int row = 0; row < outputShape.length(2); row++) {
					for (int col = 0; col < outputShape.length(3); col++) {
						double expected = 0;

						for (int cp = 0; cp < c; cp++) {
							for (int x = 0; x < convSize; x++) {
								for (int y = 0; y < convSize; y++) {
									expected += filter.valueAt(f, cp, x, y) * input.valueAt(np, cp, row + x, col + y);
								}
							}
						}

						double actual = output.valueAt(np, f, row, col);
						log("[" + f + ", " + row + ", " + col + "] " + expected + " vs " + actual);
						assertEquals(expected, actual);
					}
				}
			}
		}
	}
}
