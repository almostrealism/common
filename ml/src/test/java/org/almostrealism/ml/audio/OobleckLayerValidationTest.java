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
 * Layer-by-layer validation tests to identify where constant output pattern first appears.
 *
 * <p>The full decoder produces nearly constant output (~0.278) while expected values vary widely.
 * This test identifies which layer first introduces the constant output pattern by testing
 * each layer individually and checking output variance.</p>
 *
 * <p>Key insight: if input varies but output is constant, the layer has a bug
 * (likely in index expression computation for large tensor operations).</p>
 */
public class OobleckLayerValidationTest implements TestFeatures, LayerFeatures, ConsoleFeatures {

	private static final Path TEST_DATA_DIR = Paths.get("test_data/stable_audio");
	private static final Path WEIGHTS_DIR = TEST_DATA_DIR.resolve("weights");
	private static final Path REFERENCE_DIR = TEST_DATA_DIR.resolve("reference");

	/**
	 * Tests the first WNConv1d layer alone: WNConv1d(64 -> 2048, k=7, p=3).
	 *
	 * <p>If this layer produces constant output from varying input, the issue is in WNConv1d.
	 * If output varies correctly, the issue is in a later layer.</p>
	 */
	@Test
	public void testFirstWNConv1dOnly() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_first_wnconv1d.log"));

		log("=== Layer Validation: First WNConv1d (64 -> 2048, k=7) ===");

		// Load weights
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		// Load latent input
		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;  // 64 channels
		log("Latent input shape: (1, 64, " + latentLength + ")");

		// Report input statistics
		double[] inputStats = computeStats(latentInput);
		log(String.format("Input stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				inputStats[0], inputStats[1], inputStats[2], inputStats[3]));

		// Build just the first layer
		int batchSize = 1;
		int inChannels = 64;
		int outChannels = 2048;
		TraversalPolicy inputShape = shape(batchSize, inChannels, latentLength);

		String l0 = "decoder.layers.0";
		PackedCollection l0_g = stateDict.get(l0 + ".weight_g");
		PackedCollection l0_v = stateDict.get(l0 + ".weight_v");
		PackedCollection l0_b = stateDict.get(l0 + ".bias");

		log("Weight shapes:");
		log("  weight_g: " + l0_g.getShape());
		log("  weight_v: " + l0_v.getShape());
		log("  bias: " + l0_b.getShape());

		Model model = new Model(inputShape);
		model.add(wnConv1d(batchSize, inChannels, outChannels, latentLength, 7, 1, 3,
				l0_g, l0_v, l0_b));

		log("\nCompiling model...");
		CompiledModel compiled = model.compile(false);

		// Prepare input
		PackedCollection input = new PackedCollection(batchSize, inChannels, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		// Run layer
		log("Running first WNConv1d layer...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		// Analyze output
		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		// Check for constant output pattern
		boolean isConstant = outputStats[3] < 0.001;  // std dev < 0.001 suggests constant
		log("\nConstant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		// Report sample values
		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		if (outputSize > 20) {
			log("\nSample output values (last 20):");
			for (int i = outputSize - 20; i < outputSize; i++) {
				log(String.format("  [%d] = %.6f", i, outputValues[i]));
			}
		}

		// Assert that output varies (not constant)
		assertTrue("First WNConv1d produces constant output - likely index expression bug",
				outputStats[3] > 0.001);

		log("\n=== First WNConv1d Test PASSED ===");
	}

	/**
	 * Tests the first WNConvTranspose1d alone (inside decoder block 1).
	 *
	 * <p>This is the large-scale operation: 2048 channels × 16 kernel.
	 * If this layer produces constant output from varying input, the issue is in
	 * the transposed convolution's index expressions at large scale.</p>
	 */
	@Test
	public void testFirstWNConvTranspose1dOnly() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_first_wnconvtranspose.log"));

		log("=== Layer Validation: First WNConvTranspose1d (2048 -> 1024, stride=16) ===");

		// Load weights
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		int batchSize = 1;
		int inChannels = 2048;
		int outChannels = 1024;
		int seqLength = 2;  // Same as latent length
		int stride = 16;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;

		// Compute output length
		int outLength = (seqLength - 1) * stride - 2 * padding + kernel + outputPadding;
		log("Input shape: (1, " + inChannels + ", " + seqLength + ")");
		log("Output shape: (1, " + outChannels + ", " + outLength + ")");

		// Create varying input (synthetic - simulate output from first WNConv1d)
		TraversalPolicy inputShape = shape(batchSize, inChannels, seqLength);
		PackedCollection input = new PackedCollection(inputShape);

		// Fill with varying values
		int inputSize = inChannels * seqLength;
		for (int i = 0; i < inputSize; i++) {
			// Linear gradient with some variation
			input.setMem(i, (i % 100) * 0.01 + Math.sin(i * 0.1) * 0.5);
		}

		float[] inputValues = new float[inputSize];
		for (int i = 0; i < inputSize; i++) {
			inputValues[i] = (float) input.toDouble(i);
		}
		double[] inputStats = computeStats(inputValues);
		log(String.format("Input stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				inputStats[0], inputStats[1], inputStats[2], inputStats[3]));

		// Load weights for first decoder block's WNConvTranspose1d
		String convPrefix = "decoder.layers.1.layers.1";
		PackedCollection conv_g = stateDict.get(convPrefix + ".weight_g");
		PackedCollection conv_v = stateDict.get(convPrefix + ".weight_v");
		PackedCollection conv_b = stateDict.get(convPrefix + ".bias");

		log("\nWeight shapes:");
		log("  weight_g: " + conv_g.getShape());
		log("  weight_v: " + conv_v.getShape());
		log("  bias: " + conv_b.getShape());

		// Build model with just WNConvTranspose1d
		Model model = new Model(inputShape);
		model.add(wnConvTranspose1d(batchSize, inChannels, outChannels, seqLength,
				kernel, stride, padding, outputPadding, conv_g, conv_v, conv_b));

		log("\nCompiling model...");
		CompiledModel compiled = model.compile(false);

		// Run layer
		log("Running WNConvTranspose1d layer...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		// Analyze output
		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		// Check for constant output pattern
		boolean isConstant = outputStats[3] < 0.001;
		log("\nConstant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		// Report sample values across different positions
		log("\nSample output values (by position):");
		for (int pos = 0; pos < Math.min(5, outLength); pos++) {
			log(String.format("  Position %d:", pos));
			for (int ch = 0; ch < Math.min(4, outChannels); ch++) {
				int idx = ch * outLength + pos;
				log(String.format("    ch[%d] = %.6f", ch, outputValues[idx]));
			}
		}

		// Assert that output varies
		assertTrue("WNConvTranspose1d produces constant output - likely index expression bug",
				outputStats[3] > 0.001);

		log("\n=== WNConvTranspose1d Test PASSED ===");
	}

	/**
	 * Tests just the first two layers: WNConv1d + Snake (from decoder block 1).
	 *
	 * <p>Snake should preserve variation - it's an elementwise activation.
	 * If this combination produces constant output, the issue is in WNConv1d.</p>
	 */
	@Test
	public void testFirstConvPlusSnake() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_conv_plus_snake.log"));

		log("=== Layer Validation: WNConv1d + Snake ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		// Load latent input
		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;
		int inChannels = 64;
		int outChannels = 2048;

		// Build layers
		TraversalPolicy inputShape = shape(batchSize, inChannels, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// First WNConv1d
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, inChannels, outChannels, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		// Snake from decoder block 1
		String snakePrefix = "decoder.layers.1.layers.0";
		TraversalPolicy snakeShape = shape(batchSize, outChannels, latentLength);
		block.add(snake(snakeShape,
				stateDict.get(snakePrefix + ".alpha"),
				stateDict.get(snakePrefix + ".beta")));

		Model model = new Model(inputShape);
		model.add(block);

		log("Compiling model...");
		CompiledModel compiled = model.compile(false);

		// Prepare input
		PackedCollection input = new PackedCollection(batchSize, inChannels, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("Running layers...");
		PackedCollection output = compiled.forward(input);

		// Analyze output
		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("Output stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean isConstant = outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		assertTrue("WNConv1d + Snake produces constant output", outputStats[3] > 0.001);

		log("\n=== WNConv1d + Snake Test PASSED ===");
	}

	/**
	 * Tests through the first complete decoder block (WNConv1d + DecoderBlock1).
	 *
	 * <p>DecoderBlock1 contains: Snake + WNConvTranspose1d(2048->1024, stride=16) + 3 ResBlocks.
	 * This includes the large-scale WNConvTranspose1d that is suspected of having index issues.</p>
	 */
	@Test
	public void testThroughFirstDecoderBlock() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_through_block1.log"));

		log("=== Layer Validation: Through First Decoder Block ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;

		// Build input projection + first decoder block
		TraversalPolicy inputShape = shape(batchSize, 64, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// layers.0: Input projection WNConv1d(64 -> 2048)
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, 64, 2048, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		// Decoder block 1
		int inChannels = 2048;
		int outChannels = 1024;
		int stride = 16;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		int outLength = (latentLength - 1) * stride - 2 * padding + kernel + outputPadding;

		String prefix = "decoder.layers.1";

		// Snake before upsample
		block.add(snake(shape(batchSize, inChannels, latentLength),
				stateDict.get(prefix + ".layers.0.alpha"),
				stateDict.get(prefix + ".layers.0.beta")));

		// WNConvTranspose1d upsample
		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, latentLength,
				kernel, stride, padding, outputPadding,
				stateDict.get(prefix + ".layers.1.weight_g"),
				stateDict.get(prefix + ".layers.1.weight_v"),
				stateDict.get(prefix + ".layers.1.bias")));

		// 3 Residual blocks (not building these to isolate the convTranspose issue)
		// For now, skip residual blocks to isolate where issue first occurs

		log("Output length after block1: " + outLength);
		log("Total output elements: " + (outChannels * outLength));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model (this may take a while for large convTranspose)...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		// Prepare input
		PackedCollection input = new PackedCollection(batchSize, 64, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("\nRunning layers...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		// Analyze output
		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean isConstant = outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		// Sample values
		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertTrue("Through first decoder block produces constant output - likely convTranspose bug",
				outputStats[3] > 0.001);

		log("\n=== Through First Decoder Block Test PASSED ===");
	}

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
	 * Tests with just ONE residual block to isolate the NaN issue.
	 *
	 * <p>If a single residual block produces NaN, the issue is in the residual() method itself.
	 * If it works, the issue is in the combination of multiple residual blocks.</p>
	 */
	@Test
	public void testSingleResidualBlock() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_single_residual.log"));

		log("=== Layer Validation: Single Residual Block ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;

		// Build input projection + Snake + WNConvTranspose1d + SINGLE residual block
		TraversalPolicy inputShape = shape(batchSize, 64, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// layers.0: Input projection WNConv1d(64 -> 2048)
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, 64, 2048, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		// Decoder block 1 parameters
		int inChannels = 2048;
		int outChannels = 1024;
		int stride = 16;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		int outLength = (latentLength - 1) * stride - 2 * padding + kernel + outputPadding;

		String prefix = "decoder.layers.1";

		// Snake before upsample
		block.add(snake(shape(batchSize, inChannels, latentLength),
				stateDict.get(prefix + ".layers.0.alpha"),
				stateDict.get(prefix + ".layers.0.beta")));

		// WNConvTranspose1d upsample
		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, latentLength,
				kernel, stride, padding, outputPadding,
				stateDict.get(prefix + ".layers.1.weight_g"),
				stateDict.get(prefix + ".layers.1.weight_v"),
				stateDict.get(prefix + ".layers.1.bias")));

		// Just ONE residual block (layers.2)
		log("Adding SINGLE residual block (layers.2)...");
		String resPrefix = prefix + ".layers.2";
		TraversalPolicy resShape = shape(batchSize, outChannels, outLength);

		// Build residual block main path
		SequentialBlock mainPath = new SequentialBlock(resShape);

		// Snake 0
		mainPath.add(snake(resShape,
				stateDict.get(resPrefix + ".layers.0.alpha"),
				stateDict.get(resPrefix + ".layers.0.beta")));

		// WNConv1d k=7
		mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 7, 1, 3,
				stateDict.get(resPrefix + ".layers.1.weight_g"),
				stateDict.get(resPrefix + ".layers.1.weight_v"),
				stateDict.get(resPrefix + ".layers.1.bias")));

		// Snake 2
		mainPath.add(snake(resShape,
				stateDict.get(resPrefix + ".layers.2.alpha"),
				stateDict.get(resPrefix + ".layers.2.beta")));

		// WNConv1d k=1
		mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 1, 1, 0,
				stateDict.get(resPrefix + ".layers.3.weight_g"),
				stateDict.get(resPrefix + ".layers.3.weight_v"),
				stateDict.get(resPrefix + ".layers.3.bias")));

		// Add residual connection
		log("Creating residual wrapper...");
		block.add(residual(mainPath));

		log("Output length after block: " + outLength);
		log("Total output elements: " + (outChannels * outLength));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		// Prepare input
		PackedCollection input = new PackedCollection(batchSize, 64, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("\nRunning layers...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		// Analyze output
		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		// Check for NaN
		boolean hasNaN = Double.isNaN(outputStats[2]);
		log("NaN check: " + (hasNaN ? "HAS NaN (BUG!)" : "NO NaN (OK)"));

		boolean isConstant = !hasNaN && outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		// Sample values
		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertFalse("Single residual block produces NaN - issue is in residual() method", hasNaN);
		assertTrue("Single residual block produces constant output", outputStats[3] > 0.001);

		log("\n=== Single Residual Block Test PASSED ===");
	}

	/**
	 * Tests with TWO residual blocks to narrow down when NaN appears.
	 *
	 * <p>Single residual block works. This test checks if TWO residual blocks cause NaN.</p>
	 */
	@Test
	public void testTwoResidualBlocks() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_two_residuals.log"));

		log("=== Layer Validation: TWO Residual Blocks ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;

		// Build input projection + Snake + WNConvTranspose1d + TWO residual blocks
		TraversalPolicy inputShape = shape(batchSize, 64, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// layers.0: Input projection WNConv1d(64 -> 2048)
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, 64, 2048, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		// Decoder block 1 parameters
		int inChannels = 2048;
		int outChannels = 1024;
		int stride = 16;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		int outLength = (latentLength - 1) * stride - 2 * padding + kernel + outputPadding;

		String prefix = "decoder.layers.1";

		// Snake before upsample
		block.add(snake(shape(batchSize, inChannels, latentLength),
				stateDict.get(prefix + ".layers.0.alpha"),
				stateDict.get(prefix + ".layers.0.beta")));

		// WNConvTranspose1d upsample
		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, latentLength,
				kernel, stride, padding, outputPadding,
				stateDict.get(prefix + ".layers.1.weight_g"),
				stateDict.get(prefix + ".layers.1.weight_v"),
				stateDict.get(prefix + ".layers.1.bias")));

		// TWO residual blocks (layers.2 and layers.3)
		for (int resIdx = 0; resIdx < 2; resIdx++) {
			log("Adding residual block " + resIdx + " (layers." + (resIdx + 2) + ")...");
			String resPrefix = prefix + ".layers." + (resIdx + 2);
			TraversalPolicy resShape = shape(batchSize, outChannels, outLength);

			// Build residual block main path
			SequentialBlock mainPath = new SequentialBlock(resShape);

			// Snake 0
			mainPath.add(snake(resShape,
					stateDict.get(resPrefix + ".layers.0.alpha"),
					stateDict.get(resPrefix + ".layers.0.beta")));

			// WNConv1d k=7
			mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 7, 1, 3,
					stateDict.get(resPrefix + ".layers.1.weight_g"),
					stateDict.get(resPrefix + ".layers.1.weight_v"),
					stateDict.get(resPrefix + ".layers.1.bias")));

			// Snake 2
			mainPath.add(snake(resShape,
					stateDict.get(resPrefix + ".layers.2.alpha"),
					stateDict.get(resPrefix + ".layers.2.beta")));

			// WNConv1d k=1
			mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 1, 1, 0,
					stateDict.get(resPrefix + ".layers.3.weight_g"),
					stateDict.get(resPrefix + ".layers.3.weight_v"),
					stateDict.get(resPrefix + ".layers.3.bias")));

			// Add residual connection
			block.add(residual(mainPath));
		}

		log("Output length after block: " + outLength);
		log("Total output elements: " + (outChannels * outLength));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		// Prepare input
		PackedCollection input = new PackedCollection(batchSize, 64, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("\nRunning layers...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		// Analyze output
		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		// Check for NaN
		boolean hasNaN = Double.isNaN(outputStats[2]);
		log("NaN check: " + (hasNaN ? "HAS NaN (BUG!)" : "NO NaN (OK)"));

		boolean isConstant = !hasNaN && outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		// Sample values
		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertFalse("Two residual blocks produce NaN - issue is in block combination", hasNaN);
		assertTrue("Two residual blocks produce constant output", outputStats[3] > 0.001);

		log("\n=== Two Residual Blocks Test PASSED ===");
	}

	/**
	 * Tests the complete first decoder block including all 3 residual blocks.
	 *
	 * <p>This is the full decoder block without the residual blocks being skipped.
	 * If this produces constant output, the issue is in the residual block composition.</p>
	 */
	@Test
	public void testCompleteFirstDecoderBlock() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_complete_block1.log"));

		log("=== Layer Validation: Complete First Decoder Block (with residual blocks) ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;

		// Build input projection + complete first decoder block
		TraversalPolicy inputShape = shape(batchSize, 64, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// layers.0: Input projection WNConv1d(64 -> 2048)
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, 64, 2048, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		// Decoder block 1 parameters
		int inChannels = 2048;
		int outChannels = 1024;
		int stride = 16;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		int outLength = (latentLength - 1) * stride - 2 * padding + kernel + outputPadding;

		String prefix = "decoder.layers.1";

		// Snake before upsample
		block.add(snake(shape(batchSize, inChannels, latentLength),
				stateDict.get(prefix + ".layers.0.alpha"),
				stateDict.get(prefix + ".layers.0.beta")));

		// WNConvTranspose1d upsample
		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, latentLength,
				kernel, stride, padding, outputPadding,
				stateDict.get(prefix + ".layers.1.weight_g"),
				stateDict.get(prefix + ".layers.1.weight_v"),
				stateDict.get(prefix + ".layers.1.bias")));

		// 3 Residual blocks at output channels
		for (int resIdx = 0; resIdx < 3; resIdx++) {
			String resPrefix = prefix + ".layers." + (resIdx + 2);
			TraversalPolicy resShape = shape(batchSize, outChannels, outLength);

			// Build residual block main path
			SequentialBlock mainPath = new SequentialBlock(resShape);

			// Snake 0
			mainPath.add(snake(resShape,
					stateDict.get(resPrefix + ".layers.0.alpha"),
					stateDict.get(resPrefix + ".layers.0.beta")));

			// WNConv1d k=7
			mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 7, 1, 3,
					stateDict.get(resPrefix + ".layers.1.weight_g"),
					stateDict.get(resPrefix + ".layers.1.weight_v"),
					stateDict.get(resPrefix + ".layers.1.bias")));

			// Snake 2
			mainPath.add(snake(resShape,
					stateDict.get(resPrefix + ".layers.2.alpha"),
					stateDict.get(resPrefix + ".layers.2.beta")));

			// WNConv1d k=1
			mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 1, 1, 0,
					stateDict.get(resPrefix + ".layers.3.weight_g"),
					stateDict.get(resPrefix + ".layers.3.weight_v"),
					stateDict.get(resPrefix + ".layers.3.bias")));

			// Add residual connection
			block.add(residual(mainPath));
		}

		log("Output length after block1: " + outLength);
		log("Total output elements: " + (outChannels * outLength));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model (this may take a while for complete block)...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		// Prepare input
		PackedCollection input = new PackedCollection(batchSize, 64, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("\nRunning layers...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		// Analyze output
		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean isConstant = outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		// Sample values
		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertTrue("Complete first decoder block produces constant output",
				outputStats[3] > 0.001);

		log("\n=== Complete First Decoder Block Test PASSED ===");
	}

	/**
	 * Tests decoder blocks 1 and 2 together to isolate where constant output starts.
	 *
	 * <p>Block 1 alone PASSES with varying output. If blocks 1+2 produce constant output,
	 * the issue is in block 2. Otherwise, it's in later stages.</p>
	 */
	@Test
	public void testDecoderBlocks1And2() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_blocks_1_2.log"));

		log("=== Layer Validation: Decoder Blocks 1+2 ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;
		TraversalPolicy inputShape = shape(batchSize, 64, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// layers.0: Input projection
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, 64, 2048, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		int currentLength = latentLength;

		// Build decoder blocks 1 and 2
		int[][] blockParams = {
				{2048, 1024, 16},  // Block 1: in_channels, out_channels, stride
				{1024, 512, 16}   // Block 2
		};

		for (int blockIdx = 0; blockIdx < 2; blockIdx++) {
			int inChannels = blockParams[blockIdx][0];
			int outChannels = blockParams[blockIdx][1];
			int stride = blockParams[blockIdx][2];
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			int outputPadding = stride - 1;
			int outLength = (currentLength - 1) * stride - 2 * padding + kernel + outputPadding;

			String prefix = "decoder.layers." + (blockIdx + 1);

			// Snake before upsample
			block.add(snake(shape(batchSize, inChannels, currentLength),
					stateDict.get(prefix + ".layers.0.alpha"),
					stateDict.get(prefix + ".layers.0.beta")));

			// WNConvTranspose1d upsample
			block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, currentLength,
					kernel, stride, padding, outputPadding,
					stateDict.get(prefix + ".layers.1.weight_g"),
					stateDict.get(prefix + ".layers.1.weight_v"),
					stateDict.get(prefix + ".layers.1.bias")));

			// 3 Residual blocks
			for (int resIdx = 0; resIdx < 3; resIdx++) {
				String resPrefix = prefix + ".layers." + (resIdx + 2);
				TraversalPolicy resShape = shape(batchSize, outChannels, outLength);

				SequentialBlock mainPath = new SequentialBlock(resShape);
				mainPath.add(snake(resShape,
						stateDict.get(resPrefix + ".layers.0.alpha"),
						stateDict.get(resPrefix + ".layers.0.beta")));
				mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 7, 1, 3,
						stateDict.get(resPrefix + ".layers.1.weight_g"),
						stateDict.get(resPrefix + ".layers.1.weight_v"),
						stateDict.get(resPrefix + ".layers.1.bias")));
				mainPath.add(snake(resShape,
						stateDict.get(resPrefix + ".layers.2.alpha"),
						stateDict.get(resPrefix + ".layers.2.beta")));
				mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 1, 1, 0,
						stateDict.get(resPrefix + ".layers.3.weight_g"),
						stateDict.get(resPrefix + ".layers.3.weight_v"),
						stateDict.get(resPrefix + ".layers.3.bias")));

				block.add(residual(mainPath));
			}

			currentLength = outLength;
			log("After block " + (blockIdx + 1) + ": length=" + currentLength);
		}

		log("Final output elements: " + (512 * currentLength));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		PackedCollection input = new PackedCollection(batchSize, 64, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("\nRunning layers...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean hasNaN = Double.isNaN(outputStats[2]);
		log("NaN check: " + (hasNaN ? "HAS NaN (BUG!)" : "NO NaN (OK)"));

		boolean isConstant = !hasNaN && outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertFalse("Blocks 1+2 produce NaN", hasNaN);
		assertTrue("Blocks 1+2 produce constant output", outputStats[3] > 0.001);

		log("\n=== Decoder Blocks 1+2 Test PASSED ===");
	}

	/**
	 * Tests decoder blocks 1-3 to incrementally narrow down constant output source.
	 *
	 * <p>Blocks 1+2 PASS. If 1-3 produce constant output, the issue is in block 3.
	 * Otherwise, continue to blocks 1-4.</p>
	 */
	@Test
	public void testDecoderBlocks1To3() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_blocks_1_2_3.log"));

		log("=== Layer Validation: Decoder Blocks 1-3 ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;
		TraversalPolicy inputShape = shape(batchSize, 64, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// layers.0: Input projection
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, 64, 2048, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		int currentLength = latentLength;

		// Build decoder blocks 1, 2, and 3
		int[][] blockParams = {
				{2048, 1024, 16},  // Block 1: in_channels, out_channels, stride
				{1024, 512, 16},   // Block 2
				{512, 256, 8}      // Block 3 (stride 8)
		};

		for (int blockIdx = 0; blockIdx < 3; blockIdx++) {
			int inChannels = blockParams[blockIdx][0];
			int outChannels = blockParams[blockIdx][1];
			int stride = blockParams[blockIdx][2];
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			int outputPadding = stride - 1;
			int outLength = (currentLength - 1) * stride - 2 * padding + kernel + outputPadding;

			String prefix = "decoder.layers." + (blockIdx + 1);

			// Snake before upsample
			block.add(snake(shape(batchSize, inChannels, currentLength),
					stateDict.get(prefix + ".layers.0.alpha"),
					stateDict.get(prefix + ".layers.0.beta")));

			// WNConvTranspose1d upsample
			block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, currentLength,
					kernel, stride, padding, outputPadding,
					stateDict.get(prefix + ".layers.1.weight_g"),
					stateDict.get(prefix + ".layers.1.weight_v"),
					stateDict.get(prefix + ".layers.1.bias")));

			// 3 Residual blocks
			for (int resIdx = 0; resIdx < 3; resIdx++) {
				String resPrefix = prefix + ".layers." + (resIdx + 2);
				TraversalPolicy resShape = shape(batchSize, outChannels, outLength);

				SequentialBlock mainPath = new SequentialBlock(resShape);
				mainPath.add(snake(resShape,
						stateDict.get(resPrefix + ".layers.0.alpha"),
						stateDict.get(resPrefix + ".layers.0.beta")));
				mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 7, 1, 3,
						stateDict.get(resPrefix + ".layers.1.weight_g"),
						stateDict.get(resPrefix + ".layers.1.weight_v"),
						stateDict.get(resPrefix + ".layers.1.bias")));
				mainPath.add(snake(resShape,
						stateDict.get(resPrefix + ".layers.2.alpha"),
						stateDict.get(resPrefix + ".layers.2.beta")));
				mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 1, 1, 0,
						stateDict.get(resPrefix + ".layers.3.weight_g"),
						stateDict.get(resPrefix + ".layers.3.weight_v"),
						stateDict.get(resPrefix + ".layers.3.bias")));

				block.add(residual(mainPath));
			}

			currentLength = outLength;
			log("After block " + (blockIdx + 1) + ": length=" + currentLength);
		}

		log("Final output elements: " + (256 * currentLength));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		PackedCollection input = new PackedCollection(batchSize, 64, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("\nRunning layers...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean hasNaN = Double.isNaN(outputStats[2]);
		log("NaN check: " + (hasNaN ? "HAS NaN (BUG!)" : "NO NaN (OK)"));

		boolean isConstant = !hasNaN && outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertFalse("Blocks 1-3 produce NaN", hasNaN);
		assertTrue("Blocks 1-3 produce constant output", outputStats[3] > 0.001);

		log("\n=== Decoder Blocks 1-3 Test PASSED ===");
	}

	/**
	 * Tests all 5 decoder blocks + final Snake (without output projection).
	 *
	 * <p>If this produces varying output but full decoder produces constant,
	 * the issue is in the final output projection (layers.7).</p>
	 */
	@Test
	public void testAllBlocksWithoutOutputProj() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_all_blocks_no_output.log"));

		log("=== Layer Validation: All 5 Blocks + Final Snake (no output proj) ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		float[] latentInput = loadReferenceOutput("latent_input.bin");
		int latentLength = latentInput.length / 64;

		int batchSize = 1;
		TraversalPolicy inputShape = shape(batchSize, 64, latentLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		// layers.0: Input projection
		String l0 = "decoder.layers.0";
		block.add(wnConv1d(batchSize, 64, 2048, latentLength, 7, 1, 3,
				stateDict.get(l0 + ".weight_g"),
				stateDict.get(l0 + ".weight_v"),
				stateDict.get(l0 + ".bias")));

		int currentLength = latentLength;

		// All 5 decoder blocks
		int[][] blockParams = {
				{2048, 1024, 16},  // Block 1
				{1024, 512, 16},   // Block 2
				{512, 256, 8},     // Block 3
				{256, 128, 8},     // Block 4
				{128, 128, 4}      // Block 5
		};

		for (int blockIdx = 0; blockIdx < 5; blockIdx++) {
			int inChannels = blockParams[blockIdx][0];
			int outChannels = blockParams[blockIdx][1];
			int stride = blockParams[blockIdx][2];
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			int outputPadding = stride - 1;
			int outLength = (currentLength - 1) * stride - 2 * padding + kernel + outputPadding;

			String prefix = "decoder.layers." + (blockIdx + 1);

			// Snake before upsample
			block.add(snake(shape(batchSize, inChannels, currentLength),
					stateDict.get(prefix + ".layers.0.alpha"),
					stateDict.get(prefix + ".layers.0.beta")));

			// WNConvTranspose1d upsample
			block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, currentLength,
					kernel, stride, padding, outputPadding,
					stateDict.get(prefix + ".layers.1.weight_g"),
					stateDict.get(prefix + ".layers.1.weight_v"),
					stateDict.get(prefix + ".layers.1.bias")));

			// 3 Residual blocks
			for (int resIdx = 0; resIdx < 3; resIdx++) {
				String resPrefix = prefix + ".layers." + (resIdx + 2);
				TraversalPolicy resShape = shape(batchSize, outChannels, outLength);

				SequentialBlock mainPath = new SequentialBlock(resShape);
				mainPath.add(snake(resShape,
						stateDict.get(resPrefix + ".layers.0.alpha"),
						stateDict.get(resPrefix + ".layers.0.beta")));
				mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 7, 1, 3,
						stateDict.get(resPrefix + ".layers.1.weight_g"),
						stateDict.get(resPrefix + ".layers.1.weight_v"),
						stateDict.get(resPrefix + ".layers.1.bias")));
				mainPath.add(snake(resShape,
						stateDict.get(resPrefix + ".layers.2.alpha"),
						stateDict.get(resPrefix + ".layers.2.beta")));
				mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 1, 1, 0,
						stateDict.get(resPrefix + ".layers.3.weight_g"),
						stateDict.get(resPrefix + ".layers.3.weight_v"),
						stateDict.get(resPrefix + ".layers.3.bias")));

				block.add(residual(mainPath));
			}

			currentLength = outLength;
			log("After block " + (blockIdx + 1) + ": length=" + currentLength);
		}

		// layers.6: Final Snake activation
		String l6 = "decoder.layers.6";
		block.add(snake(shape(batchSize, 128, currentLength),
				stateDict.get(l6 + ".alpha"),
				stateDict.get(l6 + ".beta")));

		log("Final length (after all blocks + Snake): " + currentLength);
		log("Final output elements: " + (128 * currentLength));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model (all 5 blocks + Snake, this will take a while)...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		PackedCollection input = new PackedCollection(batchSize, 64, latentLength);
		for (int i = 0; i < latentInput.length; i++) {
			input.setMem(i, latentInput[i]);
		}

		log("\nRunning layers...");
		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());

		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean hasNaN = Double.isNaN(outputStats[2]);
		log("NaN check: " + (hasNaN ? "HAS NaN (BUG!)" : "NO NaN (OK)"));

		boolean isConstant = !hasNaN && outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertFalse("All blocks + Snake produce NaN", hasNaN);
		assertTrue("All blocks + Snake produce constant output - issue is in blocks 3-5 or Snake",
				outputStats[3] > 0.001);

		log("\n=== All Blocks + Snake Test PASSED ===");
	}

	/**
	 * Tests the output projection layer (WNConv1d 128->2, kernel_size=7) in isolation
	 * with synthetic varying input to verify it doesn't cause constant output.
	 *
	 * <p>This isolates the output projection to determine if it is responsible
	 * for the decoder's constant output issue.</p>
	 */
	@Test
	public void testOutputProjectionIsolation() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_output_proj_isolation.log"));

		int batchSize = 1;
		int inputChannels = 128;
		int outputChannels = 2;
		int sequenceLength = 270000;  // Match the full decoder output length
		int kernelSize = 7;
		int padding = 3;  // (kernel_size - 1) / 2 for same padding

		log("=== Output Projection Isolation Test ===");
		log("Testing WNConv1d(" + inputChannels + "->" + outputChannels + ", kernel=" + kernelSize + ")");
		log("Input shape: [" + batchSize + ", " + inputChannels + ", " + sequenceLength + "]");

		TraversalPolicy inputShape = shape(batchSize, inputChannels, sequenceLength);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		String outProjPrefix = "decoder.layers.7";
		PackedCollection outWeightG = stateDict.get(outProjPrefix + ".weight_g");
		PackedCollection outWeightV = stateDict.get(outProjPrefix + ".weight_v");
		PackedCollection outBias = stateDict.get(outProjPrefix + ".bias");

		log("Output proj weight_g shape: " + outWeightG.getShape());
		log("Output proj weight_v shape: " + outWeightV.getShape());
		log("Output proj bias shape: " + (outBias != null ? outBias.getShape().toString() : "null"));

		Model model = new Model(inputShape);
		model.add(wnConv1d(batchSize, inputChannels, outputChannels, sequenceLength,
				kernelSize, 1, padding, outWeightG, outWeightV, outBias));

		log("\nCompiling output projection model...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		// Create synthetic VARYING input - simulate what blocks 1-5 + Snake would produce
		log("\nGenerating synthetic varying input with std ~300...");
		PackedCollection input = new PackedCollection(batchSize, inputChannels, sequenceLength);
		java.util.Random rand = new java.util.Random(42);
		double inputMean = -22.94;  // Match blocks 1+2 output stats
		double inputStd = 300.48;   // Match blocks 1+2 output stats
		for (int c = 0; c < inputChannels; c++) {
			for (int s = 0; s < sequenceLength; s++) {
				double value = inputMean + inputStd * rand.nextGaussian();
				input.setMem(c * sequenceLength + s, value);
			}
		}

		// Verify input is varying
		float[] inputValues = new float[Math.min(10000, inputChannels * sequenceLength)];
		for (int i = 0; i < inputValues.length; i++) {
			inputValues[i] = (float) input.toDouble(i);
		}
		double[] inputStats = computeStats(inputValues);
		log(String.format("Input stats (first 10k): min=%.2f, max=%.2f, mean=%.2f, std=%.2f",
				inputStats[0], inputStats[1], inputStats[2], inputStats[3]));

		log("\nRunning output projection...");
		long startForward = System.currentTimeMillis();
		PackedCollection output = compiled.forward(input);
		log("Forward time: " + (System.currentTimeMillis() - startForward) + "ms");
		log("Output shape: " + output.getShape());

		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[outputSize];
		for (int i = 0; i < outputSize; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean hasNaN = Double.isNaN(outputStats[2]);
		log("NaN check: " + (hasNaN ? "HAS NaN (BUG!)" : "NO NaN (OK)"));

		boolean isConstant = !hasNaN && outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertFalse("Output projection produces NaN", hasNaN);
		assertTrue("Output projection produces constant output with varying input - this is the bug!",
				outputStats[3] > 0.001);

		log("\n=== Output Projection Isolation Test PASSED ===");
	}

	/**
	 * Tests decoder block 3 alone with real weights to isolate where timing issue occurs.
	 *
	 * <p>Block 3: 512 channels in, 256 channels out, stride 8</p>
	 * <p>Uses synthetic input to simulate block 2 output with much shorter sequence length.</p>
	 */
	@Test
	public void testBlock3Isolation() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			System.out.println("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/layer_block3_isolation.log"));

		log("=== Block 3 Isolation Test ===");
		log("Testing decoder block 3 with real weights, synthetic input");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		int batchSize = 1;
		int inChannels = 512;
		int outChannels = 256;
		int inputLength = 33;  // Reduced from 529 to speed up
		int stride = 8;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		int outLength = (inputLength - 1) * stride - 2 * padding + kernel + outputPadding;

		log("Block 3: " + inChannels + " -> " + outChannels + ", stride=" + stride);
		log("Input length: " + inputLength + ", Output length: " + outLength);
		log("Total output elements: " + (outChannels * outLength));

		TraversalPolicy inputShape = shape(batchSize, inChannels, inputLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		String prefix = "decoder.layers.3";

		// Snake before upsample
		block.add(snake(shape(batchSize, inChannels, inputLength),
				stateDict.get(prefix + ".layers.0.alpha"),
				stateDict.get(prefix + ".layers.0.beta")));

		// WNConvTranspose1d upsample
		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, inputLength,
				kernel, stride, padding, outputPadding,
				stateDict.get(prefix + ".layers.1.weight_g"),
				stateDict.get(prefix + ".layers.1.weight_v"),
				stateDict.get(prefix + ".layers.1.bias")));

		// 3 Residual blocks
		for (int resIdx = 0; resIdx < 3; resIdx++) {
			String resPrefix = prefix + ".layers." + (resIdx + 2);
			TraversalPolicy resShape = shape(batchSize, outChannels, outLength);

			SequentialBlock mainPath = new SequentialBlock(resShape);
			mainPath.add(snake(resShape,
					stateDict.get(resPrefix + ".layers.0.alpha"),
					stateDict.get(resPrefix + ".layers.0.beta")));
			mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 7, 1, 3,
					stateDict.get(resPrefix + ".layers.1.weight_g"),
					stateDict.get(resPrefix + ".layers.1.weight_v"),
					stateDict.get(resPrefix + ".layers.1.bias")));
			mainPath.add(snake(resShape,
					stateDict.get(resPrefix + ".layers.2.alpha"),
					stateDict.get(resPrefix + ".layers.2.beta")));
			mainPath.add(wnConv1d(batchSize, outChannels, outChannels, outLength, 1, 1, 0,
					stateDict.get(resPrefix + ".layers.3.weight_g"),
					stateDict.get(resPrefix + ".layers.3.weight_v"),
					stateDict.get(resPrefix + ".layers.3.bias")));

			block.add(residual(mainPath));
		}

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model...");
		long startCompile = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - startCompile) + "ms");

		// Create synthetic varying input matching blocks 1+2 output stats
		log("\nGenerating synthetic varying input...");
		PackedCollection input = new PackedCollection(batchSize, inChannels, inputLength);
		java.util.Random rand = new java.util.Random(42);
		double inputMean = -22.94;
		double inputStd = 300.48;
		for (int c = 0; c < inChannels; c++) {
			for (int s = 0; s < inputLength; s++) {
				double value = inputMean + inputStd * rand.nextGaussian();
				input.setMem(c * inputLength + s, value);
			}
		}

		log("\nRunning forward pass...");
		long startForward = System.currentTimeMillis();
		PackedCollection output = compiled.forward(input);
		log("Forward time: " + (System.currentTimeMillis() - startForward) + "ms");
		log("Output shape: " + output.getShape());

		int outputSize = (int) output.getMemLength();
		float[] outputValues = new float[Math.min(outputSize, 10000)];
		for (int i = 0; i < outputValues.length; i++) {
			outputValues[i] = (float) output.toDouble(i);
		}

		double[] outputStats = computeStats(outputValues);
		log(String.format("\nOutput stats: min=%.6f, max=%.6f, mean=%.6f, std=%.6f",
				outputStats[0], outputStats[1], outputStats[2], outputStats[3]));

		boolean hasNaN = Double.isNaN(outputStats[2]);
		log("NaN check: " + (hasNaN ? "HAS NaN (BUG!)" : "NO NaN (OK)"));

		boolean isConstant = !hasNaN && outputStats[3] < 0.001;
		log("Constant output check: " + (isConstant ? "CONSTANT (BUG!)" : "VARYING (OK)"));

		log("\nSample output values (first 20):");
		for (int i = 0; i < Math.min(20, outputSize); i++) {
			log(String.format("  [%d] = %.6f", i, outputValues[i]));
		}

		assertFalse("Block 3 produces NaN", hasNaN);
		assertTrue("Block 3 produces constant output from varying input",
				outputStats[3] > 0.001);

		log("\n=== Block 3 Isolation Test PASSED ===");
	}

	/**
	 * Computes statistics for an array of values.
	 *
	 * @return [min, max, mean, stddev]
	 */
	private double[] computeStats(float[] values) {
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		double sum = 0;

		for (float v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
			sum += v;
		}

		double mean = sum / values.length;

		double sumSqDiff = 0;
		for (float v : values) {
			sumSqDiff += (v - mean) * (v - mean);
		}
		double stddev = Math.sqrt(sumSqDiff / values.length);

		return new double[]{min, max, mean, stddev};
	}
}
