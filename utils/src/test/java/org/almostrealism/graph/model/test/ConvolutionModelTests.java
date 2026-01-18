/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.graph.Cell;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.layers.Learning;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.ModelFeatures;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.function.Supplier;

public class ConvolutionModelTests implements ModelFeatures, TestFeatures, KernelAssertions {

	@Test(timeout = 30000)
	public void convSingleChannelSmall() {
		convSingleChannel(10, 10, 3, 8);
	}

	@Test(timeout = 30000)
	public void convSingleChannelMedium() {
//		convSingleChannel(54, 54, 3, 6);
		convSingleChannel(52, 52, 3, 6);
	}

	public void convSingleChannel(int h, int w, int convSize, int filterCount) {
		TraversalPolicy inputShape = shape(h, w);
		Model model = new Model(inputShape);
		CellularLayer conv = (CellularLayer) convolution2d(inputShape, filterCount, convSize, false);

		model.add(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection input = t.pack();

		model.compile().forward(input);

		PackedCollection filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();
		Assert.assertEquals(filterCount, filterShape.length(0));
		Assert.assertEquals(1, filterShape.length(1));
		Assert.assertEquals(convSize, filterShape.length(2));
		Assert.assertEquals(convSize, filterShape.length(3));

		PackedCollection output = ((DefaultCellularLayer) conv).getOutput();
		validateConv(input.reshape(1, 1, h, w), filter, output, convSize);
	}

	@Test(timeout = 30000)
	public void convMultiChannelSmall() {
		convMultiChannel(2, 4, 10, 10, 3, 6);
	}

	@Test(timeout = 15 * 60000)
	public void convMultiChannelMedium() {
		if (testDepth < 1) return;

		convMultiChannel(2, 4, 54, 54, 3, 6);
	}

	@Test(timeout = 30000)
	public void convMultiChannelLarge() {
		convMultiChannel(1, 56, 28, 28, 3, 28);
	}

	public void convMultiChannel(int n, int c, int h, int w, int convSize, int filterCount) {
		TraversalPolicy inputShape = shape(n, c, h, w);
		Model model = new Model(inputShape);

		CellularLayer conv = (CellularLayer) convolution2d(inputShape, filterCount, convSize, false);
		model.add(conv);

		PackedCollection input = new PackedCollection(inputShape).randFill();

		model.compile().forward(input);

		PackedCollection filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();
		Assert.assertEquals(filterCount, filterShape.length(0));
		Assert.assertEquals(c, filterShape.length(1));
		Assert.assertEquals(convSize, filterShape.length(2));
		Assert.assertEquals(convSize, filterShape.length(3));

		PackedCollection output = ((DefaultCellularLayer) conv).getOutput();
		validateConv(input, filter, output, convSize);
	}

	@Test(timeout = 30000)
	public void convBackwardsSmallAtom() throws IOException {
		convBackwards("convBackwardsSmallAtom", 1, 3, 4, 4, 1, 3, 0, true);
	}

	@Test(timeout = 30000)
	public void convBackwardsMediumAtom() throws IOException {
		convBackwards("convBackwardsMediumAtom", 1, 28, 28, 28, 1, 28, 0, true);
	}

	@Test(timeout = 30000)
	public void convBackwardsMediumAtomPadded() throws IOException {
		if (skipKnownIssues) return;

		convBackwards("convBackwardsMediumAtomPadded", 1, 28, 28, 28, 1, 28,1, true);
	}

	@Test(timeout = 30000)
	public void convBackwardsSmall() throws IOException {
		convBackwards("convBackwardsSmall", 1, 3, 4, 4, 2, 3,0, true);
	}

	@Test(timeout = 30000)
	public void convBackwardsSmallPadded() throws IOException {
		convBackwards("convBackwardsSmallPadded", 1, 3, 4, 4, 2, 3, 1, true);
	}

	@Test(timeout = 30000)
	public void convBackwardsMedium() throws IOException {
		convBackwards("convBackwardsMedium", 1, 28, 28, 28, 3, 28, 0, true);
	}

	@Test(timeout = 90 * 60000)
	public void convBackwardsMediumBatch() throws IOException {
		if (testDepth < 2) return;

		convBackwards("convBackwardsMediumBatch", 4, 28, 28, 28, 3, 28, 0, true);
	}

	@Test(timeout = 30000)
	public void convBackwardsLarge() throws IOException {
		convBackwards("convBackwardsLarge", 1, 168, 7, 7, 3, 112, 1, true);
	}

	@Test(timeout = 30000)
	public void convBackwardsMediumPadded() throws IOException {
		convBackwards("convBackwardsMediumPadded", 1, 28, 28, 28, 3, 28, 1, true);
	}

	@Test(timeout = 30000)
	public void convGradientSmallest() throws IOException {
		convGradient("convGradientSmallest", 1, 1, 4, 4, 2, 1, 0, false);
	}

	@Test(timeout = 30000)
	public void convGradientSmall() throws IOException {
		convGradient("convGradientSmallest", 3, 2, 4, 4, 2, 2, 0, false);
	}

	public void convGradient(String name, int bs, int c, int h, int w, int convSize,
							 int filterCount, int padding, boolean bias) throws IOException {
		TraversalPolicy inputShape = shape(bs, c, h, w);

		Block conv = convolution2d(c, filterCount, convSize, padding, bias).apply(inputShape);
		CellularLayer layer = conv instanceof SequentialBlock ?
				(CellularLayer) ((SequentialBlock) conv).getBlocks().get(1) : (CellularLayer) conv;

		Cell.CaptureReceptor<PackedCollection> receptor = new Cell.CaptureReceptor<>();
		layer.getBackward().setReceptor(receptor);
		((Learning) layer).setParameterUpdate(ParameterUpdate.disabled());
		layer.setup().get().run();

		PackedCollection filter = layer.getWeights().get(0);
		log(filter.getShape()); // (filterCount, c, convSize, convSize)

		PackedCollection inputGradient =
				new PackedCollection(layer.getOutputShape()).randFill();

		Supplier<Runnable> op = layer.getBackward().push(cp(inputGradient));
		op.get().run();

		PackedCollection outputGradient = receptor.getReceipt().evaluate();
		log(outputGradient.getShape());

		int outH = layer.getOutputShape().length(2);
		int outW = layer.getOutputShape().length(3);

		for (int n = 0; n < bs; n++) {
			for (int inCh = 0; inCh < c; inCh++) {
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						double expected = 0.0;

						// Each (y, x) in the input influences some set of (outY, outX) in the output
						for (int outCh = 0; outCh < filterCount; outCh++) {
							for (int ky = 0; ky < convSize; ky++) {
								for (int kx = 0; kx < convSize; kx++) {
									// Derive which output pixel would have used input pixel (y, x)
									int outY = (y - ky) + padding;
									int outX = (x - kx) + padding;

									// Only accumulate if (outY, outX) is valid in the output gradient
									if (outY >= 0 && outY < outH && outX >= 0 && outX < outW) {
										double gradVal = inputGradient.valueAt(n, outCh, outY, outX);
										double filterVal = filter.valueAt(outCh, inCh, ky, kx);
										expected += gradVal * filterVal;
									}
								}
							}
						}

						double actual = outputGradient.valueAt(n, inCh, y, x);
						if (verboseLogs)
							log("[" + n + ", " + inCh + ", " + y + ", " + x + "] " + expected + " vs " + actual);
						assertEquals(expected, actual);
					}
				}
			}
		}
	}

	public void convBackwards(String name, int n, int c, int h, int w, int convSize,
							  int filterCount, int padding, boolean bias) throws IOException {
		TraversalPolicy inputShape = shape(n, c, h, w);
		Model model = new Model(inputShape);

		Block conv = convolution2d(c, filterCount, convSize, padding, bias).apply(inputShape);
		CellularLayer layer = conv instanceof SequentialBlock ?
				(CellularLayer) ((SequentialBlock) conv).getBlocks().get(1) : (CellularLayer) conv;
		model.add(conv);

		OperationProfileNode profile = new OperationProfileNode(name);

		try {
			PackedCollection gradient =
					new PackedCollection(model.getOutputShape()).randFill();

			CompiledModel compiled = model.compile(profile);
			profile(profile, () -> compiled.backward(gradient));

			PackedCollection filter = layer.getWeights().get(0);
			TraversalPolicy filterShape = filter.getShape();
			Assert.assertEquals(filterCount, filterShape.length(0));
			Assert.assertEquals(c, filterShape.length(1));
			Assert.assertEquals(convSize, filterShape.length(2));
			Assert.assertEquals(convSize, filterShape.length(3));
		} finally {
			profile.save("results/" + name + ".xml");
		}
	}

	protected void validateConv(PackedCollection input, PackedCollection filter, PackedCollection output, int convSize) {
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
						if (verboseLogs)
							log("[" + f + ", " + row + ", " + col + "] " + expected + " vs " + actual);
						assertEquals(expected, actual);
					}
				}
			}
		}
	}
}
