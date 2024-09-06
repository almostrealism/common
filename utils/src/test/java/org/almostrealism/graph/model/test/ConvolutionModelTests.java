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

	@Test
	public void convSingleChannelSmall() {
		convSingleChannel(10, 10, 3, 8);
	}

	@Test
	public void convSingleChannelMedium() {
//		convSingleChannel(54, 54, 3, 6);
		convSingleChannel(52, 52, 3, 6);
	}

	public void convSingleChannel(int h, int w, int convSize, int filterCount) {
		TraversalPolicy inputShape = shape(h, w);
		Model model = new Model(inputShape);
		CellularLayer conv = convolution2d(inputShape, filterCount, convSize, false);

		model.add(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.compile().forward(input);

		PackedCollection<?> filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();
		Assert.assertEquals(filterCount, filterShape.length(0));
		Assert.assertEquals(1, filterShape.length(1));
		Assert.assertEquals(convSize, filterShape.length(2));
		Assert.assertEquals(convSize, filterShape.length(3));

		PackedCollection<?> output = ((DefaultCellularLayer) conv).getOutput();
		validateConv(input.reshape(1, 1, h, w), filter, output, convSize);
	}

	@Test
	public void convMultiChannelSmall() {
		convMultiChannel(2, 4, 10, 10, 3, 6);
	}

	@Test
	public void convMultiChannelMedium() {
		convMultiChannel(2, 4, 54, 54, 3, 6);
	}

	public void convMultiChannel(int n, int c, int h, int w, int convSize, int filterCount) {
		TraversalPolicy inputShape = shape(n, c, h, w);
		Model model = new Model(inputShape);

		CellularLayer conv = convolution2d(inputShape, filterCount, convSize, false);
		model.add(conv);

		PackedCollection<?> input = new PackedCollection<>(inputShape).randFill();

		model.compile().forward(input);

		PackedCollection<?> filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();
		Assert.assertEquals(filterCount, filterShape.length(0));
		Assert.assertEquals(c, filterShape.length(1));
		Assert.assertEquals(convSize, filterShape.length(2));
		Assert.assertEquals(convSize, filterShape.length(3));

		PackedCollection<?> output = ((DefaultCellularLayer) conv).getOutput();
		validateConv(input, filter, output, convSize);
	}

	protected void validateConv(PackedCollection<?> input, PackedCollection<?> filter, PackedCollection<?> output, int convSize) {
		int batches = input.getShape().length(0);
		int channels = input.getShape().length(1);
		TraversalPolicy outputShape = output.getShape();

		for (int np = 0; np < batches; np++) {
			for (int f = 0; f < outputShape.length(1); f++) {
				for (int row = 0; row < outputShape.length(2); row++) {
					for (int col = 0; col < outputShape.length(3); col++) {
						double expected = 0;

						for (int cp = 0; cp < channels; cp++) {
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
