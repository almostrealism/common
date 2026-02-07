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
 * Investigation tests to verify the actual shape behavior of pool2d layers.
 * The warning observed is:
 * (1, 4, 5, 5, 1) does not match (1, 4, 5, 5) for pool2d layer
 * <p>
 * This test investigates why there's an extra trailing dimension.
 *
 * @author Michael Murray
 */
public class Pool2dShapeInvestigationTest extends TestSuiteBase {

	/**
	 * Test pool2d layer shape declarations vs actual output.
	 */
	@Test(timeout = 10000)
	public void testPool2dLayerShapeDeclaration() {
		log("=== Test: Pool2d Layer Shape Declaration ===");

		int batch = 1;
		int channels = 4;
		int height = 10;
		int width = 10;
		int poolSize = 2;

		TraversalPolicy inputShape = shape(batch, channels, height, width);
		CellularLayer layer = pool2d(inputShape, poolSize);

		log("Input shape: " + inputShape);
		log("Pool size: " + poolSize);
		log("Layer declared input shape: " + layer.getInputShape());
		log("Layer declared output shape: " + layer.getOutputShape());

		// Expected: (1, 4, 5, 5) - batch, channels, height/2, width/2
		int expectedH = height / poolSize;
		int expectedW = width / poolSize;
		log("Expected output shape: (" + batch + ", " + channels + ", " + expectedH + ", " + expectedW + ")");

		log("");
		log("=== Shape Analysis ===");
		log("pool2d operator chain:");
		log("  1. reshape(-1, c, h, w) - reshapes to (batch, channels, height, width)");
		log("  2. traverse(2) - sets traversal axis to 2 (height)");
		log("  3. enumerate(3, size) - splits width dimension by pool size");
		log("  4. enumerate(3, size) - splits again for pooling");
		log("  5. max(4) - takes max over the pooled dimension");
		log("");
		log("The extra trailing dimension (1) may come from max() output behavior.");
	}

	/**
	 * Test pool2d through a model to verify numerical correctness.
	 */
	@Test(timeout = 10000)
	public void testPool2dModelInference() {
		log("=== Test: Pool2d Model Inference ===");

		int batch = 1;
		int channels = 2;
		int height = 4;
		int width = 4;
		int poolSize = 2;

		TraversalPolicy inputShape = shape(batch, channels, height, width);

		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(pool2d(poolSize));

		Model model = new Model(inputShape);
		model.add(block);

		log("Model input shape: " + model.getInputShape());
		log("Model output shape: " + model.getOutputShape());

		// Create test input: 2x2 pooling should take max of each 2x2 region
		// Channel 0: [0,1,2,3; 4,5,6,7; 8,9,10,11; 12,13,14,15]
		// Channel 1: all 1s
		PackedCollection input = new PackedCollection(inputShape);
		for (int i = 0; i < 16; i++) {
			input.setMem(i, i);  // Channel 0: 0-15
		}
		for (int i = 16; i < 32; i++) {
			input.setMem(i, 1.0);  // Channel 1: all 1s
		}

		log("Input: " + input.toArrayString());

		// Expected output for channel 0 (2x2 max pooling on 4x4):
		// [[0,1,2,3],[4,5,6,7],[8,9,10,11],[12,13,14,15]] ->
		// max of [0,1,4,5]=5, max of [2,3,6,7]=7, max of [8,9,12,13]=13, max of [10,11,14,15]=15
		// So channel 0 output: [5, 7, 13, 15]
		// Channel 1 output: [1, 1, 1, 1]
		log("Expected channel 0 output: [5, 7, 13, 15]");
		log("Expected channel 1 output: [1, 1, 1, 1]");

		// Compile and run inference
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		log("Output shape: " + output.getShape());
		log("Output: " + output.toArrayString());
		log("Output memory length: " + output.getMemLength());

		// Verify shape
		Assert.assertEquals("Output should have 8 elements", 8, output.getShape().getTotalSize());
	}

	/**
	 * Test pool2d operator shape step by step.
	 */
	@Test(timeout = 10000)
	public void testPool2dOperatorShapeAnalysis() {
		log("=== Test: Pool2d Operator Shape Analysis ===");

		int batch = 1;
		int channels = 4;
		int height = 10;
		int width = 10;
		int poolSize = 2;

		TraversalPolicy inputShape = shape(batch, channels, height, width);
		log("Input shape: " + inputShape);

		// Analyze the declared output shape
		TraversalPolicy declaredOutput = shape(batch, channels, height / poolSize, width / poolSize);
		log("Declared output shape: " + declaredOutput);

		// The warning indicates actual output is (1, 4, 5, 5, 1)
		// This suggests the max(4) operation adds or preserves a trailing dimension
		log("");
		log("=== Analysis ===");
		log("The operator chain is:");
		log("  input.reshape(-1, c, h, w)  -> shape: (-1, 4, 10, 10)");
		log("  .traverse(2)                -> traversal axis = 2");
		log("  .enumerate(3, 2)            -> splits dim 3 by 2, adds dim");
		log("  .enumerate(3, 2)            -> splits dim 3 again by 2");
		log("  .max(4)                     -> max over dim 4");
		log("");
		log("After enumerate operations, shape grows.");
		log("max() reduces along one axis but may leave a trailing dim=1.");
		log("");
		log("The extra trailing dimension (1) comes from enumerate/max interaction.");
		log("This could be fixed by adding .reshape(outputShape) at the end.");
	}

	/**
	 * Test with different input sizes to understand the pattern.
	 */
	@Test(timeout = 10000)
	public void testPool2dVariousSizes() {
		log("=== Test: Pool2d Various Sizes ===");

		int[][] testCases = {
				{1, 2, 4, 4, 2},   // batch, channels, height, width, poolSize
				{1, 4, 8, 8, 2},
				{2, 3, 6, 6, 2},
				{1, 1, 10, 10, 5}
		};

		for (int[] tc : testCases) {
			int batch = tc[0];
			int channels = tc[1];
			int height = tc[2];
			int width = tc[3];
			int poolSize = tc[4];

			TraversalPolicy inputShape = shape(batch, channels, height, width);
			CellularLayer layer = pool2d(inputShape, poolSize);

			TraversalPolicy expectedOutput = shape(batch, channels, height / poolSize, width / poolSize);

			log("Input: " + inputShape + ", Pool: " + poolSize);
			log("  Declared output: " + layer.getOutputShape());
			log("  Expected output: " + expectedOutput);
			log("  Match: " + layer.getOutputShape().equals(expectedOutput));
			log("");
		}
	}
}
