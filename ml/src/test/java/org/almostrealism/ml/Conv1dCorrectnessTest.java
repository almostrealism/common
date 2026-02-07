/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Focused correctness tests for 1D convolution.
 * Uses simple inputs and hand-calculated expected outputs
 * to verify the implementation is correct.
 */
public class Conv1dCorrectnessTest implements LayerFeatures {

	/**
	 * Test weightedSum directly with the same parameters as conv1d.
	 * This isolates whether the bug is in convolution1d setup or weightedSum.
	 */
	@Test
	public void testWeightedSumDirect() {
		int batchSize = 1;
		int inputChannels = 1;
		int outputChannels = 1;
		int seqLength = 5;
		int kernelSize = 3;
		int stride = 1;
		int paddedLength = seqLength; // no padding
		int outLength = 3;

		// Input: [1, 2, 3, 4, 5] with shape (1, 1, 1, 5)
		PackedCollection input = new PackedCollection(shape(1, 1, 1, 5));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0);

		// Weights: [0.5, 1.0, 0.5] with shape (1, 1, 1, 3)
		PackedCollection weights = new PackedCollection(shape(1, 1, 1, 3));
		weights.setMem(0, 0.5, 1.0, 0.5);

		// Define positions for weighted sum (same as conv1d uses)
		TraversalPolicy resultShape = shape(batchSize, outputChannels, 1, outLength);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, 1, outputChannels)
				.withRate(2, inputChannels, 1)
				.withRate(3, stride, 1);
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, batchSize)
				.withRate(2, inputChannels, 1)
				.withRate(3, kernelSize, outLength);
		TraversalPolicy groupShape = shape(1, 1, inputChannels, kernelSize);

		System.out.println("=== Test WeightedSum Direct ===");
		System.out.println("resultShape: " + resultShape);
		System.out.println("inputPositions: " + inputPositions);
		System.out.println("filterPositions: " + filterPositions);
		System.out.println("groupShape: " + groupShape);

		CollectionProducer result = weightedSum("conv1dTest",
				inputPositions, filterPositions,
				groupShape, cp(input), cp(weights));

		// Reshape to output shape and evaluate
		Evaluable eval = result.reshape(batchSize, outputChannels, outLength)
				.traverseEach().get();
		PackedCollection output = (PackedCollection) eval.evaluate();

		System.out.println("Output shape: " + output.getShape());
		System.out.println("Output values:");
		for (int i = 0; i < outLength; i++) {
			System.out.println("  out[" + i + "] = " + output.toDouble(i));
		}

		// Expected: [4.0, 6.0, 8.0]
		double[] expected = {4.0, 6.0, 8.0};
		for (int i = 0; i < outLength; i++) {
			double actual = output.toDouble(i);
			System.out.println("  Expected[" + i + "] = " + expected[i] + ", Actual = " + actual);
			Assert.assertEquals("Output position " + i, expected[i], actual, 0.001);
		}

		System.out.println("=== Test PASSED ===\n");
	}

	/**
	 * Test 1: Simplest possible case.
	 * Single channel, batch=1, kernel=3, stride=1, no padding.
	 * Input: [1, 2, 3, 4, 5]
	 * Weights: [0.5, 1.0, 0.5]
	 * Expected output (valid convolution):
	 *   out[0] = 0.5*1 + 1.0*2 + 0.5*3 = 0.5 + 2.0 + 1.5 = 4.0
	 *   out[1] = 0.5*2 + 1.0*3 + 0.5*4 = 1.0 + 3.0 + 2.0 = 6.0
	 *   out[2] = 0.5*3 + 1.0*4 + 0.5*5 = 1.5 + 4.0 + 2.5 = 8.0
	 */
	/**
	 * Test 0: Minimal test using manual matrix multiplication.
	 * This verifies that basic element-by-element operations work.
	 */
	@Test
	public void testManualConv() {
		// Input: [1, 2, 3, 4, 5]
		// Weights: [0.5, 1.0, 0.5]
		// Expected: [4.0, 6.0, 8.0]

		PackedCollection input = new PackedCollection(shape(5));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0);

		PackedCollection weights = new PackedCollection(shape(3));
		weights.setMem(0, 0.5, 1.0, 0.5);

		// Compute each output position manually
		double out0 = input.toDouble(0) * weights.toDouble(0) +
					  input.toDouble(1) * weights.toDouble(1) +
					  input.toDouble(2) * weights.toDouble(2);
		double out1 = input.toDouble(1) * weights.toDouble(0) +
					  input.toDouble(2) * weights.toDouble(1) +
					  input.toDouble(3) * weights.toDouble(2);
		double out2 = input.toDouble(2) * weights.toDouble(0) +
					  input.toDouble(3) * weights.toDouble(1) +
					  input.toDouble(4) * weights.toDouble(2);

		System.out.println("Manual computation:");
		System.out.println("  out0 = " + out0 + " (expected 4.0)");
		System.out.println("  out1 = " + out1 + " (expected 6.0)");
		System.out.println("  out2 = " + out2 + " (expected 8.0)");

		Assert.assertEquals(4.0, out0, 0.001);
		Assert.assertEquals(6.0, out1, 0.001);
		Assert.assertEquals(8.0, out2, 0.001);
		System.out.println("=== Manual test PASSED ===\n");
	}

	@Test
	public void testSimpleConv1d() {
		// Disable debug logging - it causes toArray() errors with parameterized expressions
		io.almostrealism.collect.SubsetTraversalExpression.enableLogging = false;
		io.almostrealism.collect.SubsetTraversalIndexMapping.enableLogging = false;

		int batchSize = 1;
		int inputChannels = 1;
		int outputChannels = 1;
		int seqLength = 5;
		int kernelSize = 3;
		int stride = 1;
		int padding = 0;

		// Expected output length = (5 - 3) / 1 + 1 = 3
		int outLength = 3;

		// Create weights: shape [outputChannels, inputChannels, kernelSize] = [1, 1, 3]
		PackedCollection weights = new PackedCollection(shape(1, 1, 3));
		weights.setMem(0, 0.5, 1.0, 0.5);

		// Create input: shape [batch, channels, seqLength] = [1, 1, 5]
		PackedCollection input = new PackedCollection(shape(1, 1, 5));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0);

		// Build conv1d
		Block conv = convolution1d(batchSize, inputChannels, outputChannels,
				seqLength, kernelSize, stride, padding, weights, null);

		// Create model and compile
		TraversalPolicy inputShape = shape(batchSize, inputChannels, seqLength);
		Model model = new Model(inputShape);
		model.add(conv);

		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		System.out.println("=== Test Simple Conv1d ===");
		System.out.println("Input: [1, 2, 3, 4, 5]");
		System.out.println("Weights: [0.5, 1.0, 0.5]");
		System.out.println("Output shape: " + output.getShape());
		System.out.println("Output values:");
		for (int i = 0; i < outLength; i++) {
			System.out.println("  out[" + i + "] = " + output.toDouble(i));
		}

		// Verify each output position
		double[] expected = {4.0, 6.0, 8.0};
		for (int i = 0; i < outLength; i++) {
			double actual = output.toDouble(i);
			System.out.println("  Expected[" + i + "] = " + expected[i] + ", Actual = " + actual);
			Assert.assertEquals("Output position " + i, expected[i], actual, 0.001);
		}

		System.out.println("=== Test PASSED ===\n");
	}

	/**
	 * Test to isolate whether the issue is with reshape or layer wrapping.
	 * This calls weightedSum with reshape (like conv1d does) but without a Model wrapper.
	 */
	@Test
	public void testWeightedSumWithReshape() {
		int batchSize = 1;
		int inputChannels = 1;
		int outputChannels = 1;
		int seqLength = 5;
		int kernelSize = 3;
		int stride = 1;
		int paddedLength = seqLength;
		int outLength = 3;

		// Create input with shape (1, 1, 5) like conv1d expects
		PackedCollection input = new PackedCollection(shape(1, 1, 5));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0);

		// Create weights with shape (1, 1, 3)
		PackedCollection weights = new PackedCollection(shape(1, 1, 3));
		weights.setMem(0, 0.5, 1.0, 0.5);

		// Mimic conv1d's reshape operation
		CollectionProducer conv = cp(input).reshape(batchSize, 1, inputChannels, paddedLength);
		CollectionProducer filter = cp(weights).reshape(1, outputChannels, inputChannels, kernelSize);

		// Define positions WITH the stride rate (like testWeightedSumDirect)
		TraversalPolicy resultShape = shape(batchSize, outputChannels, 1, outLength);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, 1, outputChannels)
				.withRate(2, inputChannels, 1)
				.withRate(3, stride, 1);  // Including stride rate
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, batchSize)
				.withRate(2, inputChannels, 1)
				.withRate(3, kernelSize, outLength);
		TraversalPolicy groupShape = shape(1, 1, inputChannels, kernelSize);

		System.out.println("=== Test WeightedSum With Reshape ===");
		System.out.println("resultShape: " + resultShape);
		System.out.println("inputPositions: " + inputPositions);
		System.out.println("conv shape: " + conv.getShape());
		System.out.println("filter shape: " + filter.getShape());

		CollectionProducer result = weightedSum("conv1dReshapeTest",
				inputPositions, filterPositions,
				groupShape, conv, filter);

		// Evaluate without Model wrapper
		Evaluable eval = result.reshape(batchSize, outputChannels, outLength)
				.traverseEach().get();
		PackedCollection output = (PackedCollection) eval.evaluate(input);

		System.out.println("Output shape: " + output.getShape());
		System.out.println("Output values:");
		for (int i = 0; i < outLength; i++) {
			System.out.println("  out[" + i + "] = " + output.toDouble(i));
		}

		// Expected: [4.0, 6.0, 8.0]
		double[] expected = {4.0, 6.0, 8.0};
		for (int i = 0; i < outLength; i++) {
			double actual = output.toDouble(i);
			System.out.println("  Expected[" + i + "] = " + expected[i] + ", Actual = " + actual);
			Assert.assertEquals("Output position " + i, expected[i], actual, 0.001);
		}

		System.out.println("=== Test PASSED ===\n");
	}

	/**
	 * Test to see what happens when calling weightedSum with reshape but WITHOUT the stride rate.
	 * This should reproduce the conv1d bug.
	 */
	@Test
	public void testWeightedSumWithReshapeNoStrideRate() {
		int batchSize = 1;
		int inputChannels = 1;
		int outputChannels = 1;
		int seqLength = 5;
		int kernelSize = 3;
		int paddedLength = seqLength;
		int outLength = 3;

		// Create input with shape (1, 1, 5) like conv1d expects
		PackedCollection input = new PackedCollection(shape(1, 1, 5));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0);

		// Create weights with shape (1, 1, 3)
		PackedCollection weights = new PackedCollection(shape(1, 1, 3));
		weights.setMem(0, 0.5, 1.0, 0.5);

		// Mimic conv1d's reshape operation
		CollectionProducer conv = cp(input).reshape(batchSize, 1, inputChannels, paddedLength);
		CollectionProducer filter = cp(weights).reshape(1, outputChannels, inputChannels, kernelSize);

		// Define positions WITHOUT the stride rate (like conv1d currently does)
		TraversalPolicy resultShape = shape(batchSize, outputChannels, 1, outLength);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, 1, outputChannels)
				.withRate(2, inputChannels, 1);
		// NOTE: No .withRate(3, stride, 1) - this is what conv1d does
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, batchSize)
				.withRate(2, inputChannels, 1)
				.withRate(3, kernelSize, outLength);
		TraversalPolicy groupShape = shape(1, 1, inputChannels, kernelSize);

		System.out.println("=== Test WeightedSum With Reshape (NO stride rate) ===");
		System.out.println("resultShape: " + resultShape);
		System.out.println("inputPositions: " + inputPositions);

		CollectionProducer result = weightedSum("conv1dNoStrideTest",
				inputPositions, filterPositions,
				groupShape, conv, filter);

		// Evaluate without Model wrapper
		Evaluable eval = result.reshape(batchSize, outputChannels, outLength)
				.traverseEach().get();
		PackedCollection output = (PackedCollection) eval.evaluate(input);

		System.out.println("Output shape: " + output.getShape());
		System.out.println("Output values (BUG: all should be different but may be the same):");
		for (int i = 0; i < outLength; i++) {
			System.out.println("  out[" + i + "] = " + output.toDouble(i));
		}

		// Expected: [4.0, 6.0, 8.0] but bug gives [4.0, 4.0, 4.0]
		double[] expected = {4.0, 6.0, 8.0};
		boolean allSame = true;
		for (int i = 1; i < outLength; i++) {
			if (Math.abs(output.toDouble(i) - output.toDouble(0)) > 0.001) {
				allSame = false;
			}
		}

		if (allSame) {
			System.out.println("CONFIRMED: Bug reproduced - all outputs are the same!");
			System.out.println("This proves the missing .withRate(3, stride, 1) is the cause.");
		} else {
			System.out.println("Bug NOT reproduced - outputs vary. Checking correctness...");
			for (int i = 0; i < outLength; i++) {
				Assert.assertEquals("Output position " + i, expected[i], output.toDouble(i), 0.001);
			}
		}

		System.out.println("=== Test END ===\n");
	}

	/**
	 * Test 2: Check that different sequence positions get different values.
	 * This specifically tests that the stride/position indexing is correct.
	 * Input: ascending values [1, 2, 3, 4, 5, 6, 7, 8]
	 * Weights: [1.0, 0, 0] (identity-like, just takes first element of kernel window)
	 * Expected: [1, 2, 3, 4, 5, 6]
	 */
	@Test
	public void testPositionVariesAcrossSequence() {
		int batchSize = 1;
		int inputChannels = 1;
		int outputChannels = 1;
		int seqLength = 8;
		int kernelSize = 3;
		int stride = 1;
		int padding = 0;

		// Expected output length = (8 - 3) / 1 + 1 = 6
		int outLength = 6;

		// Create weights: identity-like [1, 0, 0]
		PackedCollection weights = new PackedCollection(shape(1, 1, 3));
		weights.setMem(0, 1.0, 0.0, 0.0);

		// Create input with ascending values
		PackedCollection input = new PackedCollection(shape(1, 1, 8));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);

		// Build conv1d
		Block conv = convolution1d(batchSize, inputChannels, outputChannels,
				seqLength, kernelSize, stride, padding, weights, null);

		Model model = new Model(shape(batchSize, inputChannels, seqLength));
		model.add(conv);
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		System.out.println("=== Test Position Varies Across Sequence ===");
		System.out.println("Input: [1, 2, 3, 4, 5, 6, 7, 8]");
		System.out.println("Weights: [1, 0, 0] (identity-like)");
		System.out.println("Output values:");

		boolean allEqual = true;
		double first = output.toDouble(0);
		for (int i = 0; i < outLength; i++) {
			double val = output.toDouble(i);
			System.out.println("  out[" + i + "] = " + val);
			if (Math.abs(val - first) > 0.001) {
				allEqual = false;
			}
		}

		if (allEqual) {
			System.out.println("ERROR: All outputs are identical! Position indexing is broken.");
		} else {
			System.out.println("OK: Outputs vary across sequence positions.");
		}

		// Verify outputs should be [1, 2, 3, 4, 5, 6]
		double[] expected = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0};
		for (int i = 0; i < outLength; i++) {
			double actual = output.toDouble(i);
			Assert.assertEquals("Position " + i + " should have value " + expected[i],
					expected[i], actual, 0.001);
		}

		System.out.println("=== Test PASSED ===\n");
	}

	/**
	 * Test 3: Multiple output channels.
	 * Tests that each output channel gets the right filter applied.
	 */
	@Test
	public void testMultipleOutputChannels() {
		int batchSize = 1;
		int inputChannels = 1;
		int outputChannels = 2;
		int seqLength = 5;
		int kernelSize = 3;
		int stride = 1;
		int padding = 0;

		int outLength = 3;

		// Weights: [2, 1, 3] - shape [2, 1, 3]
		// Filter 0: [1, 0, 0] - takes first element
		// Filter 1: [0, 0, 1] - takes last element
		PackedCollection weights = new PackedCollection(shape(2, 1, 3));
		weights.setMem(0, 1.0, 0.0, 0.0,   // Filter 0
		                  0.0, 0.0, 1.0);  // Filter 1

		// Input: [1, 2, 3, 4, 5]
		PackedCollection input = new PackedCollection(shape(1, 1, 5));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0);

		Block conv = convolution1d(batchSize, inputChannels, outputChannels,
				seqLength, kernelSize, stride, padding, weights, null);

		Model model = new Model(shape(batchSize, inputChannels, seqLength));
		model.add(conv);
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		System.out.println("=== Test Multiple Output Channels ===");
		System.out.println("Input: [1, 2, 3, 4, 5]");
		System.out.println("Filter 0: [1, 0, 0] (first element)");
		System.out.println("Filter 1: [0, 0, 1] (last element)");
		System.out.println("Output shape: " + output.getShape());

		// Output shape should be [1, 2, 3]
		// Channel 0: [1, 2, 3] (first element of each window)
		// Channel 1: [3, 4, 5] (last element of each window)
		System.out.println("Channel 0 output:");
		for (int i = 0; i < outLength; i++) {
			System.out.println("  out[0," + i + "] = " + output.toDouble(i));
		}
		System.out.println("Channel 1 output:");
		for (int i = 0; i < outLength; i++) {
			System.out.println("  out[1," + i + "] = " + output.toDouble(outLength + i));
		}

		// Verify channel 0: [1, 2, 3]
		Assert.assertEquals("Channel 0, pos 0", 1.0, output.toDouble(0), 0.001);
		Assert.assertEquals("Channel 0, pos 1", 2.0, output.toDouble(1), 0.001);
		Assert.assertEquals("Channel 0, pos 2", 3.0, output.toDouble(2), 0.001);

		// Verify channel 1: [3, 4, 5]
		Assert.assertEquals("Channel 1, pos 0", 3.0, output.toDouble(3), 0.001);
		Assert.assertEquals("Channel 1, pos 1", 4.0, output.toDouble(4), 0.001);
		Assert.assertEquals("Channel 1, pos 2", 5.0, output.toDouble(5), 0.001);

		System.out.println("=== Test PASSED ===\n");
	}

	/**
	 * Test 4: Stride > 1 (downsampling).
	 * Tests that stride correctly skips input positions.
	 */
	@Test
	public void testStride2() {
		int batchSize = 1;
		int inputChannels = 1;
		int outputChannels = 1;
		int seqLength = 8;
		int kernelSize = 3;
		int stride = 2;
		int padding = 0;

		// Output length = (8 - 3) / 2 + 1 = 3
		int outLength = 3;

		// Weights: [1, 0, 0] - takes first element
		PackedCollection weights = new PackedCollection(shape(1, 1, 3));
		weights.setMem(0, 1.0, 0.0, 0.0);

		// Input: [1, 2, 3, 4, 5, 6, 7, 8]
		PackedCollection input = new PackedCollection(shape(1, 1, 8));
		input.setMem(0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);

		Block conv = convolution1d(batchSize, inputChannels, outputChannels,
				seqLength, kernelSize, stride, padding, weights, null);

		Model model = new Model(shape(batchSize, inputChannels, seqLength));
		model.add(conv);
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		System.out.println("=== Test Stride 2 ===");
		System.out.println("Input: [1, 2, 3, 4, 5, 6, 7, 8]");
		System.out.println("Weights: [1, 0, 0]");
		System.out.println("Stride: 2");
		System.out.println("Output values:");

		// With stride=2 and weights [1,0,0], we expect:
		// out[0] = input[0] = 1
		// out[1] = input[2] = 3
		// out[2] = input[4] = 5
		double[] expected = {1.0, 3.0, 5.0};
		for (int i = 0; i < outLength; i++) {
			double val = output.toDouble(i);
			System.out.println("  out[" + i + "] = " + val + " (expected " + expected[i] + ")");
		}

		for (int i = 0; i < outLength; i++) {
			Assert.assertEquals("Position " + i, expected[i], output.toDouble(i), 0.001);
		}

		System.out.println("=== Test PASSED ===\n");
	}

	/**
	 * Test 5: Multiple input channels.
	 * Verifies that all input channels are summed correctly.
	 */
	@Test
	public void testMultipleInputChannels() {
		int batchSize = 1;
		int inputChannels = 2;
		int outputChannels = 1;
		int seqLength = 5;
		int kernelSize = 3;
		int stride = 1;
		int padding = 0;

		int outLength = 3;

		// Weights: shape [1, 2, 3]
		// Channel 0 filter: [1, 0, 0]
		// Channel 1 filter: [0, 0, 1]
		PackedCollection weights = new PackedCollection(shape(1, 2, 3));
		weights.setMem(0,
				1.0, 0.0, 0.0,   // Filter for input channel 0
				0.0, 0.0, 1.0);  // Filter for input channel 1

		// Input: shape [1, 2, 5]
		// Channel 0: [1, 2, 3, 4, 5]
		// Channel 1: [10, 20, 30, 40, 50]
		PackedCollection input = new PackedCollection(shape(1, 2, 5));
		input.setMem(0,
				1.0, 2.0, 3.0, 4.0, 5.0,      // Channel 0
				10.0, 20.0, 30.0, 40.0, 50.0); // Channel 1

		Block conv = convolution1d(batchSize, inputChannels, outputChannels,
				seqLength, kernelSize, stride, padding, weights, null);

		Model model = new Model(shape(batchSize, inputChannels, seqLength));
		model.add(conv);
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		System.out.println("=== Test Multiple Input Channels ===");
		System.out.println("Channel 0 input: [1, 2, 3, 4, 5]");
		System.out.println("Channel 1 input: [10, 20, 30, 40, 50]");
		System.out.println("Channel 0 filter: [1, 0, 0]");
		System.out.println("Channel 1 filter: [0, 0, 1]");
		System.out.println("Output values:");

		// Expected: sum across channels
		// out[0] = ch0[0]*1 + ch1[2]*1 = 1 + 30 = 31
		// out[1] = ch0[1]*1 + ch1[3]*1 = 2 + 40 = 42
		// out[2] = ch0[2]*1 + ch1[4]*1 = 3 + 50 = 53
		double[] expected = {31.0, 42.0, 53.0};
		for (int i = 0; i < outLength; i++) {
			double val = output.toDouble(i);
			System.out.println("  out[" + i + "] = " + val + " (expected " + expected[i] + ")");
		}

		for (int i = 0; i < outLength; i++) {
			Assert.assertEquals("Position " + i, expected[i], output.toDouble(i), 0.001);
		}

		System.out.println("=== Test PASSED ===\n");
	}
}
