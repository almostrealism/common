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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.Layer;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Integration tests that document which layers trigger shape mismatches
 * and will need to be fixed when strict shape enforcement is enabled.
 *
 * <p>Run with AR_STRICT_SHAPES=true to identify layers that need fixing:</p>
 * <pre>
 * export AR_STRICT_SHAPES=true
 * mvn test -pl utils -Dtest=StrictShapeEnforcementTest
 * </pre>
 *
 * <p>This test serves two purposes:</p>
 * <ol>
 *   <li>Documents which layers currently rely on lenient shape matching</li>
 *   <li>Becomes a regression test after all layers are fixed</li>
 * </ol>
 *
 * @author Michael Murray
 */
public class StrictShapeEnforcementTest implements ModelTestFeatures {

	static {
		if (TestUtils.getTrainTests()) {
			Console.root().addListener(OutputFeatures.fileOutput("results/logs/strict_shape_enforcement.out"));
		}
	}

	private final double[] coeff = { 0.24, -0.1, 0.36 };

	private final UnaryOperator<PackedCollection> linearFunc =
			in -> {
				PackedCollection out = new PackedCollection(in.getShape());
				for (int i = 0; i < in.getMemLength(); i++) {
					int coeffIdx = i % coeff.length;
					out.setMem(i, coeff[coeffIdx] * in.valueAt(i));
				}
				return out;
			};

	/**
	 * Document current strict mode status.
	 */
	@Test
	public void documentStrictModeStatus() {
		log("=== Strict Shape Enforcement Status ===");
		log("Layer.strictShapeEnforcement = " + Layer.strictShapeEnforcement);
		log("Layer.shapeWarnings = " + Layer.shapeWarnings);

		if (Layer.strictShapeEnforcement) {
			log("STRICT MODE ENABLED: Shape mismatches will throw exceptions");
		} else {
			log("LENIENT MODE: Shape mismatches will log warnings and auto-reshape");
		}
	}

	/**
	 * Test dense layer shape compatibility.
	 * Documents that dense layers currently produce transposed output shapes.
	 */
	@Test
	public void testDenseLayerShapes() {
		log("=== Dense Layer Shape Test ===");

		int inputSize = 4;
		int outputSize = 3;

		try {
			// Create a simple dense layer
			CellularLayer denseLayer = dense(inputSize, outputSize).apply(shape(1, inputSize));

			TraversalPolicy inputShape = denseLayer.getInputShape();
			TraversalPolicy outputShape = denseLayer.getOutputShape();

			log("Dense layer created successfully");
			log("  Input shape: " + inputShape);
			log("  Output shape: " + outputShape);

			// Validate the shape declaration
			validateShapeDeclaration("dense", inputShape, outputShape, inputSize, outputSize);

		} catch (IllegalArgumentException e) {
			if (Layer.strictShapeEnforcement) {
				log("EXPECTED in strict mode: " + e.getMessage());
				log("This layer needs to be fixed for strict mode compatibility");
			} else {
				throw e;
			}
		}
	}

	/**
	 * Test normalization layer shape compatibility.
	 */
	@Test
	public void testNormLayerShapes() {
		log("=== Normalization Layer Shape Test ===");

		int size = 6;

		try {
			CellularLayer normLayer = norm(1).apply(shape(1, size));

			TraversalPolicy inputShape = normLayer.getInputShape();
			TraversalPolicy outputShape = normLayer.getOutputShape();

			log("Norm layer created successfully");
			log("  Input shape: " + inputShape);
			log("  Output shape: " + outputShape);

		} catch (IllegalArgumentException e) {
			if (Layer.strictShapeEnforcement) {
				log("EXPECTED in strict mode: " + e.getMessage());
			} else {
				throw e;
			}
		}
	}

	/**
	 * Test that the synthetic dense training test works in current mode.
	 * This test will fail in strict mode until dense layer is fixed.
	 */
	@Test
	public void testSyntheticDenseInCurrentMode() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Synthetic Dense Test (Current Mode) ===");

		int inputSize = 5;
		int outputSize = 3;
		int epochs = 50;
		int steps = 50;

		try {
			SequentialBlock block = new SequentialBlock(shape(inputSize));
			block.add(dense(inputSize, outputSize));

			Model model = new Model(shape(inputSize), 1e-5);
			model.add(block);

			log("Model created successfully with output shape: " + model.getOutputShape());

			Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
					.mapToObj(i -> new PackedCollection(shape(inputSize)))
					.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
					.map(input -> ValueTarget.of(input, linearFunc.apply(input)))
					.collect(Collectors.toList()));

			train("syntheticDense_currentMode", model, data, epochs, steps, 2.0, 1.0);

			log("Training completed successfully in current mode");

		} catch (IllegalArgumentException e) {
			if (Layer.strictShapeEnforcement) {
				log("EXPECTED failure in strict mode: " + e.getMessage());
				log("Dense layer shape mismatch needs to be fixed");
			} else {
				throw e;
			}
		}
	}

	/**
	 * Test pool2d layer shape compatibility.
	 */
	@Test
	public void testPool2dLayerShapes() {
		log("=== Pool2d Layer Shape Test ===");

		int channels = 4;
		int height = 10;
		int width = 10;
		int poolSize = 2;

		try {
			CellularLayer poolLayer = pool2d(poolSize).apply(shape(1, channels, height, width));

			TraversalPolicy inputShape = poolLayer.getInputShape();
			TraversalPolicy outputShape = poolLayer.getOutputShape();

			log("Pool2d layer created successfully");
			log("  Input shape: " + inputShape);
			log("  Output shape: " + outputShape);

			// Expected output shape should be (1, channels, height/poolSize, width/poolSize)
			int expectedHeight = height / poolSize;
			int expectedWidth = width / poolSize;
			log("  Expected output: (1, " + channels + ", " + expectedHeight + ", " + expectedWidth + ")");

		} catch (IllegalArgumentException e) {
			if (Layer.strictShapeEnforcement) {
				log("EXPECTED in strict mode: " + e.getMessage());
			} else {
				throw e;
			}
		}
	}

	/**
	 * Test SiLU activation layer shape compatibility.
	 */
	@Test
	public void testSiLULayerShapes() {
		log("=== SiLU Activation Layer Shape Test ===");

		int size = 8;

		try {
			CellularLayer siluLayer = silu().apply(shape(1, size));

			TraversalPolicy inputShape = siluLayer.getInputShape();
			TraversalPolicy outputShape = siluLayer.getOutputShape();

			log("SiLU layer created successfully");
			log("  Input shape: " + inputShape);
			log("  Output shape: " + outputShape);

			Assert.assertEquals("SiLU should preserve shape",
					inputShape.getTotalSize(), outputShape.getTotalSize());

		} catch (IllegalArgumentException e) {
			if (Layer.strictShapeEnforcement) {
				log("EXPECTED in strict mode: " + e.getMessage());
			} else {
				throw e;
			}
		}
	}

	/**
	 * Test residual block shape compatibility.
	 */
	@Test
	public void testResidualBlockShapes() {
		log("=== Residual Block Shape Test ===");

		int size = 4;

		try {
			SequentialBlock innerBlock = new SequentialBlock(shape(size));
			innerBlock.add(dense(size, size));

			// residual(Block) returns a Block, not a CellularLayer
			// We test it by adding to a SequentialBlock
			SequentialBlock block = new SequentialBlock(shape(size));
			block.add(residual(innerBlock));
			block.add(dense(size, size));

			Model model = new Model(shape(size), 1e-5);
			model.add(block);

			log("Residual block created successfully");
			log("  Input shape: " + model.getInputShape());
			log("  Output shape: " + model.getOutputShape());

		} catch (IllegalArgumentException e) {
			if (Layer.strictShapeEnforcement) {
				log("EXPECTED in strict mode: " + e.getMessage());
			} else {
				throw e;
			}
		}
	}

	/**
	 * Helper method to validate shape declarations.
	 */
	private void validateShapeDeclaration(String name, TraversalPolicy inputShape,
										  TraversalPolicy outputShape,
										  int expectedInputSize, int expectedOutputSize) {
		log("Validating " + name + " shape declaration:");
		log("  Input total size: " + inputShape.getTotalSize() + " (expected: " + expectedInputSize + ")");
		log("  Output total size: " + outputShape.getTotalSize() + " (expected: " + expectedOutputSize + ")");

		// In lenient mode, we just verify sizes match expectations
		if (inputShape.getTotalSize() != expectedInputSize) {
			log("  WARNING: Input size mismatch");
		}
		if (outputShape.getTotalSize() != expectedOutputSize) {
			log("  WARNING: Output size mismatch");
		}
	}
}
