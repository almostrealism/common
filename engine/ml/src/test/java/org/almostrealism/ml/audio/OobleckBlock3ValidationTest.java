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
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.junit.Test;

import java.io.IOException;
import java.util.Random;

/**
 * Isolation tests for Oobleck decoder block 3 (512 channels in, 256 out, stride 8).
 *
 * <p>These tests progressively build up the block 3 sub-graph to pinpoint
 * compile-time and numerical issues: Snake-only, transpose-only, Snake+Transpose,
 * full block with 3 residuals, and a single-residual variant.</p>
 */
public class OobleckBlock3ValidationTest extends OobleckValidationBase {

	/**
	 * Tests decoder block 3 alone with real weights to isolate where timing issue occurs.
	 *
	 * <p>Block 3: 512 channels in, 256 channels out, stride 8</p>
	 * <p>Uses synthetic input to simulate block 2 output with much shorter sequence length.</p>
	 */
	@Test(timeout = 120000)
	public void testBlock3Isolation() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			log("Skipping - weights not found at " + WEIGHTS_DIR);
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
		Random rand = new Random(42);
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
	 * Tests just the Snake activation for block 3 settings.
	 */
	@Test(timeout = 120000)
	public void testBlock3SnakeOnly() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			log("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		log("=== Block 3 Snake Only Test ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		int batchSize = 1;
		int channels = 512;
		int length = 33;

		log("Testing Snake: " + channels + " channels, length=" + length);

		TraversalPolicy inputShape = shape(batchSize, channels, length);
		Model model = new Model(inputShape);

		model.add(snake(inputShape,
				stateDict.get("decoder.layers.3.layers.0.alpha"),
				stateDict.get("decoder.layers.3.layers.0.beta")));

		log("\nCompiling model...");
		long start = System.currentTimeMillis();
		model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - start) + "ms");

		log("\n=== Block 3 Snake Only PASSED ===");
	}

	/**
	 * Tests just the WNConvTranspose1d for block 3 settings (512->256, stride=8).
	 */
	@Test(timeout = 120000)
	public void testBlock3TransposeOnly() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			log("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		log("=== Block 3 WNConvTranspose1d Only Test ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		int batchSize = 1;
		int inChannels = 512;
		int outChannels = 256;
		int inputLength = 33;
		int stride = 8;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;

		log("Testing WNConvTranspose1d: " + inChannels + "->" + outChannels + ", stride=" + stride);

		TraversalPolicy inputShape = shape(batchSize, inChannels, inputLength);
		Model model = new Model(inputShape);

		model.add(wnConvTranspose1d(batchSize, inChannels, outChannels, inputLength,
				kernel, stride, padding, outputPadding,
				stateDict.get("decoder.layers.3.layers.1.weight_g"),
				stateDict.get("decoder.layers.3.layers.1.weight_v"),
				stateDict.get("decoder.layers.3.layers.1.bias")));

		log("\nCompiling model...");
		long start = System.currentTimeMillis();
		model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - start) + "ms");

		log("\n=== Block 3 WNConvTranspose1d Only PASSED ===");
	}

	/**
	 * Tests Snake + WNConvTranspose1d for block 3 (no residuals).
	 */
	@Test(timeout = 120000)
	public void testBlock3SnakeAndTranspose() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			log("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		log("=== Block 3 Snake + Transpose Test ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		int batchSize = 1;
		int inChannels = 512;
		int outChannels = 256;
		int inputLength = 33;
		int stride = 8;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;

		log("Testing Snake + WNConvTranspose1d: " + inChannels + "->" + outChannels);

		TraversalPolicy inputShape = shape(batchSize, inChannels, inputLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		block.add(snake(inputShape,
				stateDict.get("decoder.layers.3.layers.0.alpha"),
				stateDict.get("decoder.layers.3.layers.0.beta")));

		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, inputLength,
				kernel, stride, padding, outputPadding,
				stateDict.get("decoder.layers.3.layers.1.weight_g"),
				stateDict.get("decoder.layers.3.layers.1.weight_v"),
				stateDict.get("decoder.layers.3.layers.1.bias")));

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model...");
		long start = System.currentTimeMillis();
		model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - start) + "ms");

		log("\n=== Block 3 Snake + Transpose PASSED ===");
	}

	/**
	 * Tests Snake + WNConvTranspose1d + 1 residual block for block 3.
	 */
	@Test(timeout = 120000)
	public void testBlock3OneResidual() throws IOException {
		if (!WEIGHTS_DIR.toFile().exists()) {
			log("Skipping - weights not found at " + WEIGHTS_DIR);
			return;
		}

		log("=== Block 3 + One Residual Test ===");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		int batchSize = 1;
		int inChannels = 512;
		int outChannels = 256;
		int inputLength = 33;
		int stride = 8;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		int outLength = (inputLength - 1) * stride - 2 * padding + kernel + outputPadding;

		log("Testing Snake + Transpose + 1 Residual: " + inChannels + "->" + outChannels + ", outLength=" + outLength);

		TraversalPolicy inputShape = shape(batchSize, inChannels, inputLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		String prefix = "decoder.layers.3";

		block.add(snake(inputShape,
				stateDict.get(prefix + ".layers.0.alpha"),
				stateDict.get(prefix + ".layers.0.beta")));

		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, inputLength,
				kernel, stride, padding, outputPadding,
				stateDict.get(prefix + ".layers.1.weight_g"),
				stateDict.get(prefix + ".layers.1.weight_v"),
				stateDict.get(prefix + ".layers.1.bias")));

		// Add just 1 residual block
		String resPrefix = prefix + ".layers.2";
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

		Model model = new Model(inputShape);
		model.add(block);

		log("\nCompiling model...");
		long start = System.currentTimeMillis();
		model.compile(false);
		log("Compile time: " + (System.currentTimeMillis() - start) + "ms");

		log("\n=== Block 3 + One Residual PASSED ===");
	}
}
