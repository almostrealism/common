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
 * These tests examine how the norm layer handles different input shapes
 * and how the padDimensions affects shape declarations.
 *
 * <p>Key questions addressed:</p>
 * <ul>
 *   <li>How does padDimensions affect the input shape?</li>
 *   <li>What shape does the layer declare vs what it receives?</li>
 *   <li>What causes the shape mismatch warning?</li>
 *   <li>How does training work with the internal reshape operations?</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class NormLayerShapeInvestigationTest extends TestSuiteBase {

	static {
		if (TestUtils.getTrainTests()) {
			Console.root().addListener(OutputFeatures.fileOutput("results/logs/norm_shape_investigation.out"));
		}
	}

	/**
	 * Test 1: Understand padDimensions behavior.
	 * When norm receives shape (6) from previous layer, what does padDimensions do?
	 */
	@Test(timeout = 10000)
	public void testPadDimensionsBehavior() {
		log("=== Test 1: padDimensions Behavior ===");

		// Simulate what happens in norm layer
		TraversalPolicy shape1D = shape(6);
		TraversalPolicy padded1D = padDimensions(shape1D, 1, 3);

		log("Input 1D shape: " + shape1D);
		log("padDimensions(1D, 1, 3): " + padded1D);

		TraversalPolicy shape2D = shape(1, 6);
		TraversalPolicy padded2D = padDimensions(shape2D, 1, 3);

		log("Input 2D shape: " + shape2D);
		log("padDimensions(2D, 1, 3): " + padded2D);

		TraversalPolicy shape3D = shape(1, 1, 6);
		TraversalPolicy padded3D = padDimensions(shape3D, 1, 3);

		log("Input 3D shape: " + shape3D);
		log("padDimensions(3D, 1, 3): " + padded3D);

		// Key insight: padDimensions pads to minimum 3 dimensions
		Assert.assertEquals("1D should become 3D", 3, padded1D.getDimensions());
		Assert.assertEquals("2D should become 3D", 3, padded2D.getDimensions());
		Assert.assertEquals("3D should stay 3D", 3, padded3D.getDimensions());
	}

	/**
	 * Test 2: Examine the norm layer shape declarations.
	 * What does the layer claim its input/output shapes are?
	 */
	@Test(timeout = 10000)
	public void testNormLayerShapeDeclaration() {
		log("=== Test 2: Norm Layer Shape Declaration ===");

		int size = 6;
		int groups = 1;

		// Create a norm layer directly with 2D shape
		TraversalPolicy inputShape = shape(1, size);
		CellularLayer layer = norm(inputShape, groups);

		log("Input shape provided: " + inputShape);
		log("Layer.getInputShape(): " + layer.getInputShape());
		log("Layer.getOutputShape(): " + layer.getOutputShape());

		// Create with 2D shape via Function variant (this is how SequentialBlock uses it)
		TraversalPolicy inputShape2D = shape(1, size);
		CellularLayer layer2D = norm(1).apply(inputShape2D);

		log("");
		log("Input shape provided (2D via Function): " + inputShape2D);
		log("Layer.getInputShape(): " + layer2D.getInputShape());
		log("Layer.getOutputShape(): " + layer2D.getOutputShape());

		// NOTE: 1D shapes (6) cannot be used directly with norm(1).apply()
		// because padDimensions will create (1, 1, 6) and the item() check fails
		log("");
		log("NOTE: Direct 1D shape input to norm() is not supported.");
		log("Use SequentialBlock.add(norm(groups)) which passes output shape of previous layer.");
	}

	/**
	 * Test 3: Trace what happens in the norm computation.
	 * The internal reshape: input -> (-1, groups, size/groups) -> compute -> (-1, size)
	 */
	@Test(timeout = 10000)
	public void testNormInternalReshape() {
		log("=== Test 3: Norm Internal Reshape ===");

		int size = 6;
		int groups = 2;
		int groupSize = size / groups;  // 3

		log("size: " + size);
		log("groups: " + groups);
		log("groupSize: " + groupSize);

		// Simulate what norm does internally
		// Input: some shape with total size N * size
		// Reshape to: (-1, groups, groupSize) = (-1, 2, 3)

		TraversalPolicy inputShape = shape(1, size);  // (1, 6)
		log("Input shape: " + inputShape);
		log("Total size: " + inputShape.getTotalSize());

		// What reshape(-1, groups, groupSize) produces
		log("");
		log("reshape(-1, " + groups + ", " + groupSize + ")");
		log("With input (1, 6) -> total 6 elements");
		log("Expected: (1, 2, 3) since 6 / (2*3) = 1");

		// Output reshape
		log("");
		log("reshape(-1, " + size + ").traverse(1)");
		log("With (1, 2, 3) -> total 6 elements");
		log("Expected: (1, 6) then traverse(1) = (1, 6)");
	}

	/**
	 * Test 4: Run a simple norm layer and observe the shape warning.
	 */
	@Test(timeout = 20000)
	public void testNormLayerWarning() {
		log("=== Test 4: Norm Layer Shape Warning ===");

		int size = 6;

		// Build a model: dense -> norm
		TraversalPolicy inputShape = shape(size);
		SequentialBlock block = new SequentialBlock(inputShape);
		block.add(dense(size, size));
		block.add(norm(1));  // This triggers shape analysis

		log("Block built: dense(" + size + ") -> norm(1)");

		Model model = new Model(inputShape);
		model.add(block);

		log("Model input shape: " + model.getInputShape());
		log("Model output shape: " + model.getOutputShape());

		// Compile and run inference
		CompiledModel compiled = model.compile();

		PackedCollection input = new PackedCollection(inputShape);
		input.fill(pos -> 1.0 + pos[0]);
		log("Input: " + input.toArrayString());

		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());
		log("Output: " + output.toArrayString());

		// Verify output shape matches input
		Assert.assertEquals("Output size should match input",
				size, output.getShape().getTotalSize());
	}

	/**
	 * Test 5: Understand what the dense layer outputs.
	 * The dense layer outputs shape (1, size) due to matmul behavior.
	 */
	@Test(timeout = 10000)
	public void testDenseOutputShapeForNorm() {
		log("=== Test 5: Dense Output Shape for Norm ===");

		int size = 6;

		// Build just a dense layer - need to apply the Function to get CellularLayer
		TraversalPolicy inputShape = shape(size);
		CellularLayer denseLayer = dense(size, size).apply(inputShape);

		log("Dense input shape (from weights): expecting (1, " + size + ")");
		log("Dense.getInputShape(): " + denseLayer.getInputShape());
		log("Dense.getOutputShape(): " + denseLayer.getOutputShape());

		// This shows what shape the norm layer receives
		log("");
		log("When dense outputs " + denseLayer.getOutputShape() + ",");
		log("norm receives this shape and pads to 3D.");
	}

	/**
	 * Test 6: Compare norm input shape vs actual input record shape.
	 * This is where the mismatch occurs.
	 */
	@Test(timeout = 10000)
	public void testNormInputMismatch() {
		log("=== Test 6: Norm Input Shape Mismatch ===");

		int size = 6;

		// Create a dense layer - need to apply the Function to get CellularLayer
		TraversalPolicy inputShape = shape(size);
		CellularLayer denseLayer = dense(size, size).apply(inputShape);

		log("Dense output shape: " + denseLayer.getOutputShape());

		// Create a norm layer using Function pattern (as SequentialBlock does)
		CellularLayer normLayer = norm(1).apply(denseLayer.getOutputShape());

		log("Norm layer input shape: " + normLayer.getInputShape());
		log("Norm layer output shape: " + normLayer.getOutputShape());

		// The warning happens because:
		// 1. Dense declares output (1, 6) [batch, features]
		// 2. But matmul actually produces (6, 1) [features, batch]
		// 3. Norm expects input matching (1, 6)
		// 4. The actual data has shape (6, 1) from dense's matmul

		log("");
		log("The mismatch is:");
		log("  Dense layer declares output: " + denseLayer.getOutputShape());
		log("  Dense matmul actually outputs: (6, 1) [transposed]");
		log("  Norm layer expects input: " + normLayer.getInputShape());
		log("  Warning: '(1, 1, 6) does not match (6)' for Input record");
	}
}
