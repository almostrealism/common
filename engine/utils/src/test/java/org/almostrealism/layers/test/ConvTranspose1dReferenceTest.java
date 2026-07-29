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

package org.almostrealism.layers.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;

/**
 * Tests ConvTranspose1d against a reference implementation.
 *
 * <p>Inputs are sequential values (1, 2, 3, ...) and weights are small sequential multiples
 * of 0.01, so outputs are easy to verify by hand. Expected outputs come from
 * {@link #referenceConvTranspose1d}, a direct host-side statement of the transposed
 * convolution whose semantics match {@code torch.nn.functional.conv_transpose1d}
 * (verified against PyTorch for each of these five cases when it was introduced).</p>
 *
 * <p>This test isolates ConvTranspose1d indexing issues by using simple inputs
 * that make manual verification straightforward.</p>
 */
public class ConvTranspose1dReferenceTest extends TestSuiteBase {

	/** Tolerance for floating-point comparisons. */
	private static final double TOLERANCE = 0.001;

	/**
	 * Reference transposed 1D convolution, computed directly on the host.
	 *
	 * <p>Input is {@code (batch, inChannels, seqLength)}, weights are
	 * {@code (inChannels, outChannels, kernel)}, bias is {@code (outChannels)}; the result is
	 * {@code (batch, outChannels, outLen)} with
	 * {@code outLen = (seqLength - 1) * stride - 2 * padding + kernel + outputPadding},
	 * matching {@code torch.nn.functional.conv_transpose1d}.</p>
	 *
	 * @param input Input collection of shape (batch, inChannels, seqLength)
	 * @param weights Weight collection of shape (inChannels, outChannels, kernel)
	 * @param bias Bias collection of shape (outChannels)
	 * @param batchSize Number of batches
	 * @param inChannels Number of input channels
	 * @param outChannels Number of output channels
	 * @param seqLength Input sequence length
	 * @param kernel Kernel size
	 * @param stride Stride
	 * @param padding Padding
	 * @param outputPadding Output padding
	 * @return Expected output values in row-major order
	 */
	private double[] referenceConvTranspose1d(
			PackedCollection input, PackedCollection weights, PackedCollection bias,
			int batchSize, int inChannels, int outChannels, int seqLength,
			int kernel, int stride, int padding, int outputPadding) {
		int outLen = (seqLength - 1) * stride - 2 * padding + kernel + outputPadding;

		double in[] = input.toArray();
		double w[] = weights.toArray();
		double b[] = bias.toArray();
		double out[] = new double[batchSize * outChannels * outLen];

		for (int bt = 0; bt < batchSize; bt++) {
			for (int oc = 0; oc < outChannels; oc++) {
				for (int t = 0; t < seqLength; t++) {
					for (int ic = 0; ic < inChannels; ic++) {
						for (int k = 0; k < kernel; k++) {
							int j = t * stride + k - padding;
							if (j >= 0 && j < outLen) {
								out[(bt * outChannels + oc) * outLen + j] +=
										in[(bt * inChannels + ic) * seqLength + t] *
										w[(ic * outChannels + oc) * kernel + k];
							}
						}
					}
				}

				for (int j = 0; j < outLen; j++) {
					out[(bt * outChannels + oc) * outLen + j] += b[oc];
				}
			}
		}

		return out;
	}

	/**
	 * Compares actual output against expected reference values.
	 *
	 * @param testName Name of the test for logging
	 * @param actual Actual output collection
	 * @param expected Expected reference values
	 */
	private void compareOutputs(String testName, PackedCollection actual, double[] expected) {
		int size = (int) actual.getMemLength();
		assertEquals(testName + " size mismatch", expected.length, size);

		log("\n" + testName + " comparison:");
		double maxDiff = 0;
		double sumDiff = 0;
		int failCount = 0;

		for (int i = 0; i < size; i++) {
			double actualVal = actual.toDouble(i);
			double expectedVal = expected[i];
			double diff = Math.abs(actualVal - expectedVal);

			maxDiff = Math.max(maxDiff, diff);
			sumDiff += diff;

			if (diff > TOLERANCE) {
				failCount++;
				if (failCount <= 10) {
					log(String.format("  [%d] actual=%.6f, expected=%.6f, diff=%.6f FAIL",
							i, actualVal, expectedVal, diff));
				}
			} else if (i < 5) {
				log(String.format("  [%d] actual=%.6f, expected=%.6f, diff=%.6f OK",
						i, actualVal, expectedVal, diff));
			}
		}

		double mae = sumDiff / size;
		log(String.format("\nMAE: %.6f, MaxDiff: %.6f, Failures: %d/%d",
				mae, maxDiff, failCount, size));

		assertEquals(testName + " has failures", 0, failCount);
	}

	/**
	 * Builds sequential test data for the given parameters, runs ConvTranspose1d through a
	 * compiled model, and compares the output against {@link #referenceConvTranspose1d}.
	 *
	 * @param testName Name of the test case for logging
	 * @param inChannels Number of input channels
	 * @param outChannels Number of output channels
	 * @param seqLength Input sequence length
	 * @param kernel Kernel size
	 * @param stride Stride
	 * @param padding Padding
	 * @param outputPadding Output padding
	 */
	private void runReferenceCase(String testName, int inChannels, int outChannels,
								  int seqLength, int kernel, int stride,
								  int padding, int outputPadding) {
		log("=== ConvTranspose1d " + testName + " ===");

		int batchSize = 1;
		int inputTotal = batchSize * inChannels * seqLength;
		int weightTotal = inChannels * outChannels * kernel;

		PackedCollection input = integers(1, inputTotal + 1).evaluate()
				.reshape(shape(batchSize, inChannels, seqLength));
		PackedCollection weights = integers(1, weightTotal + 1).multiply(0.01).evaluate()
				.reshape(shape(inChannels, outChannels, kernel));
		PackedCollection bias = new PackedCollection(shape(outChannels));

		double[] expected = referenceConvTranspose1d(input, weights, bias,
				batchSize, inChannels, outChannels, seqLength,
				kernel, stride, padding, outputPadding);

		log("Input: " + Arrays.toString(input.toArray()));
		log("Expected output: " + Arrays.toString(expected));

		Model model = new Model(shape(batchSize, inChannels, seqLength));
		model.add(convTranspose1d(batchSize, inChannels, outChannels, seqLength,
				kernel, stride, padding, outputPadding, weights, bias));

		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(input);

		log("Output shape: " + output.getShape());
		compareOutputs(testName, output, expected);

		log("\n=== " + testName + " PASSED ===");
	}

	/**
	 * Minimal test: 1 input channel, 1 output channel, stride=2, no padding.
	 * Input: [1, 2] -> Output: [0.01, 0.02, 0.02, 0.04]
	 */
	@Test(timeout = 60000)
	public void testMinimal() {
		runReferenceCase("minimal", 1, 1, 2, 2, 2, 0, 0);
	}

	/**
	 * Test with padding: kernel=4, stride=2, padding=1.
	 */
	@Test(timeout = 60000)
	public void testWithPadding() {
		runReferenceCase("with_padding", 1, 1, 2, 4, 2, 1, 0);
	}

	/**
	 * Multi-channel test: in_ch=2, out_ch=2, stride=2.
	 */
	@Test(timeout = 60000)
	public void testMultichannelSmall() {
		runReferenceCase("multichannel_small", 2, 2, 2, 2, 2, 0, 0);
	}

	/**
	 * Oobleck-like test: stride=4, outputPadding=3.
	 * Similar to real Oobleck decoder but with tiny dimensions.
	 */
	@Test(timeout = 60000)
	public void testOobleckLike() {
		runReferenceCase("oobleck_like", 4, 2, 2, 4, 4, 1, 3);
	}

	/**
	 * Stride=16 test: exactly like Oobleck decoder block 1 parameters but tiny channels.
	 * in_ch=2, out_ch=1, seq=2, kernel=16, stride=16, padding=7, outputPadding=15.
	 * Output length = (2-1)*16 - 2*7 + 16 + 15 = 16 - 14 + 31 = 33.
	 */
	@Test(timeout = 60000)
	public void testStride16Tiny() {
		runReferenceCase("stride16_tiny", 2, 1, 2, 16, 16, 7, 15);
	}
}
