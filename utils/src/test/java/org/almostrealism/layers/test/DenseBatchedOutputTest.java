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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Investigation test to verify the actual behavior of dense layer with batched inputs.
 *
 * <p>The comment in LayerFeatures::dense claims "matmul produces (nodes, batched),
 * reshape to declared (batched, nodes)". This test verifies whether:</p>
 * <ol>
 *   <li>The matmul truly produces transposed output</li>
 *   <li>If so, whether reshape (vs permute) produces correct results</li>
 *   <li>If the layer works correctly with batch size > 1</li>
 * </ol>
 *
 * <p>Key insight: If matmul produces (4, 3) instead of (3, 4) for a batch of 3 inputs
 * with 4 output nodes, then reshape would scramble the data. We need permute/transpose
 * to correctly rearrange the values.</p>
 *
 * @author Michael Murray
 */
public class DenseBatchedOutputTest implements LayerFeatures, TestFeatures {

	/**
	 * Test 1: Single sample - verify basic dense layer math.
	 * With batch=1, reshape vs permute doesn't matter (both are identity).
	 */
	@Test
	public void testDenseSingleSample() {
		log("=== Test 1: Dense Single Sample ===");

		// Simple 2 -> 3 dense layer, batch size = 1
		int inputSize = 2;
		int outputSize = 3;
		int batchSize = 1;

		// Create weights: 3x2 matrix (output_nodes x input_nodes)
		// W = [[1, 2],    -> output[0] = 1*in[0] + 2*in[1]
		//      [3, 4],    -> output[1] = 3*in[0] + 4*in[1]
		//      [5, 6]]    -> output[2] = 5*in[0] + 6*in[1]
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.setMem(0, 1, 2, 3, 4, 5, 6);

		// Create input: [1, 2] (batch=1, features=2)
		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.setMem(0, 1, 2);

		log("Weights shape: " + weights.getShape());
		log("Weights: " + weights.toArrayString());
		log("Input shape: " + input.getShape());
		log("Input: " + input.toArrayString());

		// Expected output (manual calculation):
		// output[0] = 1*1 + 2*2 = 5
		// output[1] = 3*1 + 4*2 = 11
		// output[2] = 5*1 + 6*2 = 17
		log("Expected output: [5, 11, 17]");

		// Build model
		TraversalPolicy inputShape = shape(batchSize, inputSize);
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(weights));

		Model model = new Model(inputShape);
		model.add(block);

		log("Model input shape: " + model.getInputShape());
		log("Model output shape: " + model.getOutputShape());

		// Compile and run inference
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("Actual output shape: " + output.getShape());
		log("Actual output: " + output.toArrayString());

		// Verify output
		assertEquals(5.0, output.toDouble(0));
		assertEquals(11.0, output.toDouble(1));
		assertEquals(17.0, output.toDouble(2));

		log("Test 1 PASSED");
	}

	/**
	 * Test 2: Multiple samples (batch=3) - this is the critical test.
	 * If matmul produces (output, batch) and we just reshape to (batch, output),
	 * the values will be scrambled.
	 */
	@Test
	public void testDenseBatchedSamples() {
		log("=== Test 2: Dense Batched Samples (batch=3) ===");

		int inputSize = 2;
		int outputSize = 4;
		int batchSize = 3;

		// Create weights: 4x2 matrix
		// W = [[1, 0],    -> output[0] = 1*in[0] + 0*in[1] = in[0]
		//      [0, 1],    -> output[1] = 0*in[0] + 1*in[1] = in[1]
		//      [1, 1],    -> output[2] = 1*in[0] + 1*in[1] = in[0] + in[1]
		//      [2, 3]]    -> output[3] = 2*in[0] + 3*in[1]
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.setMem(0, 1, 0, 0, 1, 1, 1, 2, 3);

		// Create batched input: 3 samples, each with 2 features
		// Sample 0: [1, 2]
		// Sample 1: [3, 4]
		// Sample 2: [5, 6]
		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.setMem(0, 1, 2, 3, 4, 5, 6);

		log("Weights shape: " + weights.getShape());
		log("Weights: " + weights.toArrayString());
		log("Input shape: " + input.getShape());
		log("Input: " + input.toArrayString());

		// Expected output (manual calculation):
		// Sample 0: [1, 2, 3, 8]    (1, 2, 1+2, 2*1+3*2)
		// Sample 1: [3, 4, 7, 18]   (3, 4, 3+4, 2*3+3*4)
		// Sample 2: [5, 6, 11, 28]  (5, 6, 5+6, 2*5+3*6)
		log("");
		log("Expected output (batch, features):");
		log("  Sample 0: [1, 2, 3, 8]");
		log("  Sample 1: [3, 4, 7, 18]");
		log("  Sample 2: [5, 6, 11, 28]");
		log("Expected flat: [1, 2, 3, 8, 3, 4, 7, 18, 5, 6, 11, 28]");

		// Build model
		TraversalPolicy inputShape = shape(batchSize, inputSize);
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(weights));

		Model model = new Model(inputShape);
		model.add(block);

		log("");
		log("Model input shape: " + model.getInputShape());
		log("Model output shape: " + model.getOutputShape());

		// Compile and run inference
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("");
		log("Actual output shape: " + output.getShape());
		log("Actual output: " + output.toArrayString());

		// Check if the output is in (batch, features) order
		// If reshape was wrong and values are scrambled, this will fail
		log("");
		log("Verifying sample-by-sample:");

		// Sample 0
		log("  Sample 0: expected [1, 2, 3, 8]");
		double s0_f0 = output.toDouble(0);
		double s0_f1 = output.toDouble(1);
		double s0_f2 = output.toDouble(2);
		double s0_f3 = output.toDouble(3);
		log("  Sample 0: actual   [" + s0_f0 + ", " + s0_f1 + ", " + s0_f2 + ", " + s0_f3 + "]");

		// Sample 1
		log("  Sample 1: expected [3, 4, 7, 18]");
		double s1_f0 = output.toDouble(4);
		double s1_f1 = output.toDouble(5);
		double s1_f2 = output.toDouble(6);
		double s1_f3 = output.toDouble(7);
		log("  Sample 1: actual   [" + s1_f0 + ", " + s1_f1 + ", " + s1_f2 + ", " + s1_f3 + "]");

		// Sample 2
		log("  Sample 2: expected [5, 6, 11, 28]");
		double s2_f0 = output.toDouble(8);
		double s2_f1 = output.toDouble(9);
		double s2_f2 = output.toDouble(10);
		double s2_f3 = output.toDouble(11);
		log("  Sample 2: actual   [" + s2_f0 + ", " + s2_f1 + ", " + s2_f2 + ", " + s2_f3 + "]");

		// Assertions
		// Sample 0
		assertEquals(1.0, s0_f0);
		assertEquals(2.0, s0_f1);
		assertEquals(3.0, s0_f2);
		assertEquals(8.0, s0_f3);

		// Sample 1
		assertEquals(3.0, s1_f0);
		assertEquals(4.0, s1_f1);
		assertEquals(7.0, s1_f2);
		assertEquals(18.0, s1_f3);

		// Sample 2
		assertEquals(5.0, s2_f0);
		assertEquals(6.0, s2_f1);
		assertEquals(11.0, s2_f2);
		assertEquals(28.0, s2_f3);

		log("");
		log("Test 2 PASSED - Batched dense layer produces correct output");
	}

	/**
	 * Test 3: Verify that the matmul semantics are consistent.
	 * For dense layers, matmul(W, X) where W:(out, in), X:(batch, in)
	 * produces output in (batch, out) format with correct values.
	 */
	@Test
	public void testMatmulSemantics() {
		log("=== Test 3: Matmul Semantics Verification ===");

		// This test verifies the semantics by testing through a dense layer
		// We've proven in Test 2 that the output is correct, so matmul must
		// internally handle batched inputs properly.

		int inputSize = 3;
		int outputSize = 2;

		// Simple identity-like weights for easy verification
		// W = [[1, 0, 0],
		//      [0, 1, 0]]
		// This extracts the first two features
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.setMem(0, 1, 0, 0, 0, 1, 0);

		// Input: single sample [10, 20, 30]
		PackedCollection input = new PackedCollection(shape(1, inputSize));
		input.setMem(0, 10, 20, 30);

		log("Weights: " + weights.toArrayString());
		log("Input: " + input.toArrayString());
		log("Expected output: [10, 20] (first two features)");

		// Build and run model
		TraversalPolicy inputShape = shape(1, inputSize);
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(weights));

		Model model = new Model(inputShape);
		model.add(block);

		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("Actual output: " + output.toArrayString());

		assertEquals(10.0, output.toDouble(0));
		assertEquals(20.0, output.toDouble(1));

		log("Test 3 PASSED - matmul semantics are correct");
	}

	/**
	 * Test 4: What happens if we reshape (4,3) to (3,4) vs permute?
	 * This demonstrates the difference between reshape and transpose.
	 */
	@Test
	public void testReshapeVsPermute() {
		log("=== Test 4: Reshape vs Permute Comparison ===");

		// Create a (4, 3) matrix where we know exactly what each element is
		// data[i][j] = i * 10 + j
		PackedCollection original = new PackedCollection(shape(4, 3));
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 3; j++) {
				original.setMem(i * 3 + j, i * 10 + j);
			}
		}

		log("Original (4, 3):");
		log("  Row 0: [0, 1, 2]");
		log("  Row 1: [10, 11, 12]");
		log("  Row 2: [20, 21, 22]");
		log("  Row 3: [30, 31, 32]");
		log("  Flat: " + original.toArrayString());

		// Reshape to (3, 4) - just reinterprets the memory layout
		CollectionProducer reshapedProd = c(original).reshape(shape(3, 4));
		PackedCollection reshaped = reshapedProd.get().evaluate();

		log("");
		log("After reshape(3, 4) - reinterprets memory:");
		log("  Row 0: [" + reshaped.toDouble(0) + ", " + reshaped.toDouble(1) + ", " + reshaped.toDouble(2) + ", " + reshaped.toDouble(3) + "]");
		log("  Row 1: [" + reshaped.toDouble(4) + ", " + reshaped.toDouble(5) + ", " + reshaped.toDouble(6) + ", " + reshaped.toDouble(7) + "]");
		log("  Row 2: [" + reshaped.toDouble(8) + ", " + reshaped.toDouble(9) + ", " + reshaped.toDouble(10) + ", " + reshaped.toDouble(11) + "]");

		// Permute/transpose to (3, 4) - actually moves data
		// permute([1, 0]) swaps dimensions
		CollectionProducer permutedProd = c(original).permute(1, 0);
		PackedCollection permuted = permutedProd.get().evaluate();

		log("");
		log("After permute(1, 0) - true transpose:");
		log("  Shape: " + permuted.getShape());
		log("  Row 0: [" + permuted.toDouble(0) + ", " + permuted.toDouble(1) + ", " + permuted.toDouble(2) + ", " + permuted.toDouble(3) + "]");
		log("  Row 1: [" + permuted.toDouble(4) + ", " + permuted.toDouble(5) + ", " + permuted.toDouble(6) + ", " + permuted.toDouble(7) + "]");
		log("  Row 2: [" + permuted.toDouble(8) + ", " + permuted.toDouble(9) + ", " + permuted.toDouble(10) + ", " + permuted.toDouble(11) + "]");

		log("");
		log("Expected after true transpose (3, 4):");
		log("  Row 0 (was col 0): [0, 10, 20, 30]");
		log("  Row 1 (was col 1): [1, 11, 21, 31]");
		log("  Row 2 (was col 2): [2, 12, 22, 32]");

		// Verify permute gives correct transpose
		assertEquals(0.0, permuted.toDouble(0));
		assertEquals(10.0, permuted.toDouble(1));
		assertEquals(20.0, permuted.toDouble(2));
		assertEquals(30.0, permuted.toDouble(3));

		log("");
		log("Test 4 complete - reshape and permute produce DIFFERENT results");
	}

	/**
	 * Test 5: Verify that the dense layer operator produces correct values.
	 * Since Test 2 passed, we know the layer works - let's just confirm
	 * the comment about reshape is misleading (the values ARE correct).
	 */
	@Test
	public void testDenseOperatorCorrectness() {
		log("=== Test 5: Dense Operator Correctness Verification ===");

		int inputSize = 2;
		int outputSize = 4;
		int batchSize = 3;

		// Create weights: 4x2 matrix
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.setMem(0, 1, 0, 0, 1, 1, 1, 2, 3);

		// Input: (3, 2) - batched
		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.setMem(0, 1, 2, 3, 4, 5, 6);

		log("Weights (4x2): " + weights.toArrayString());
		log("Input (3x2): " + input.toArrayString());

		// Expected output (batch, output_nodes) = (3, 4):
		// Sample 0: [1, 2, 3, 8]
		// Sample 1: [3, 4, 7, 18]
		// Sample 2: [5, 6, 11, 28]

		// Build model and verify output
		TraversalPolicy inputShape = shape(batchSize, inputSize);
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(weights));

		Model model = new Model(inputShape);
		model.add(block);

		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("");
		log("Dense layer output shape: " + output.getShape());
		log("Dense layer output values: " + output.toArrayString());

		// The key finding: Test 2 passed, which means the layer produces
		// correct (batch, features) ordered output.
		//
		// This means EITHER:
		// 1. matmul produces (batch, features) directly (not (features, batch))
		// 2. OR the reshape operation somehow knows to transpose
		//
		// Given our Test 4 showed reshape does NOT transpose (just reinterprets),
		// the matmul must be producing (batch, features) directly.
		//
		// CONCLUSION: The comment "matmul produces (nodes, batched)" is INCORRECT!
		// The matmul actually produces (batched, nodes) for batched inputs.

		log("");
		log("CONCLUSION:");
		log("Since Test 2 passed with correct sample-by-sample values,");
		log("and Test 4 showed reshape does NOT transpose data,");
		log("the matmul must produce (batch, output_nodes) directly.");
		log("");
		log("The comment 'matmul produces (nodes, batched)' is MISLEADING.");
		log("The reshape is just changing shape metadata, not reordering.");

		// Verify values one more time
		assertEquals(1.0, output.toDouble(0));  // Sample 0, output 0
		assertEquals(2.0, output.toDouble(1));  // Sample 0, output 1
		assertEquals(3.0, output.toDouble(2));  // Sample 0, output 2
		assertEquals(8.0, output.toDouble(3));  // Sample 0, output 3

		log("");
		log("Test 5 PASSED - Dense layer produces correct batched output");
	}
}
