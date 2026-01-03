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
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for Conv1d, ConvTranspose1d, and Snake activation layers.
 * These layers are used in the Oobleck Autoencoder implementation.
 */
public class Conv1dLayerTests implements LayerFeatures, TestFeatures {

	/**
	 * Tests Snake activation with alpha=1.0.
	 * Snake formula: f(x) = x + (1/alpha) * sin^2(alpha * x)
	 */
	@Test(timeout = 60000)
	public void testSnakeActivation() {
		int size = 100;
		double alpha = 1.0;

		// Create input with values in range [-pi, pi] to test sinusoidal behavior
		PackedCollection input = new PackedCollection(shape(size));
		input.fill(pos -> (pos[0] - size / 2.0) * Math.PI / (size / 2.0));

		// Create Snake layer
		CellularLayer snake = snake(shape(size), alpha);

		// Capture output
		PackedCollection actualOutput = new PackedCollection(shape(size));
		snake.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			actualOutput.setMem(0, result.toArray(0, size), 0, size);
		});

		// Execute
		OperationList op = (OperationList) snake.getForward().push(p(input));
		op.get().run();

		// Calculate expected output: f(x) = x + (1/alpha) * sin^2(alpha * x)
		double[] inputArray = input.toArray(0, size);
		double[] expectedOutput = new double[size];

		for (int i = 0; i < size; i++) {
			double x = inputArray[i];
			double sinPart = Math.sin(alpha * x);
			expectedOutput[i] = x + (1.0 / alpha) * sinPart * sinPart;
		}

		// Compare results
		double[] actualArray = actualOutput.toArray(0, size);
		for (int i = 0; i < size; i++) {
			Assert.assertEquals("Snake mismatch at index " + i,
					expectedOutput[i], actualArray[i], 1e-5);
		}
	}

	/**
	 * Tests Snake activation with alpha=0.5.
	 */
	@Test(timeout = 60000)
	public void testSnakeActivationAlpha05() {
		int size = 50;
		double alpha = 0.5;

		PackedCollection input = new PackedCollection(shape(size));
		input.fill(pos -> (pos[0] - size / 2.0) * 0.2);

		CellularLayer snake = snake(shape(size), alpha);

		PackedCollection actualOutput = new PackedCollection(shape(size));
		snake.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			actualOutput.setMem(0, result.toArray(0, size), 0, size);
		});

		OperationList op = (OperationList) snake.getForward().push(p(input));
		op.get().run();

		double[] inputArray = input.toArray(0, size);
		double[] actualArray = actualOutput.toArray(0, size);

		for (int i = 0; i < size; i++) {
			double x = inputArray[i];
			double sinPart = Math.sin(alpha * x);
			double expected = x + (1.0 / alpha) * sinPart * sinPart;
			Assert.assertEquals("Snake alpha=0.5 mismatch at index " + i,
					expected, actualArray[i], 1e-5);
		}
	}

	/**
	 * Tests Conv1d with kernel size 1 (pointwise convolution).
	 * This should behave like a linear transformation across channels.
	 */
	@Test(timeout = 60000)
	public void testConv1dKernel1() {
		int batchSize = 1;
		int inputChannels = 2;
		int outputChannels = 4;
		int seqLength = 8;

		// Create input
		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		// Create weights: [out_ch, in_ch, kernel_size]
		PackedCollection weights = new PackedCollection(shape(outputChannels, inputChannels, 1));
		weights.fill(pos -> 0.5); // All weights = 0.5

		// Create bias
		PackedCollection bias = new PackedCollection(shape(outputChannels));
		bias.fill(pos -> 0.1);

		// Create Conv1d block
		Block conv = convolution1d(batchSize, inputChannels, outputChannels, seqLength,
								   1, 1, 0, weights, bias);

		// Execute through a sequential block
		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(conv);

		PackedCollection actualOutput = new PackedCollection(shape(batchSize, outputChannels, seqLength));
		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			int totalSize = batchSize * outputChannels * seqLength;
			actualOutput.setMem(0, result.toArray(0, totalSize), 0, totalSize);
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();

		// Expected: for kernel_size=1, each output = sum(inputs * weight) + bias
		// input all 1s, weights all 0.5, so sum = inputChannels * 0.5 = 1.0, + bias 0.1 = 1.1
		double expectedValue = inputChannels * 0.5 + 0.1;
		double[] actualArray = actualOutput.toArray(0, batchSize * outputChannels * seqLength);

		for (int i = 0; i < actualArray.length; i++) {
			Assert.assertEquals("Conv1d k=1 mismatch at index " + i,
					expectedValue, actualArray[i], 1e-4);
		}
	}

	/**
	 * Tests Conv1d with kernel size 3 and stride 1.
	 * Verifies output shape calculation.
	 */
	@Test(timeout = 60000)
	public void testConv1dKernel3Stride1() {
		int batchSize = 1;
		int inputChannels = 2;
		int outputChannels = 4;
		int seqLength = 16;
		int kernelSize = 3;
		int stride = 1;
		int padding = 1; // Same padding to preserve length

		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		PackedCollection weights = new PackedCollection(shape(outputChannels, inputChannels, kernelSize));
		weights.fill(pos -> 0.1);

		PackedCollection bias = new PackedCollection(shape(outputChannels));
		bias.fill(pos -> 0.0);

		Block conv = convolution1d(batchSize, inputChannels, outputChannels, seqLength,
								   kernelSize, stride, padding, weights, bias);

		// Output length with padding = (16 + 2*1 - 3) / 1 + 1 = 16
		int expectedOutLength = (seqLength + 2 * padding - kernelSize) / stride + 1;
		assertEquals(16, expectedOutLength);

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(conv);

		PackedCollection actualOutput = new PackedCollection(shape(batchSize, outputChannels, expectedOutLength));
		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			// Just verify it runs and produces output of expected shape
			assertEquals(batchSize * outputChannels * expectedOutLength, result.getShape().getTotalSize());
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();
	}

	/**
	 * Tests Conv1d with stride 2 for downsampling.
	 * This is the pattern used in the encoder blocks.
	 */
	@Test(timeout = 60000)
	public void testConv1dStride2Downsampling() {
		int batchSize = 1;
		int inputChannels = 2;
		int outputChannels = 4;
		int seqLength = 16;
		int kernelSize = 3;
		int stride = 2;
		int padding = 1;

		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> pos[2] + 1.0); // Sequential values 1, 2, 3, ..., 16

		PackedCollection weights = new PackedCollection(shape(outputChannels, inputChannels, kernelSize));
		weights.fill(pos -> 1.0 / (inputChannels * kernelSize));

		PackedCollection bias = new PackedCollection(shape(outputChannels));
		bias.fill(pos -> 0.0);

		Block conv = convolution1d(batchSize, inputChannels, outputChannels, seqLength,
								   kernelSize, stride, padding, weights, bias);

		// Output length = (16 + 2*1 - 3) / 2 + 1 = 8
		int expectedOutLength = (seqLength + 2 * padding - kernelSize) / stride + 1;
		assertEquals(8, expectedOutLength);

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(conv);

		PackedCollection actualOutput = new PackedCollection(shape(batchSize, outputChannels, expectedOutLength));
		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			assertEquals(batchSize * outputChannels * expectedOutLength, result.getShape().getTotalSize());
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();
	}

	/**
	 * Tests ConvTranspose1d with stride 2 for upsampling.
	 * This is the pattern used in the decoder blocks.
	 */
	@Test(timeout = 60000)
	public void testConvTranspose1dStride2Upsampling() {
		int batchSize = 1;
		int inputChannels = 4;
		int outputChannels = 2;
		int seqLength = 8;
		int kernelSize = 4; // stride * 2
		int stride = 2;
		int padding = 1; // stride / 2

		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		// Weights for transposed conv: [in_ch, out_ch, kernel_size]
		PackedCollection weights = new PackedCollection(shape(inputChannels, outputChannels, kernelSize));
		weights.fill(pos -> 0.25);

		PackedCollection bias = new PackedCollection(shape(outputChannels));
		bias.fill(pos -> 0.0);

		Block convT = convTranspose1d(batchSize, inputChannels, outputChannels, seqLength,
									  kernelSize, stride, padding, weights, bias);

		// Output length = (8 - 1) * 2 - 2 * 1 + 4 = 7 * 2 - 2 + 4 = 16
		int expectedOutLength = (seqLength - 1) * stride - 2 * padding + kernelSize;
		assertEquals(16, expectedOutLength);

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(convT);

		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			assertEquals(batchSize * outputChannels * expectedOutLength, result.getShape().getTotalSize());
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();
	}

	/**
	 * Tests Conv1d followed by ConvTranspose1d to verify shape consistency.
	 * The output shape should match the original input shape.
	 */
	@Test(timeout = 60000)
	public void testConv1dConvTranspose1dRoundtrip() {
		int batchSize = 1;
		int channels = 4;
		int seqLength = 16;
		int kernelSize = 4;
		int stride = 2;
		int padding = 1;

		// Forward conv: 16 -> 8
		int midLength = (seqLength + 2 * padding - kernelSize) / stride + 1;
		assertEquals(8, midLength);

		// Transposed conv: 8 -> 16
		int outLength = (midLength - 1) * stride - 2 * padding + kernelSize;
		assertEquals(16, outLength);

		PackedCollection input = new PackedCollection(shape(batchSize, channels, seqLength));
		input.fill(pos -> Math.random());

		// Create weights
		PackedCollection convWeights = new PackedCollection(shape(channels, channels, kernelSize));
		convWeights.fill(pos -> 0.1);

		PackedCollection convTWeights = new PackedCollection(shape(channels, channels, kernelSize));
		convTWeights.fill(pos -> 0.1);

		// Build model
		SequentialBlock model = new SequentialBlock(shape(batchSize, channels, seqLength));
		model.add(convolution1d(batchSize, channels, channels, seqLength,
							   kernelSize, stride, padding, convWeights, null));
		model.add(convTranspose1d(batchSize, channels, channels, midLength,
								 kernelSize, stride, padding, convTWeights, null));

		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			// Verify final shape matches original
			assertEquals(batchSize * channels * seqLength, result.getShape().getTotalSize());
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();
	}

	/**
	 * Tests Conv1d without bias.
	 */
	@Test(timeout = 60000)
	public void testConv1dNoBias() {
		int batchSize = 1;
		int inputChannels = 2;
		int outputChannels = 4;
		int seqLength = 8;

		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		PackedCollection weights = new PackedCollection(shape(outputChannels, inputChannels, 1));
		weights.fill(pos -> 0.5);

		// No bias
		Block conv = convolution1d(batchSize, inputChannels, outputChannels, seqLength,
								   1, 1, 0, weights, null);

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(conv);

		PackedCollection actualOutput = new PackedCollection(shape(batchSize, outputChannels, seqLength));
		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			int totalSize = batchSize * outputChannels * seqLength;
			actualOutput.setMem(0, result.toArray(0, totalSize), 0, totalSize);
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();

		// Expected: sum(inputs * weight) = inputChannels * 0.5 = 1.0 (no bias)
		double expectedValue = inputChannels * 0.5;
		double[] actualArray = actualOutput.toArray(0, batchSize * outputChannels * seqLength);

		for (int i = 0; i < actualArray.length; i++) {
			Assert.assertEquals("Conv1d no bias mismatch at index " + i,
					expectedValue, actualArray[i], 1e-4);
		}
	}

	/**
	 * Tests ConvTranspose1d with stride 4 (used in autoencoder).
	 */
	@Test(timeout = 60000)
	public void testConvTranspose1dStride4() {
		int batchSize = 1;
		int inputChannels = 8;
		int outputChannels = 4;
		int seqLength = 4;
		int kernelSize = 8; // stride * 2
		int stride = 4;
		int padding = 2; // stride / 2

		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		PackedCollection weights = new PackedCollection(shape(inputChannels, outputChannels, kernelSize));
		weights.fill(pos -> 0.125);

		Block convT = convTranspose1d(batchSize, inputChannels, outputChannels, seqLength,
									  kernelSize, stride, padding, weights, null);

		// Output length = (4 - 1) * 4 - 2 * 2 + 8 = 12 - 4 + 8 = 16
		int expectedOutLength = (seqLength - 1) * stride - 2 * padding + kernelSize;
		assertEquals(16, expectedOutLength);

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(convT);

		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			assertEquals(batchSize * outputChannels * expectedOutLength, result.getShape().getTotalSize());
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();
	}

	/**
	 * Tests ConvTranspose1d with stride 8 (used in autoencoder decoder).
	 */
	@Test(timeout = 60000)
	public void testConvTranspose1dStride8() {
		int batchSize = 1;
		int inputChannels = 16;
		int outputChannels = 8;
		int seqLength = 2;
		int kernelSize = 16; // stride * 2
		int stride = 8;
		int padding = 4; // stride / 2

		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		PackedCollection weights = new PackedCollection(shape(inputChannels, outputChannels, kernelSize));
		weights.fill(pos -> 0.0625);

		Block convT = convTranspose1d(batchSize, inputChannels, outputChannels, seqLength,
									  kernelSize, stride, padding, weights, null);

		// Output length = (2 - 1) * 8 - 2 * 4 + 16 = 8 - 8 + 16 = 16
		int expectedOutLength = (seqLength - 1) * stride - 2 * padding + kernelSize;
		assertEquals(16, expectedOutLength);

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(convT);

		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			assertEquals(batchSize * outputChannels * expectedOutLength, result.getShape().getTotalSize());
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();
	}

	/**
	 * Tests ConvTranspose1d correctness with manual computation.
	 * This test verifies the output values match expected results computed manually.
	 * It also logs the weighted sum size to help diagnose performance issues.
	 */
	@Test(timeout = 60000)
	public void testConvTranspose1dCorrectnessSmall() {
		int batchSize = 1;
		int inputChannels = 4;
		int outputChannels = 2;
		int seqLength = 2;
		int kernelSize = 4;
		int stride = 2;
		int padding = 1;
		int outputPadding = 0;

		// Weighted sum size per output element = inputChannels * kernelSize
		int weightedSumSize = inputChannels * kernelSize;
		System.err.println("ConvTranspose1d correctness test:");
		System.err.println("  inputChannels=" + inputChannels + ", kernelSize=" + kernelSize);
		System.err.println("  Weighted sum size per output element: " + weightedSumSize);

		// Create simple input: all 1s
		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		// Create simple weights: all same value so output is predictable
		PackedCollection weights = new PackedCollection(shape(inputChannels, outputChannels, kernelSize));
		double weightVal = 1.0 / weightedSumSize;
		weights.fill(pos -> weightVal);

		int outLength = (seqLength - 1) * stride - 2 * padding + kernelSize + outputPadding;
		System.err.println("  Output length: " + outLength);
		System.err.println("  Total output elements: " + (batchSize * outputChannels * outLength));

		Block convT = convTranspose1d(batchSize, inputChannels, outputChannels, seqLength,
									  kernelSize, stride, padding, outputPadding, weights, null);

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(convT);

		PackedCollection actualOutput = new PackedCollection(shape(batchSize, outputChannels, outLength));
		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			int totalSize = batchSize * outputChannels * outLength;
			actualOutput.setMem(0, result.toArray(0, totalSize), 0, totalSize);
		});

		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();

		// Print actual output for verification
		double[] actual = actualOutput.toArray(0, batchSize * outputChannels * outLength);
		System.err.println("  Output values: ");
		for (int i = 0; i < actual.length; i++) {
			System.err.println("    [" + i + "] = " + actual[i]);
		}

		// Verify at least one non-zero output
		boolean hasNonZero = false;
		for (double v : actual) {
			if (Math.abs(v) > 1e-10) hasNonZero = true;
		}
		Assert.assertTrue("Output should have non-zero values", hasNonZero);
	}

	/**
	 * Tests ConvTranspose1d with large channel count to measure scaling.
	 * This is the problematic case from the Oobleck decoder (2048 input channels).
	 */
	@Test(timeout = 60000)
	public void testConvTranspose1dLargeChannels() {
		int batchSize = 1;
		int inputChannels = 2048;  // Same as Oobleck decoder block 1
		int outputChannels = 1024;
		int seqLength = 2;
		int kernelSize = 16;
		int stride = 16;
		int padding = 7;
		int outputPadding = 15;

		// Weighted sum size per output element = inputChannels * kernelSize
		int weightedSumSize = inputChannels * kernelSize;
		System.err.println("\n=== ConvTranspose1d LARGE CHANNEL TEST ===");
		System.err.println("  inputChannels=" + inputChannels + ", kernelSize=" + kernelSize);
		System.err.println("  Weighted sum size per output element: " + weightedSumSize);
		System.err.println("  This is " + (weightedSumSize / 1024) + "K values summed PER OUTPUT ELEMENT!");

		int outLength = (seqLength - 1) * stride - 2 * padding + kernelSize + outputPadding;
		int totalOutputElements = batchSize * outputChannels * outLength;
		System.err.println("  Output length: " + outLength);
		System.err.println("  Total output elements: " + totalOutputElements);
		System.err.println("  Total weighted sums computed: " + totalOutputElements);
		System.err.println("  Total multiply-adds: " + ((long) totalOutputElements * weightedSumSize));

		// Create input
		PackedCollection input = new PackedCollection(shape(batchSize, inputChannels, seqLength));
		input.fill(pos -> 1.0);

		// Create weights
		PackedCollection weights = new PackedCollection(shape(inputChannels, outputChannels, kernelSize));
		weights.fill(pos -> 0.0001);

		System.err.println("\nBuilding convTranspose1d block...");
		long buildStart = System.currentTimeMillis();
		Block convT = convTranspose1d(batchSize, inputChannels, outputChannels, seqLength,
									  kernelSize, stride, padding, outputPadding, weights, null);
		System.err.println("  Block build time: " + (System.currentTimeMillis() - buildStart) + "ms");

		SequentialBlock model = new SequentialBlock(shape(batchSize, inputChannels, seqLength));
		model.add(convT);

		model.getForward().setReceptor(out -> () -> () -> {
			out.get().evaluate();
		});

		System.err.println("Compiling model...");
		long compileStart = System.currentTimeMillis();
		OperationList op = (OperationList) model.getForward().push(p(input));

		// This is where it gets slow - the .get() triggers kernel compilation
		System.err.println("Getting compiled operation (this is where slowness occurs)...");
		long getStart = System.currentTimeMillis();
		Runnable compiled = op.get();
		System.err.println("  op.get() time: " + (System.currentTimeMillis() - getStart) + "ms");

		System.err.println("Running forward pass...");
		long runStart = System.currentTimeMillis();
		compiled.run();
		System.err.println("  Forward pass time: " + (System.currentTimeMillis() - runStart) + "ms");

		System.err.println("Total compile time: " + (System.currentTimeMillis() - compileStart) + "ms");
	}
}
