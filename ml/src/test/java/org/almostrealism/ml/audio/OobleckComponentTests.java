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
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Component-level performance tests for Oobleck decoder.
 *
 * <p>These tests isolate individual components to identify which parts of the
 * decoder architecture are slow to compile. Each test measures build time,
 * compile time, and forward pass time.</p>
 *
 * <p>See AUTOENCODER_PLAN.md for the full test plan and probability estimates.</p>
 */
public class OobleckComponentTests implements TestFeatures, LayerFeatures, ConsoleFeatures {

	private static final int BATCH_SIZE = 1;
	private static final int CHANNELS_SMALL = 128;
	private static final int CHANNELS_LARGE = 1024;

	/**
	 * Helper to time and report component performance.
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
	 */
	private PackedCollection randomWeights(int... dims) {
		PackedCollection w = new PackedCollection(dims);
		w.fill(pos -> (Math.random() - 0.5) * 0.1);
		return w;
	}

	// ==================== Snake Tests ====================

	@Test
	public void testSnakeSmall() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_snake_small.log"));
		log("=== Snake Small Test ===");

		int seqLen = 32;
		int channels = CHANNELS_SMALL;

		PackedCollection alpha = randomWeights(channels);
		PackedCollection beta = randomWeights(channels);
		for (int i = 0; i < channels; i++) {
			beta.setMem(i, Math.abs(beta.toDouble(i)) + 0.1);  // Ensure positive beta
		}

		CellularLayer snake = snake(shape(BATCH_SIZE, channels, seqLen), alpha, beta);
		timeComponent("Snake Small", channels, seqLen, snake);
	}

	@Test
	public void testSnakeMedium() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_snake_medium.log"));
		log("=== Snake Medium Test ===");

		int seqLen = 4096;
		int channels = CHANNELS_SMALL;

		PackedCollection alpha = randomWeights(channels);
		PackedCollection beta = randomWeights(channels);
		for (int i = 0; i < channels; i++) {
			beta.setMem(i, Math.abs(beta.toDouble(i)) + 0.1);
		}

		CellularLayer snake = snake(shape(BATCH_SIZE, channels, seqLen), alpha, beta);
		timeComponent("Snake Medium", channels, seqLen, snake);
	}

	@Test
	public void testSnakeLarge() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_snake_large.log"));
		log("=== Snake Large Test ===");

		int seqLen = 135461;
		int channels = CHANNELS_SMALL;

		PackedCollection alpha = randomWeights(channels);
		PackedCollection beta = randomWeights(channels);
		for (int i = 0; i < channels; i++) {
			beta.setMem(i, Math.abs(beta.toDouble(i)) + 0.1);
		}

		CellularLayer snake = snake(shape(BATCH_SIZE, channels, seqLen), alpha, beta);
		timeComponent("Snake Large", channels, seqLen, snake);
	}

	// ==================== WNConv1d Tests ====================

	@Test
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

	@Test
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

	@Test
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

	@Test
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

	@Test
	public void testResidualBlockSmall() {
		Console.root().addListener(OutputFeatures.fileOutput(
				"test_data/stable_audio/component_residual_small.log"));
		log("=== ResidualBlock Small Test ===");

		int seqLen = 32;
		int channels = CHANNELS_SMALL;

		Block resBlock = buildRandomResidualBlock(channels, seqLen);
		timeComponent("ResidualBlock Small", channels, seqLen, resBlock);
	}

	@Test
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
	 */
	private Block buildRandomResidualBlock(int channels, int seqLen) {
		TraversalPolicy inputShape = shape(BATCH_SIZE, channels, seqLen);
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		// Snake 1
		PackedCollection alpha1 = randomWeights(channels);
		PackedCollection beta1 = randomWeights(channels);
		for (int i = 0; i < channels; i++) beta1.setMem(i, Math.abs(beta1.toDouble(i)) + 0.1);
		mainPath.add(snake(inputShape, alpha1, beta1));

		// WNConv(k=7)
		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 7, 1, 3,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 7),
				randomWeights(channels)));

		// Snake 2
		PackedCollection alpha2 = randomWeights(channels);
		PackedCollection beta2 = randomWeights(channels);
		for (int i = 0; i < channels; i++) beta2.setMem(i, Math.abs(beta2.toDouble(i)) + 0.1);
		mainPath.add(snake(inputShape, alpha2, beta2));

		// WNConv(k=1)
		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 1, 1, 0,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 1),
				randomWeights(channels)));

		return residual(mainPath);
	}

	// ==================== Decoder Block Tests ====================

	@Test
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

	@Test
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
		for (int i = 0; i < inChannels; i++) beta.setMem(i, Math.abs(beta.toDouble(i)) + 0.1);
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
	 */
	private Block buildRandomResidualBlockForDecoderBlock(int channels, int seqLen) {
		TraversalPolicy inputShape = shape(BATCH_SIZE, channels, seqLen);
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		PackedCollection alpha1 = randomWeights(channels);
		PackedCollection beta1 = randomWeights(channels);
		for (int i = 0; i < channels; i++) beta1.setMem(i, Math.abs(beta1.toDouble(i)) + 0.1);
		mainPath.add(snake(inputShape, alpha1, beta1));

		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 7, 1, 3,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 7),
				randomWeights(channels)));

		PackedCollection alpha2 = randomWeights(channels);
		PackedCollection beta2 = randomWeights(channels);
		for (int i = 0; i < channels; i++) beta2.setMem(i, Math.abs(beta2.toDouble(i)) + 0.1);
		mainPath.add(snake(inputShape, alpha2, beta2));

		mainPath.add(wnConv1d(BATCH_SIZE, channels, channels, seqLen, 1, 1, 0,
				randomWeights(channels, 1, 1),
				randomWeights(channels, channels, 1),
				randomWeights(channels)));

		return residual(mainPath);
	}
}
