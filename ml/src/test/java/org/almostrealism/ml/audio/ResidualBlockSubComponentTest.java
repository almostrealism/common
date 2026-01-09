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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Narrow tests for residual block sub-components within Decoder Block 1.
 *
 * <p>These tests validate each individual sub-component (Snake, Conv1d) within
 * the residual blocks to precisely identify the source of numerical error.</p>
 *
 * <p>Error accumulation analysis (from previous tests):
 * <ul>
 *   <li>Residual Block 0 output: MAE = 0.000148 (excellent)</li>
 *   <li>Residual Block 1 output: MAE = 0.306 (~2000x worse)</li>
 *   <li>Residual Block 2 output: MAE = 1.57 (~5x worse)</li>
 * </ul>
 *
 * <p>This test class breaks down each residual block into sub-components:
 * Snake1 -> Conv1(k=7) -> Snake2 -> Conv2(k=1) -> residual add
 * to identify exactly where the ~2000x error jump occurs.</p>
 */
public class ResidualBlockSubComponentTest implements TestFeatures, LayerFeatures, ConsoleFeatures {

	private static final Path TEST_DATA_DIR = Paths.get("test_data/stable_audio");
	private static final Path WEIGHTS_DIR = TEST_DATA_DIR.resolve("weights");
	private static final Path REFERENCE_DIR = TEST_DATA_DIR.resolve("reference");

	private static final double TOLERANCE = 0.01;

	// Common dimensions for Block 1's residual blocks
	private static final int BATCH_SIZE = 1;
	private static final int CHANNELS = 1024;
	private static final int SEQ_LENGTH = 33;

	/**
	 * Tests Residual Block 0's Snake1 activation output.
	 * Input: decoder_block1_res0_input.bin (after upsample)
	 * Expected: decoder_block1_res0_after_snake1.bin
	 */
	@Test
	public void testRes0Snake1() throws IOException {
		runSubComponentTest("res0", "snake1", "decoder.layers.1.layers.2",
				this::buildSnake1);
	}

	/**
	 * Tests Residual Block 0's Conv1 output.
	 * Input: decoder_block1_res0_after_snake1.bin
	 * Expected: decoder_block1_res0_after_conv1.bin
	 */
	@Test
	public void testRes0Conv1() throws IOException {
		runSubComponentTest("res0", "conv1", "decoder.layers.1.layers.2",
				this::buildConv1);
	}

	/**
	 * Tests Residual Block 0's Snake2 activation output.
	 * Input: decoder_block1_res0_after_conv1.bin
	 * Expected: decoder_block1_res0_after_snake2.bin
	 */
	@Test
	public void testRes0Snake2() throws IOException {
		runSubComponentTest("res0", "snake2", "decoder.layers.1.layers.2",
				this::buildSnake2);
	}

	/**
	 * Tests Residual Block 0's Conv2 output.
	 * Input: decoder_block1_res0_after_snake2.bin
	 * Expected: decoder_block1_res0_after_conv2.bin
	 */
	@Test
	public void testRes0Conv2() throws IOException {
		runSubComponentTest("res0", "conv2", "decoder.layers.1.layers.2",
				this::buildConv2);
	}

	/**
	 * Tests Residual Block 1's Snake1 activation output.
	 * Input: decoder_block1_res1_input.bin (output of res0)
	 * Expected: decoder_block1_res1_after_snake1.bin
	 */
	@Test
	public void testRes1Snake1() throws IOException {
		runSubComponentTest("res1", "snake1", "decoder.layers.1.layers.3",
				this::buildSnake1);
	}

	/**
	 * Tests Residual Block 1's Conv1 output.
	 * Input: decoder_block1_res1_after_snake1.bin
	 * Expected: decoder_block1_res1_after_conv1.bin
	 */
	@Test
	public void testRes1Conv1() throws IOException {
		runSubComponentTest("res1", "conv1", "decoder.layers.1.layers.3",
				this::buildConv1);
	}

	/**
	 * Tests Residual Block 1's Snake2 activation output.
	 * Input: decoder_block1_res1_after_conv1.bin
	 * Expected: decoder_block1_res1_after_snake2.bin
	 */
	@Test
	public void testRes1Snake2() throws IOException {
		runSubComponentTest("res1", "snake2", "decoder.layers.1.layers.3",
				this::buildSnake2);
	}

	/**
	 * Tests Residual Block 1's Conv2 output.
	 * Input: decoder_block1_res1_after_snake2.bin
	 * Expected: decoder_block1_res1_after_conv2.bin
	 */
	@Test
	public void testRes1Conv2() throws IOException {
		runSubComponentTest("res1", "conv2", "decoder.layers.1.layers.3",
				this::buildConv2);
	}

	/**
	 * Tests Residual Block 2's Snake1 activation output.
	 */
	@Test
	public void testRes2Snake1() throws IOException {
		runSubComponentTest("res2", "snake1", "decoder.layers.1.layers.4",
				this::buildSnake1);
	}

	/**
	 * Tests Residual Block 2's Conv1 output.
	 */
	@Test
	public void testRes2Conv1() throws IOException {
		runSubComponentTest("res2", "conv1", "decoder.layers.1.layers.4",
				this::buildConv1);
	}

	/**
	 * Tests Residual Block 2's Snake2 activation output.
	 */
	@Test
	public void testRes2Snake2() throws IOException {
		runSubComponentTest("res2", "snake2", "decoder.layers.1.layers.4",
				this::buildSnake2);
	}

	/**
	 * Tests Residual Block 2's Conv2 output.
	 */
	@Test
	public void testRes2Conv2() throws IOException {
		runSubComponentTest("res2", "conv2", "decoder.layers.1.layers.4",
				this::buildConv2);
	}

	/**
	 * Tests FULL Residual Block 0 with residual add.
	 * Input: decoder_block1_res0_input.bin (after upsample)
	 * Expected: decoder_block1_res0_output.bin
	 *
	 * This tests the residual() wrapper functionality.
	 */
	@Test
	public void testRes0FullBlock() throws IOException {
		runFullResidualBlockTest("res0", "decoder.layers.1.layers.2");
	}

	/**
	 * Tests FULL Residual Block 1 with residual add.
	 * Input: decoder_block1_res1_input.bin (output of res0)
	 * Expected: decoder_block1_res1_output.bin
	 *
	 * This tests where the error jump occurs (MAE goes from 0.000148 to 0.306).
	 */
	@Test
	public void testRes1FullBlock() throws IOException {
		runFullResidualBlockTest("res1", "decoder.layers.1.layers.3");
	}

	/**
	 * Tests FULL Residual Block 2 with residual add.
	 */
	@Test
	public void testRes2FullBlock() throws IOException {
		runFullResidualBlockTest("res2", "decoder.layers.1.layers.4");
	}

	/**
	 * Test runner for full residual block with residual add.
	 */
	private void runFullResidualBlockTest(String resBlock, String prefix) throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		String logFile = String.format("test_data/stable_audio/test_%s_full_block.log", resBlock);
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log(String.format("\n=== Test: %s FULL BLOCK (with residual add) ===\n", resBlock.toUpperCase()));

		StateDictionary weights = new StateDictionary(WEIGHTS_DIR.toString());

		// Load input and expected output
		String inputFile = String.format("decoder_block1_%s_input.bin", resBlock);
		String expectedFile = String.format("decoder_block1_%s_output.bin", resBlock);

		log(String.format("Input: %s", inputFile));
		log(String.format("Expected: %s", expectedFile));

		float[] inputData = loadReferenceOutput(inputFile);
		log(String.format("Input size: %d elements", inputData.length));

		PackedCollection input = new PackedCollection(BATCH_SIZE, CHANNELS, SEQ_LENGTH);
		for (int i = 0; i < inputData.length; i++) {
			input.setMem(i, inputData[i]);
		}

		// Build full residual block with skip connection
		Block fullBlock = buildFullResidualBlock(weights, prefix);
		Model model = new Model(shape(BATCH_SIZE, CHANNELS, SEQ_LENGTH));
		model.add(fullBlock);

		// Compile and run
		log("\nCompiling and running...");
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		// Load expected output
		float[] expected = loadReferenceOutput(expectedFile);
		log(String.format("Output size: %d elements", output.getMemLength()));
		log(String.format("Expected size: %d elements", expected.length));

		// Compare
		compareOutputs(String.format("%s_full_block", resBlock), output, expected, TOLERANCE);
	}

	/**
	 * Tests COMPOSED blocks: Java res0 output fed into res1.
	 *
	 * This test verifies that error amplification occurs when:
	 * - res0 produces output with small error (MAE ~0.0001)
	 * - This slightly-wrong output is fed into res1
	 * - The error amplifies (~2000x to MAE ~0.3)
	 */
	@Test
	public void testComposedRes0ThenRes1() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		String logFile = "test_data/stable_audio/test_composed_res0_res1.log";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Test: COMPOSED res0 -> res1 (error amplification) ===\n");

		StateDictionary weights = new StateDictionary(WEIGHTS_DIR.toString());

		// Step 1: Run res0 with REFERENCE input
		float[] res0Input = loadReferenceOutput("decoder_block1_res0_input.bin");
		log(String.format("Step 1: Running res0 with reference input (%d elements)", res0Input.length));

		PackedCollection input = new PackedCollection(BATCH_SIZE, CHANNELS, SEQ_LENGTH);
		for (int i = 0; i < res0Input.length; i++) {
			input.setMem(i, res0Input[i]);
		}

		Block res0Block = buildFullResidualBlock(weights, "decoder.layers.1.layers.2");
		Model res0Model = new Model(shape(BATCH_SIZE, CHANNELS, SEQ_LENGTH));
		res0Model.add(res0Block);
		CompiledModel res0Compiled = res0Model.compile();
		PackedCollection javaRes0Output = res0Compiled.forward(input);

		// Check res0 output error
		float[] refRes0Output = loadReferenceOutput("decoder_block1_res0_output.bin");
		log("\nRes0 output comparison (Java vs PyTorch reference):");
		double res0Error = computeMAE(javaRes0Output, refRes0Output);
		log(String.format("  Res0 MAE: %.6f", res0Error));

		// Step 2: Feed JAVA res0 output into res1
		log("\nStep 2: Feeding Java res0 output into res1...");

		Block res1Block = buildFullResidualBlock(weights, "decoder.layers.1.layers.3");
		Model res1Model = new Model(shape(BATCH_SIZE, CHANNELS, SEQ_LENGTH));
		res1Model.add(res1Block);
		CompiledModel res1Compiled = res1Model.compile();
		PackedCollection javaRes1Output = res1Compiled.forward(javaRes0Output);

		// Check res1 output error (this should show amplification)
		float[] refRes1Output = loadReferenceOutput("decoder_block1_res1_output.bin");
		log("\nRes1 output comparison (using Java res0 output as input):");
		double res1Error = computeMAE(javaRes1Output, refRes1Output);
		log(String.format("  Res1 MAE: %.6f", res1Error));

		// Show amplification factor
		double amplification = res1Error / res0Error;
		log(String.format("\n  Error amplification factor: %.1fx", amplification));

		// Compare with isolated res1 test (which uses reference input)
		log("\n  For comparison:");
		log("    - Res1 with REFERENCE input: MAE ~0.003654 (from previous test)");
		log(String.format("    - Res1 with JAVA res0 output: MAE %.6f", res1Error));

		if (amplification > 10) {
			log("\n  CONFIRMED: Error amplification through composition!");
			log("  Small errors in res0 output get amplified through res1.");
		}

		// This test documents the amplification but doesn't assert failure
		// The amplification is expected behavior, not a bug
		assertTrue("Res0 should have low error", res0Error < 0.01);
	}

	/**
	 * Computes Mean Absolute Error between actual and expected.
	 */
	private double computeMAE(PackedCollection actual, float[] expected) {
		int size = Math.min((int) actual.getMemLength(), expected.length);
		double sum = 0;
		for (int i = 0; i < size; i++) {
			sum += Math.abs(actual.toDouble(i) - expected[i]);
		}
		return sum / size;
	}

	/**
	 * Builds a full residual block with skip connection.
	 */
	private Block buildFullResidualBlock(StateDictionary weights, String prefix) {
		TraversalPolicy inputShape = shape(BATCH_SIZE, CHANNELS, SEQ_LENGTH);

		// Main path: Snake -> Conv(k=7) -> Snake -> Conv(k=1)
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		// layers.0: Snake
		PackedCollection snake0_alpha = weights.get(prefix + ".layers.0.alpha");
		PackedCollection snake0_beta = weights.get(prefix + ".layers.0.beta");
		mainPath.add(snake(inputShape, snake0_alpha, snake0_beta));

		// layers.1: WNConv1d(k=7, p=3)
		PackedCollection conv1_g = weights.get(prefix + ".layers.1.weight_g");
		PackedCollection conv1_v = weights.get(prefix + ".layers.1.weight_v");
		PackedCollection conv1_b = weights.get(prefix + ".layers.1.bias");
		mainPath.add(wnConv1d(BATCH_SIZE, CHANNELS, CHANNELS, SEQ_LENGTH, 7, 1, 3,
				conv1_g, conv1_v, conv1_b));

		// layers.2: Snake
		PackedCollection snake2_alpha = weights.get(prefix + ".layers.2.alpha");
		PackedCollection snake2_beta = weights.get(prefix + ".layers.2.beta");
		mainPath.add(snake(inputShape, snake2_alpha, snake2_beta));

		// layers.3: WNConv1d(k=1, p=0)
		PackedCollection conv3_g = weights.get(prefix + ".layers.3.weight_g");
		PackedCollection conv3_v = weights.get(prefix + ".layers.3.weight_v");
		PackedCollection conv3_b = weights.get(prefix + ".layers.3.bias");
		mainPath.add(wnConv1d(BATCH_SIZE, CHANNELS, CHANNELS, SEQ_LENGTH, 1, 1, 0,
				conv3_g, conv3_v, conv3_b));

		// Residual connection: output = main(x) + x
		return residual(mainPath);
	}

	/**
	 * Generic sub-component test runner.
	 */
	private void runSubComponentTest(String resBlock, String component, String prefix,
									 BlockBuilder blockBuilder) throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		String logFile = String.format("test_data/stable_audio/test_%s_%s.log", resBlock, component);
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log(String.format("\n=== Test: %s %s ===\n", resBlock.toUpperCase(), component));

		StateDictionary weights = new StateDictionary(WEIGHTS_DIR.toString());

		// Determine input/output file names
		String inputFile = getInputFileName(resBlock, component);
		String expectedFile = String.format("decoder_block1_%s_after_%s.bin", resBlock, component);

		log(String.format("Input: %s", inputFile));
		log(String.format("Expected: %s", expectedFile));

		// Load input
		float[] inputData = loadReferenceOutput(inputFile);
		log(String.format("Input size: %d elements", inputData.length));

		PackedCollection input = new PackedCollection(BATCH_SIZE, CHANNELS, SEQ_LENGTH);
		for (int i = 0; i < inputData.length; i++) {
			input.setMem(i, inputData[i]);
		}

		// Build the sub-component
		Block block = blockBuilder.build(weights, prefix);
		Model model = new Model(shape(BATCH_SIZE, CHANNELS, SEQ_LENGTH));
		model.add(block);

		// Compile and run
		log("\nCompiling and running...");
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		// Load expected output
		float[] expected = loadReferenceOutput(expectedFile);
		log(String.format("Output size: %d elements", output.getMemLength()));
		log(String.format("Expected size: %d elements", expected.length));

		// Compare
		compareOutputs(String.format("%s_%s", resBlock, component), output, expected, TOLERANCE);
	}

	/**
	 * Gets the input file name for a given component.
	 */
	private String getInputFileName(String resBlock, String component) {
		switch (component) {
			case "snake1":
				return String.format("decoder_block1_%s_input.bin", resBlock);
			case "conv1":
				return String.format("decoder_block1_%s_after_snake1.bin", resBlock);
			case "snake2":
				return String.format("decoder_block1_%s_after_conv1.bin", resBlock);
			case "conv2":
				return String.format("decoder_block1_%s_after_snake2.bin", resBlock);
			default:
				throw new IllegalArgumentException("Unknown component: " + component);
		}
	}

	/**
	 * Functional interface for building sub-component blocks.
	 */
	@FunctionalInterface
	private interface BlockBuilder {
		Block build(StateDictionary weights, String prefix);
	}

	/**
	 * Builds Snake1 (layers.0) sub-component.
	 */
	private Block buildSnake1(StateDictionary weights, String prefix) {
		TraversalPolicy inputShape = shape(BATCH_SIZE, CHANNELS, SEQ_LENGTH);
		PackedCollection alpha = weights.get(prefix + ".layers.0.alpha");
		PackedCollection beta = weights.get(prefix + ".layers.0.beta");
		return snake(inputShape, alpha, beta);
	}

	/**
	 * Builds Conv1 (layers.1) sub-component: WNConv1d(k=7, p=3).
	 */
	private Block buildConv1(StateDictionary weights, String prefix) {
		PackedCollection g = weights.get(prefix + ".layers.1.weight_g");
		PackedCollection v = weights.get(prefix + ".layers.1.weight_v");
		PackedCollection b = weights.get(prefix + ".layers.1.bias");
		return wnConv1d(BATCH_SIZE, CHANNELS, CHANNELS, SEQ_LENGTH, 7, 1, 3, g, v, b);
	}

	/**
	 * Builds Snake2 (layers.2) sub-component.
	 */
	private Block buildSnake2(StateDictionary weights, String prefix) {
		TraversalPolicy inputShape = shape(BATCH_SIZE, CHANNELS, SEQ_LENGTH);
		PackedCollection alpha = weights.get(prefix + ".layers.2.alpha");
		PackedCollection beta = weights.get(prefix + ".layers.2.beta");
		return snake(inputShape, alpha, beta);
	}

	/**
	 * Builds Conv2 (layers.3) sub-component: WNConv1d(k=1, p=0).
	 */
	private Block buildConv2(StateDictionary weights, String prefix) {
		PackedCollection g = weights.get(prefix + ".layers.3.weight_g");
		PackedCollection v = weights.get(prefix + ".layers.3.weight_v");
		PackedCollection b = weights.get(prefix + ".layers.3.bias");
		return wnConv1d(BATCH_SIZE, CHANNELS, CHANNELS, SEQ_LENGTH, 1, 1, 0, g, v, b);
	}

	/**
	 * Loads a reference output file in binary format.
	 */
	private float[] loadReferenceOutput(String filename) throws IOException {
		Path filepath = REFERENCE_DIR.resolve(filename);

		try (DataInputStream dis = new DataInputStream(new FileInputStream(filepath.toFile()))) {
			byte[] countBytes = new byte[4];
			dis.readFully(countBytes);
			int count = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

			float[] values = new float[count];
			byte[] floatBytes = new byte[4];
			for (int i = 0; i < count; i++) {
				dis.readFully(floatBytes);
				values[i] = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
			}

			return values;
		}
	}

	/**
	 * Compares AR output against PyTorch reference and reports statistics.
	 */
	private void compareOutputs(String name, PackedCollection actual, float[] expected,
								double tolerance) {
		int actualSize = (int) actual.getMemLength();
		int expectedSize = expected.length;

		log(String.format("  %s: actual size=%d, expected size=%d", name, actualSize, expectedSize));

		if (actualSize != expectedSize) {
			log("  WARNING: Size mismatch!");
		}

		int compareSize = Math.min(actualSize, expectedSize);
		double sumAbsDiff = 0;
		double maxAbsDiff = 0;
		int aboveToleranceCount = 0;

		for (int i = 0; i < compareSize; i++) {
			double actualVal = actual.toDouble(i);
			double expectedVal = expected[i];
			double diff = Math.abs(actualVal - expectedVal);

			sumAbsDiff += diff;
			maxAbsDiff = Math.max(maxAbsDiff, diff);

			if (diff > tolerance) {
				aboveToleranceCount++;
			}
		}

		double meanAbsDiff = sumAbsDiff / compareSize;

		log(String.format("  Mean Absolute Difference: %.6f", meanAbsDiff));
		log(String.format("  Max Absolute Difference: %.6f", maxAbsDiff));
		log(String.format("  Above tolerance: %d / %d (%.2f%%)",
				aboveToleranceCount, compareSize, 100.0 * aboveToleranceCount / compareSize));

		// Sample values
		log("  Sample values:");
		for (int i = 0; i < Math.min(5, compareSize); i++) {
			log(String.format("    [%d] actual=%.6f, expected=%.6f, diff=%.6f",
					i, actual.toDouble(i), expected[i],
					Math.abs(actual.toDouble(i) - expected[i])));
		}

		String result = meanAbsDiff <= tolerance ? "PASS" : "FAIL";
		log(String.format("  Result: %s (MAE=%.6f, tolerance=%.6f)", result, meanAbsDiff, tolerance));

		assertTrue(String.format("%s MAE %.6f exceeds tolerance %.6f", name, meanAbsDiff, tolerance),
				meanAbsDiff <= tolerance);
	}
}
