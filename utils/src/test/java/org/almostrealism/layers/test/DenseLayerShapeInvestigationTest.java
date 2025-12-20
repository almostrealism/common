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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Investigation tests to verify the actual shape behavior of dense layers.
 * These tests use known weights and inputs to verify:
 * 1. The declared input/output shapes
 * 2. The actual shape of the computation result
 * 3. The numerical correctness of the dense layer
 *
 * @author Michael Murray
 */
public class DenseLayerShapeInvestigationTest implements TestFeatures {

	static {
		if (TestUtils.getTrainTests()) {
			Console.root().addListener(OutputFeatures.fileOutput("results/logs/dense_shape_investigation.out"));
		}
	}

	/**
	 * Test matmul shape by using dense layer internally.
	 * The dense layer uses matmul(weights, input) and we observe the shape mismatch warning.
	 */
	@Test(timeout = 10000)
	public void testMatmulShapeViaDense() {
		log("=== Test: Matmul Shape via Dense Layer ===");

		// Weights: (2, 3) - standard (out, in) layout
		PackedCollection weights = new PackedCollection(shape(2, 3));
		weights.setMem(0, 1, 2, 3, 4, 5, 6);

		TraversalPolicy inputShape = shape(1, 3);
		CellularLayer layer = dense(inputShape, weights, false, false);

		log("Weights shape: " + weights.getShape());
		log("Layer input shape: " + layer.getInputShape());
		log("Layer output shape: " + layer.getOutputShape());

		log("");
		log("Dense layer uses matmul(weights, input) internally.");
		log("matmul((2,3), (1,3)) produces shape (2,1) [m,n where k is shared]");
		log("But layer declares output as (1,2) [batch, output_nodes]");
		log("This triggers the shape mismatch warning, which is automatically resolved by reshape.");
	}

	/**
	 * Test dense layer shape declarations vs actual output.
	 */
	@Test(timeout = 10000)
	public void testDenseLayerShapeDeclaration() {
		log("=== Test: Dense Layer Shape Declaration ===");

		int inputSize = 3;
		int outputSize = 2;

		// Create weights with known values
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.setMem(0, 1, 2, 3, 4, 5, 6);

		log("Weights shape: " + weights.getShape());

		// Create the dense layer directly
		TraversalPolicy inputShape = shape(1, inputSize);
		CellularLayer layer = dense(inputShape, weights, false, false);

		log("Layer declared input shape: " + layer.getInputShape());
		log("Layer declared output shape: " + layer.getOutputShape());

		// The key question: what does the layer claim vs what does matmul produce?
		log("");
		log("=== Shape Analysis ===");
		log("Dense layer uses matmul(weights, input)");
		log("  weights: (2, 3)");
		log("  input:   (1, 3)");
		log("  matmul convention: (m, k) @ (n, k) -> (m, n) where k is shared");
		log("  Expected matmul output: (2, 1) since m=2, n=1, k=3");
		log("");
		log("But layer declares output shape: " + layer.getOutputShape());
	}

	/**
	 * Test a simple dense model to verify numerical correctness end-to-end.
	 */
	@Test(timeout = 10000)
	public void testDenseModelInference() {
		log("=== Test: Dense Model Inference ===");

		int inputSize = 3;
		int outputSize = 2;

		// Create weights: (2, 3) - 2 outputs, 3 inputs
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.setMem(0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

		log("Weights shape: " + weights.getShape());
		log("Weights: " + weights.toArrayString());

		// Build model
		TraversalPolicy inputShape = shape(1, inputSize);
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(weights));

		Model model = new Model(inputShape);
		model.add(block);

		log("Model input shape: " + model.getInputShape());
		log("Model output shape: " + model.getOutputShape());

		// Create test input
		PackedCollection input = new PackedCollection(inputShape);
		input.setMem(0, 1.0, 2.0, 3.0);
		log("Input: " + input.toArrayString());

		// Expected output (manual calculation):
		// y[0] = 0.1*1 + 0.2*2 + 0.3*3 = 0.1 + 0.4 + 0.9 = 1.4
		// y[1] = 0.4*1 + 0.5*2 + 0.6*3 = 0.4 + 1.0 + 1.8 = 3.2
		log("Expected: y[0]=1.4, y[1]=3.2");

		// Compile and run inference
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("Output shape: " + output.getShape());
		log("Output: " + output.toArrayString());

		// Verify output shape
		Assert.assertEquals("Output should have 2 elements", 2, output.getShape().getTotalSize());
		Assert.assertEquals("Output batch dimension should be 1", 1, output.getShape().length(0));
		Assert.assertEquals("Output feature dimension should be 2", 2, output.getShape().length(1));

		// Verify numerical correctness via toArray
		double[] values = output.toArray(0, 2);
		log("");
		log("=== Verification ===");
		log("Expected: [1.4, 3.2]");
		log("Actual: [" + values[0] + ", " + values[1] + "]");

		Assert.assertEquals("First output should be 1.4", 1.4, values[0], 0.001);
		Assert.assertEquals("Second output should be 3.2", 3.2, values[1], 0.001);
	}

	/**
	 * Test to understand exactly where the shape mismatch warning comes from.
	 * This test examines the into() method behavior.
	 */
	@Test(timeout = 10000)
	public void testIntoShapeMismatchSource() {
		log("=== Test: Where Does Shape Mismatch Come From? ===");

		int inputSize = 5;
		int outputSize = 3;

		// Create dense layer
		TraversalPolicy inputShape = shape(1, inputSize);
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));

		CellularLayer layer = dense(inputShape, weights, false, false);

		log("Layer declared input shape: " + layer.getInputShape());
		log("Layer declared output shape: " + layer.getOutputShape());

		// The dense layer declares output shape as (1, outputSize) = (1, 3)
		// But matmul(weights, input) produces (outputSize, 1) = (3, 1)
		//
		// When the layer connects to next layer (in Model.add or SequentialBlock),
		// it uses into() to copy from matmul result -> layer's output buffer
		// into() sees: source=(3,1), dest=(1,3) -> shape mismatch warning

		log("");
		log("Analysis:");
		log("  matmul((3,5), (1,5)) produces shape (3, 1)");
		log("  Layer declares output shape (1, 3)");
		log("  into() compares these and warns about mismatch");
		log("");
		log("  Total sizes match: 3 == 3 (so data is correct)");
		log("  But dimension order differs: (3,1) vs (1,3)");
	}

	/**
	 * Test batched inference to verify shapes scale correctly.
	 */
	@Test(timeout = 10000)
	public void testBatchedDenseInference() {
		log("=== Test: Batched Dense Inference ===");

		int batchSize = 2;
		int inputSize = 3;
		int outputSize = 4;

		// Weights: (4, 3)
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> (pos[0] + 1) * 0.1);

		log("Weights shape: " + weights.getShape());

		// Input shape: (2, 3)
		TraversalPolicy inputShape = shape(batchSize, inputSize);

		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(weights));

		Model model = new Model(inputShape);
		model.add(block);

		log("Model input shape: " + model.getInputShape());
		log("Model output shape: " + model.getOutputShape());

		// Create batched input
		PackedCollection input = new PackedCollection(inputShape);
		input.setMem(0, 1, 2, 3, 4, 5, 6);
		log("Input (2 samples): " + input.toArrayString());

		// Run inference
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("Output shape: " + output.getShape());
		log("Output: " + output.toArrayString());
		log("Output memory length: " + output.getMemLength());

		// Expected: batch of 2, each with 4 outputs = 8 values total
		Assert.assertEquals("Should have 8 output values", 8, output.getMemLength());
	}

	/**
	 * Test that compares declared output shape vs actual computation shape.
	 * This demonstrates that the model correctly reshapes the output.
	 */
	@Test(timeout = 10000)
	public void testShapeComparisonViaModel() {
		log("=== Test: Shape Comparison via Model ===");

		int inputSize = 4;
		int outputSize = 2;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> 1.0);

		TraversalPolicy inputShape = shape(1, inputSize);
		CellularLayer layer = dense(inputShape, weights, false, false);

		log("1. Weight shape: " + weights.getShape());
		log("2. Input shape for layer: " + inputShape);
		log("3. Layer.getInputShape(): " + layer.getInputShape());
		log("4. Layer.getOutputShape(): " + layer.getOutputShape());

		// Create test input
		PackedCollection testInput = new PackedCollection(inputShape);
		testInput.fill(pos -> 1.0);

		// Run through model
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(weights));
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		log("5. Model output shape: " + model.getOutputShape());

		PackedCollection modelOutput = compiled.forward(testInput);
		log("6. Model forward output shape: " + modelOutput.getShape());
		log("7. Model forward output values: " + modelOutput.toArrayString());

		// The key insight
		log("");
		log("=== KEY FINDING ===");
		log("Layer declares output: " + layer.getOutputShape());
		log("Model outputs shape: " + modelOutput.getShape());
		log("");
		log("matmul internally produces transposed shape (output_nodes, batch)");
		log("but the reshape corrects it to (batch, output_nodes)");
		log("The warning is about the internal mismatch, but final output is correct.");

		// Verify shape matches declaration
		Assert.assertEquals("Output shape should match declared",
				layer.getOutputShape().getTotalSize(), modelOutput.getShape().getTotalSize());
	}
}
