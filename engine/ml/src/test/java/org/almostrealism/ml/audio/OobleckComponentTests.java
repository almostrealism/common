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
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Component-level performance tests for Oobleck decoder.
 *
 * <p>These tests isolate individual components to identify which parts of the
 * decoder architecture are slow to compile. Each test measures build time,
 * compile time, and forward pass time.</p>
 */
public class OobleckComponentTests extends TestSuiteBase {

	/** Batch size used across all component tests. */
	private static final int BATCH_SIZE = 1;

	/** Channel count for small/single-channel tests. */
	private static final int CHANNELS_SMALL = 128;

	/**
	 * Helper to time and report component performance.
	 *
	 * @param name the component name for logging
	 * @param channels the number of channels
	 * @param seqLen the sequence length
	 * @param block the block to measure
	 */
	private void timeComponent(String name, int channels, int seqLen, Block block) {
		log(String.format("\n=== %s (channels=%d, seqLen=%d) ===", name, channels, seqLen));

		// Build time is already done (block was passed in)
		log("Building model...");
		long buildStart = System.currentTimeMillis();
		Model model = new Model(shape(BATCH_SIZE, channels, seqLen));
		model.add(block);
		long buildTime = System.currentTimeMillis() - buildStart;
		log(String.format("  Model build: %d ms", buildTime));

		// Compile
		log("Compiling...");
		long compileStart = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		long compileTime = System.currentTimeMillis() - compileStart;
		log(String.format("  Compile: %d ms", compileTime));

		// Forward pass
		log("Running forward...");
		PackedCollection input = new PackedCollection(BATCH_SIZE, channels, seqLen);
		input.fill(pos -> Math.random() * 0.1);

		long forwardStart = System.currentTimeMillis();
		PackedCollection output = compiled.forward(input);
		long forwardTime = System.currentTimeMillis() - forwardStart;
		log(String.format("  Forward: %d ms", forwardTime));

		log(String.format("  Output shape: %s", output.getShape()));
		log(String.format("  TOTAL: %d ms", buildTime + compileTime + forwardTime));
	}

	/**
	 * Creates random weights for testing.
	 *
	 * @param dims the tensor dimensions
	 * @return a PackedCollection with random small values
	 */
	private PackedCollection randomWeights(int... dims) {
		PackedCollection w = new PackedCollection(dims);
		w.fill(pos -> (Math.random() - 0.5) * 0.1);
		return w;
	}

	// ==================== Snake Tests ====================

	/**
	 * Tests Snake activation with small sequence length (32 samples).
	 */
	@Test(timeout = 120000)
	public void testSnakeSmall() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_snake_small.log"));
		log("=== Snake Small Test ===");

		int seqLen = 32;
		int channels = CHANNELS_SMALL;

		PackedCollection alpha = randomWeights(channels);
		PackedCollection beta = randomWeights(channels);
		a(cp(beta), abs(cp(beta)).add(0.1)).get().run();  // Ensure positive beta

		CellularLayer snake = snake(shape(BATCH_SIZE, channels, seqLen), alpha, beta);
		timeComponent("Snake Small", channels, seqLen, snake);
	}

	/**
	 * Tests Snake activation with medium sequence length (4096 samples).
	 */
	@Test(timeout = 120000)
	public void testSnakeMedium() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_snake_medium.log"));
		log("=== Snake Medium Test ===");

		int seqLen = 4096;
		int channels = CHANNELS_SMALL;

		PackedCollection alpha = randomWeights(channels);
		PackedCollection beta = randomWeights(channels);
		a(cp(beta), abs(cp(beta)).add(0.1)).get().run();

		CellularLayer snake = snake(shape(BATCH_SIZE, channels, seqLen), alpha, beta);
		timeComponent("Snake Medium", channels, seqLen, snake);
	}

	/**
	 * Tests Snake activation with large sequence length (135461 samples).
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	@TestProperties(knownIssue = true)
	public void testSnakeLarge() {

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_snake_large.log"));
		log("=== Snake Large Test ===");

		int seqLen = 135461;
		int channels = CHANNELS_SMALL;

		PackedCollection alpha = randomWeights(channels);
		PackedCollection beta = randomWeights(channels);
		a(cp(beta), abs(cp(beta)).add(0.1)).get().run();

		CellularLayer snake = snake(shape(BATCH_SIZE, channels, seqLen), alpha, beta);
		timeComponent("Snake Large", channels, seqLen, snake);
	}

	// ==================== WNConv1d Tests ====================

	/**
	 * Tests weight-normalized 1D convolution with small sequence length (32 samples).
	 */
	@Test(timeout = 120000)
	public void testWNConv1dSmall() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_wnconv1d_small.log"));
		log("=== WNConv1d Small Test ===");

		int seqLen = 32;
		int channels = CHANNELS_SMALL;
		int kernel = 7;
		int padding = 3;

		PackedCollection g = randomWeights(channels, 1, 1);
		PackedCollection v = randomWeights(channels, channels, kernel);
		PackedCollection b = randomWeights(channels);

		Block conv = wnConv1d(BATCH_SIZE, channels, channels, seqLen, kernel, 1, padding, g, v, b);
		timeComponent("WNConv1d Small", channels, seqLen, conv);
	}

	/**
	 * Tests weight-normalized 1D convolution with large sequence length (135461 samples).
	 */
	@Test(timeout = 120000)
	public void testWNConv1dLarge() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_wnconv1d_large.log"));
		log("=== WNConv1d Large Test ===");

		int seqLen = 135461;
		int channels = CHANNELS_SMALL;
		int kernel = 7;
		int padding = 3;

		PackedCollection g = randomWeights(channels, 1, 1);
		PackedCollection v = randomWeights(channels, channels, kernel);
		PackedCollection b = randomWeights(channels);

		Block conv = wnConv1d(BATCH_SIZE, channels, channels, seqLen, kernel, 1, padding, g, v, b);
		timeComponent("WNConv1d Large", channels, seqLen, conv);
	}

	// ==================== WNConvTranspose1d Tests ====================

	/**
	 * Tests transposed weight-normalized 1D convolution with 16x upsampling factor.
	 */
	@Test(timeout = 120000)
	@TestProperties(knownIssue = true)
	public void testWNConvTranspose16x() {

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_wnconvtranspose_16x.log"));
		log("=== WNConvTranspose1d 16x Test ===");

		int seqLen = 2;  // Small input
		int inChannels = 2048;
		int outChannels = 1024;
		int stride = 16;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;

		// weightG shape is (inChannels, 1, 1) for transposed conv
		PackedCollection g = randomWeights(inChannels, 1, 1);
		PackedCollection v = randomWeights(inChannels, outChannels, kernel);
		PackedCollection b = randomWeights(outChannels);

		Block conv = wnConvTranspose1d(BATCH_SIZE, inChannels, outChannels, seqLen,
				kernel, stride, padding, outputPadding, g, v, b);
		timeComponent("WNConvTranspose 16x", inChannels, seqLen, conv);
	}

	/**
	 * Tests transposed weight-normalized 1D convolution with 4x upsampling factor and large output (32768 samples).
	 */
	@Test(timeout = 120000)
	public void testWNConvTranspose4xLarge() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_wnconvtranspose_4x_large.log"));
		log("=== WNConvTranspose1d 4x Large Output Test ===");

		// This is the last decoder block scenario: input 32768 -> output ~135K
		int seqLen = 32768;
		int inChannels = CHANNELS_SMALL;
		int outChannels = CHANNELS_SMALL;
		int stride = 4;
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;

		// weightG shape is (inChannels, 1, 1) for transposed conv
		PackedCollection g = randomWeights(inChannels, 1, 1);
		PackedCollection v = randomWeights(inChannels, outChannels, kernel);
		PackedCollection b = randomWeights(outChannels);

		Block conv = wnConvTranspose1d(BATCH_SIZE, inChannels, outChannels, seqLen,
				kernel, stride, padding, outputPadding, g, v, b);
		timeComponent("WNConvTranspose 4x Large", inChannels, seqLen, conv);
	}

	// ==================== Residual Block Tests ====================

	/**
	 * Tests residual block with small sequence length (32 samples, 128 channels).
	 */
	@Test(timeout = 120000)
	public void testResidualBlockSmall() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_residual_small.log"));
		log("=== ResidualBlock Small Test ===");

		int seqLen = 32;
		int channels = CHANNELS_SMALL;

		Block resBlock = buildRandomResidualBlock(channels, seqLen);
		timeComponent("ResidualBlock Small", channels, seqLen, resBlock);
	}

	/**
	 * Tests residual block with large sequence length (135461 samples, 128 channels).
	 */
	@Test(timeout = 120000)
	public void testResidualBlockLarge() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_residual_large.log"));
		log("=== ResidualBlock Large Test ===");

		int seqLen = 135461;
		int channels = CHANNELS_SMALL;

		Block resBlock = buildRandomResidualBlock(channels, seqLen);
		timeComponent("ResidualBlock Large", channels, seqLen, resBlock);
	}

	/**
	 * Builds a residual block with random weights.
	 * Structure: Snake -> WNConv(k=7) -> Snake -> WNConv(k=1) + skip
	 *
	 * @param channels the number of channels
	 * @param seqLen the sequence length
	 * @return a residual block with random weights
	 */
	private Block buildRandomResidualBlock(int channels, int seqLen) {
		TraversalPolicy inputShape = shape(BATCH_SIZE, channels, seqLen);
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		// Snake 1
		PackedCollection alpha1 = randomWeights(channels);
		PackedCollection beta1 = randomWeights(channels);
		a(cp(beta1), abs(cp(beta1)).add(0.1)).get().run();
		mainPath.add(snake(inputShape, alpha1, beta1));

		// WNConv(k=7)
		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 7, 1, 3,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 7),
				randomWeights(channels)));

		// Snake 2
		PackedCollection alpha2 = randomWeights(channels);
		PackedCollection beta2 = randomWeights(channels);
		a(cp(beta2), abs(cp(beta2)).add(0.1)).get().run();
		mainPath.add(snake(inputShape, alpha2, beta2));

		// WNConv(k=1)
		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 1, 1, 0,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 1),
				randomWeights(channels)));

		return residual(mainPath);
	}

	// ==================== Decoder Block Tests ====================

	/**
	 * Tests decoder block 1 (first block: small input, 2048 to 1024 channels).
	 */
	@Test(timeout = 6 * 60000)
	public void testDecoderBlock1() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_decoder_block1.log"));
		log("=== DecoderBlock 1 Test (first block, small) ===");

		// First decoder block: 2 -> 32 samples, 2048 -> 1024 channels
		int seqLen = 2;
		int inChannels = 2048;
		int outChannels = 1024;
		int stride = 16;

		Block block = buildRandomDecoderBlock(inChannels, outChannels, seqLen, stride);
		timeComponent("DecoderBlock 1", inChannels, seqLen, block);
	}

	/**
	 * Tests decoder block 5 (last block: largest output, 32768 samples, 128 channels).
	 */
	@Test(timeout = 5 * 60000)
	@TestProperties(highMemory = true)
	@TestDepth(2)
	public void testDecoderBlock5() {

		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_decoder_block5.log"));
		log("=== DecoderBlock 5 Test (last block, largest) ===");

		// Last decoder block: 32768 -> 135461 samples, 128 -> 128 channels
		int seqLen = 32768;
		int inChannels = CHANNELS_SMALL;
		int outChannels = CHANNELS_SMALL;
		int stride = 4;

		Block block = buildRandomDecoderBlock(inChannels, outChannels, seqLen, stride);
		timeComponent("DecoderBlock 5", inChannels, seqLen, block);
	}

	/**
	 * Builds a decoder block with random weights.
	 * Structure: Snake -> WNConvTranspose -> 3x ResidualBlock
	 *
	 * @param inChannels the input channel count
	 * @param outChannels the output channel count
	 * @param seqLen the input sequence length
	 * @param stride the upsampling stride
	 * @return a decoder block with random weights
	 */
	private Block buildRandomDecoderBlock(int inChannels, int outChannels,
										  int seqLen, int stride) {
		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		int outLength = (seqLen - 1) * stride - 2 * padding + kernel + outputPadding;

		SequentialBlock block = new SequentialBlock(shape(BATCH_SIZE, inChannels, seqLen));

		// Snake before upsample
		PackedCollection alpha = randomWeights(inChannels);
		PackedCollection beta = randomWeights(inChannels);
		a(cp(beta), abs(cp(beta)).add(0.1)).get().run();
		block.add(snake(shape(BATCH_SIZE, inChannels, seqLen), alpha, beta));

		// Upsample conv - weightG shape is (inChannels, 1, 1) for transposed conv
		block.add(wnConvTranspose1d(BATCH_SIZE, inChannels, outChannels, seqLen,
				kernel, stride, padding, outputPadding,
				randomWeights(inChannels, 1, 1),
				randomWeights(inChannels, outChannels, kernel),
				randomWeights(outChannels)));

		// 3 residual blocks
		for (int i = 0; i < 3; i++) {
			block.add(buildRandomResidualBlockForDecoderBlock(outChannels, outLength));
		}

		return block;
	}

	/**
	 * Helper to build residual block at the output size of the decoder block.
	 *
	 * @param channels the number of channels
	 * @param seqLen the sequence length
	 * @return a residual block with random weights
	 */
	private Block buildRandomResidualBlockForDecoderBlock(int channels, int seqLen) {
		TraversalPolicy inputShape = shape(BATCH_SIZE, channels, seqLen);
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		PackedCollection alpha1 = randomWeights(channels);
		PackedCollection beta1 = randomWeights(channels);
		a(cp(beta1), abs(cp(beta1)).add(0.1)).get().run();
		mainPath.add(snake(inputShape, alpha1, beta1));

		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 7, 1, 3,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 7),
				randomWeights(channels)));

		PackedCollection alpha2 = randomWeights(channels);
		PackedCollection beta2 = randomWeights(channels);
		a(cp(beta2), abs(cp(beta2)).add(0.1)).get().run();
		mainPath.add(snake(inputShape, alpha2, beta2));

		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 1, 1, 0,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 1),
				randomWeights(channels)));

		return residual(mainPath);
	}
}