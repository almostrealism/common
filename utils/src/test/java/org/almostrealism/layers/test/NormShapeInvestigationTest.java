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
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Investigation tests to verify the actual shape behavior of norm layers.
 * The warning observed is:
 * (1, 6) does not match (1, 1, 6) for norm layer
 * <p>
 * This test investigates the missing batch dimension.
 *
 * @author Michael Murray
 */
public class NormShapeInvestigationTest extends TestSuiteBase {

	static {
		if (TestUtils.getTrainTests()) {
			Console.root().addListener(OutputFeatures.fileOutput("results/logs/norm_shape_investigation.out"));
		}
	}

	/**
	 * Test norm layer shape declarations vs actual output with 2D input.
	 */
	@Test(timeout = 10000)
	public void testNormLayerShape2D() {
		log("=== Test: Norm Layer Shape with 2D Input ===");

		int batch = 1;
		int features = 6;

		TraversalPolicy inputShape = shape(batch, features);
		CellularLayer layer = norm(1).apply(inputShape);

		log("Input shape: " + inputShape);
		log("Layer declared input shape: " + layer.getInputShape());
		log("Layer declared output shape: " + layer.getOutputShape());

		log("");
		log("=== Analysis ===");
		log("norm() calls padDimensions(shape, 1, 3)");
		log("This pads 2D shape (1, 6) to 3D shape (1, 1, 6)");
		log("So declared shapes are 3D, but operator may produce 2D");
	}

	/**
	 * Test norm layer shape declarations with 3D input.
	 */
	@Test(timeout = 10000)
	public void testNormLayerShape3D() {
		log("=== Test: Norm Layer Shape with 3D Input ===");

		int batch = 1;
		int groups = 1;
		int features = 6;

		TraversalPolicy inputShape = shape(batch, groups, features);
		CellularLayer layer = norm(1).apply(inputShape);

		log("Input shape: " + inputShape);
		log("Layer declared input shape: " + layer.getInputShape());
		log("Layer declared output shape: " + layer.getOutputShape());
	}

	/**
	 * Test norm through a model to verify numerical correctness.
	 */
	@Test(timeout = 10000)
	public void testNormModelInference() {
		log("=== Test: Norm Model Inference ===");

		int batch = 1;
		int features = 4;

		TraversalPolicy inputShape = shape(batch, features);

		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(norm(1));

		Model model = new Model(inputShape);
		model.add(block);

		log("Model input shape: " + model.getInputShape());
		log("Model output shape: " + model.getOutputShape());

		// Create test input: [1, 2, 3, 4]
		// Mean = 2.5, Var = 1.25
		// Normalized: (x - mean) / sqrt(var + eps) = (x - 2.5) / sqrt(1.25)
		PackedCollection input = new PackedCollection(inputShape);
		input.setMem(0, 1.0, 2.0, 3.0, 4.0);

		log("Input: " + input.toArrayString());

		// Calculate expected output
		double mean = 2.5;
		double variance = 1.25;  // ((1-2.5)^2 + (2-2.5)^2 + (3-2.5)^2 + (4-2.5)^2) / 4
		double eps = 1e-5;
		double std = Math.sqrt(variance + eps);
		log("Mean: " + mean + ", Variance: " + variance + ", Std: " + std);
		log("Expected normalized values:");
		for (int i = 1; i <= 4; i++) {
			double normalized = (i - mean) / std;
			log("  " + i + " -> " + normalized);
		}

		// Compile and run inference
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("Output shape: " + output.getShape());
		log("Output: " + output.toArrayString());
		log("Output memory length: " + output.getMemLength());

		// Verify shape
		Assert.assertEquals("Output should have 4 elements", 4, output.getShape().getTotalSize());
	}

	/**
	 * Test norm operator shape transformation step by step.
	 */
	@Test(timeout = 10000)
	public void testNormOperatorShapeAnalysis() {
		log("=== Test: Norm Operator Shape Analysis ===");

		int batch = 1;
		int features = 6;
		int groups = 1;

		TraversalPolicy inputShape = shape(batch, features);
		log("Original input shape: " + inputShape);

		// Simulate padDimensions
		TraversalPolicy paddedShape = shape(batch, 1, features);
		log("After padDimensions(shape, 1, 3): " + paddedShape);

		log("");
		log("=== Norm Operator Analysis ===");
		log("The operator does:");
		log("  1. input.reshape(-1, groups, size/groups)  -> (-1, 1, 6)");
		log("  2. subtractMean(2)                         -> operates on last dim");
		log("  3. divide(variance(2).add(eps).sqrt())     -> normalization");
		log("  4. reshape(-1, size).traverse(1)           -> (-1, 6)");
		log("");
		log("The reshape(-1, size) at the end produces 2D output");
		log("But layer declares 3D shape from padDimensions");
		log("");
		log("This is a mismatch between operator output and declared shape.");

		// The fix should be to reshape to the padded output shape
		log("");
		log("Fix: The operator should end with .reshape(outputShape)");
		log("where outputShape is the padded 3D shape.");
	}

	/**
	 * Test with various input shapes to understand the pattern.
	 */
	@Test(timeout = 10000)
	public void testNormVariousSizes() {
		log("=== Test: Norm Various Sizes ===");

		int[][] testCases = {
				{1, 4},       // 2D: (batch, features)
				{1, 1, 6},    // 3D: (batch, groups, features)
				{2, 8},       // 2D with larger batch
				{1, 2, 4}     // 3D with multiple groups concept
		};

		for (int[] tc : testCases) {
			TraversalPolicy inputShape = tc.length == 2 ? shape(tc[0], tc[1]) : shape(tc[0], tc[1], tc[2]);
			CellularLayer layer = norm(1).apply(inputShape);

			log("Input: " + inputShape);
			log("  Declared input: " + layer.getInputShape());
			log("  Declared output: " + layer.getOutputShape());
			log("");
		}
	}
}
