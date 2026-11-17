/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.layers.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.Learning;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies that a model containing a {@link org.almostrealism.layers.ConvolutionLayerFeatures#convTranspose1d
 * convTranspose1d} layer back-propagates: the input gradient the backward pass returns, and the
 * gradient the weight update applies, each match the central differences of the forward pass.
 *
 * <p>The forward pass itself is checked separately, against a direct reference, by
 * {@link ConvTranspose1dReferenceTest}; this test exercises only the gradient.</p>
 */
public class ConvTranspose1dBackwardTest extends TestSuiteBase {

	/** Perturbation step for the finite differences. */
	private static final double EPS = 1e-4;

	/** Permitted relative difference between the analytic gradient and the finite differences. */
	private static final double TOLERANCE = 1e-3;

	/** The analytic gradient must reach at least this magnitude, so a trivially zero one cannot pass. */
	private static final double MINIMUM = 1e-4;

	/**
	 * Returns a collection of the given shape filled with distinct, non-trivial values: successive
	 * multiples of {@code scale} offset by {@code offset}, so no two elements coincide and the
	 * gradient of each is exercised independently.
	 *
	 * @param shape  the shape of the collection
	 * @param scale  the spacing between successive values
	 * @param offset the value of the first element
	 * @return the filled collection
	 */
	private PackedCollection nonTrivial(TraversalPolicy shape, double scale, double offset) {
		return integers(0, shape.getTotalSize()).multiply(scale).add(offset).evaluate()
				.reshape(shape);
	}

	/**
	 * Returns the scalar {@code sum(output * upstream)}, whose gradient with respect to any input of
	 * the forward pass is that input's gradient weighted by {@code upstream}.
	 *
	 * @param output   the model output
	 * @param upstream the upstream gradient, of the output shape
	 * @return the inner product of the two
	 */
	private double loss(PackedCollection output, PackedCollection upstream) {
		int n = output.getShape().getTotalSize();
		return cp(output).reshape(shape(n))
				.multiply(cp(upstream).reshape(shape(n)))
				.sum().evaluate().toDouble(0);
	}

	/**
	 * Builds a single-layer transposed-convolution model, back-propagates a non-trivial upstream
	 * gradient through it, and checks the input gradient and the filter gradient against the
	 * central differences of {@code sum(output * upstream)}.
	 *
	 * @param testName      a label for the assertions
	 * @param inChannels    number of input channels
	 * @param outChannels   number of output channels
	 * @param seqLength     input sequence length
	 * @param kernel        kernel size
	 * @param stride        stride
	 * @param padding       padding
	 * @param outputPadding output padding
	 */
	private void runGradientCase(String testName, int inChannels, int outChannels,
								 int seqLength, int kernel, int stride,
								 int padding, int outputPadding) {
		int batchSize = 1;
		int outLength = (seqLength - 1) * stride - 2 * padding + kernel + outputPadding;

		TraversalPolicy inputShape = shape(batchSize, inChannels, seqLength);
		TraversalPolicy weightShape = shape(inChannels, outChannels, kernel);
		TraversalPolicy outputShape = shape(batchSize, outChannels, outLength);

		PackedCollection input = nonTrivial(inputShape, 0.1, 0.3);
		PackedCollection weights = nonTrivial(weightShape, 0.07, -0.2);
		PackedCollection upstream = nonTrivial(outputShape, 0.05, 0.2);

		// --- Input gradient: weights are frozen so the backward pass leaves them intact ---
		Model model = new Model(inputShape);
		CellularLayer layer = (CellularLayer) convTranspose1d(batchSize, inChannels, outChannels,
				seqLength, kernel, stride, padding, outputPadding, weights, null);
		((Learning) layer).setParameterUpdate(ParameterUpdate.disabled());
		model.add(layer);

		CompiledModel compiled = model.compile(true, true);
		compiled.forward(input);
		double[] inputGradient = compiled.backward(upstream).toArray();

		double[] numericInput = extrapolatedDifferences(input, EPS,
				() -> loss(compiled.forward(input), upstream));
		assertGradientMatches(testName + " input", numericInput, inputGradient, TOLERANCE, MINIMUM);

		// --- Filter gradient: observed as the weight update produced by a known learning rate ---
		double learningRate = 1.0;
		Model trained = new Model(inputShape, learningRate);
		CellularLayer trainedLayer = (CellularLayer) convTranspose1d(batchSize, inChannels, outChannels,
				seqLength, kernel, stride, padding, outputPadding, weights, null);
		trained.add(trainedLayer);

		CompiledModel trainedCompiled = trained.compile(true, true);
		trainedCompiled.forward(input);

		// Snapshot the weights on the device so they can be restored after the update
		PackedCollection weightsSnapshot = new PackedCollection(weights.getShape());
		weightsSnapshot.setFrom(0, weights);

		double[] weightsBefore = weights.toArray();
		trainedCompiled.backward(upstream);
		double[] weightsAfter = weights.toArray();

		double[] filterGradient = new double[weightsBefore.length];
		for (int i = 0; i < filterGradient.length; i++) {
			filterGradient[i] = (weightsBefore[i] - weightsAfter[i]) / learningRate;
		}

		// Restore the weights the update changed before taking finite differences of them
		weights.setFrom(0, weightsSnapshot);

		double[] numericWeights = extrapolatedDifferences(weights, EPS,
				() -> loss(trainedCompiled.forward(input), upstream));
		assertGradientMatches(testName + " filter", numericWeights, filterGradient, TOLERANCE, MINIMUM);
	}

	/**
	 * Transposed convolution with stride 2 and padding 1.
	 */
	@Test(timeout = 120000)
	public void gradientStride2Padding1() {
		runGradientCase("stride2_padding1", 2, 3, 4, 4, 2, 1, 0);
	}

	/**
	 * Transposed convolution with stride 1 and no padding.
	 */
	@Test(timeout = 120000)
	public void gradientStride1Padding0() {
		runGradientCase("stride1_padding0", 2, 3, 4, 3, 1, 0, 0);
	}
}
