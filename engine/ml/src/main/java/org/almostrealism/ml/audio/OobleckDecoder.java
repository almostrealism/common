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
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

/**
 * Oobleck Decoder implementation using the AR HPC framework.
 *
 * <p>This decoder is designed to match the Stable Audio Open autoencoder (pretransform)
 * architecture exactly. It reconstructs stereo audio from a compact latent representation.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Input: (B, 64, L/65536) latent representation
 *     |
 *     v
 * layers.0: WNConv1d(64 -> 2048, k=7, p=3)            # Input projection
 *     |
 *     v
 * layers.1: DecoderBlock(2048 -> 1024, stride=16)    # Snake + Upsample + 3 ResBlocks
 * layers.2: DecoderBlock(1024 -> 512, stride=16)
 * layers.3: DecoderBlock(512 -> 256, stride=8)
 * layers.4: DecoderBlock(256 -> 128, stride=8)
 * layers.5: DecoderBlock(128 -> 128, stride=4)
 *     |
 *     v
 * layers.6: Snake(128)                               # Final activation
 *     |
 *     v
 * layers.7: WNConv1d(128 -> 2, k=7, p=3)              # Output projection (no bias)
 *
 * Output: (B, 2, ~L) stereo audio
 * </pre>
 *
 * @see OobleckEncoder
 * @see OobleckAutoEncoder
 */
public class OobleckDecoder extends SequentialBlock {

	private static final int[] STRIDES = {16, 16, 8, 8, 4};
	private static final int[] IN_CHANNELS = {2048, 1024, 512, 256, 128};
	private static final int[] OUT_CHANNELS = {1024, 512, 256, 128, 128};
	private static final int BASE_CHANNELS = 128;
	private static final int LATENT_DIM = 64;
	private static final int NUM_RES_BLOCKS = 3;

	private final StateDictionary stateDict;
	private final int batchSize;
	private final int outputLength;

	/**
	 * Creates an OobleckDecoder with the given weights.
	 *
	 * @param stateDict StateDictionary containing decoder weights with keys matching
	 *                  the Stable Audio Open checkpoint format (decoder.layers.*)
	 * @param batchSize Batch size for inference
	 * @param latentLength Input latent sequence length
	 */
	public OobleckDecoder(StateDictionary stateDict, int batchSize, int latentLength) {
		super(new TraversalPolicy(batchSize, LATENT_DIM, latentLength));
		this.stateDict = stateDict;
		this.batchSize = batchSize;
		this.outputLength = computeOutputLength(latentLength);
		buildDecoder(batchSize, latentLength);
	}

	private static int computeOutputLength(int latentLength) {
		int length = latentLength;
		for (int stride : STRIDES) {
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			int outputPadding = stride - 1;
			length = (length - 1) * stride - 2 * padding + kernel + outputPadding;
		}
		return length;
	}

	private void buildDecoder(int batchSize, int latentLength) {
		String l0 = "decoder.layers.0";
		PackedCollection l0_g = stateDict.get(l0 + ".weight_g");
		PackedCollection l0_v = stateDict.get(l0 + ".weight_v");
		PackedCollection l0_b = stateDict.get(l0 + ".bias");
		int initialChannels = 2048;
		add(wnConv1d(batchSize, LATENT_DIM, initialChannels, latentLength, 7, 1, 3,
				l0_g, l0_v, l0_b));

		int currentLength = latentLength;

		for (int blockIdx = 0; blockIdx < 5; blockIdx++) {
			int inChannels = IN_CHANNELS[blockIdx];
			int outChannels = OUT_CHANNELS[blockIdx];
			int stride = STRIDES[blockIdx];
			int layerIdx = blockIdx + 1;

			int kernel = stride;
			int padding = (kernel - 1) / 2;
			int outputPadding = stride - 1;
			int nextLength = (currentLength - 1) * stride - 2 * padding + kernel + outputPadding;

			add(buildDecoderBlock(batchSize, inChannels, outChannels,
					currentLength, nextLength, stride, layerIdx));

			currentLength = nextLength;
		}

		String l6 = "decoder.layers.6";
		PackedCollection l6_alpha = stateDict.get(l6 + ".alpha");
		PackedCollection l6_beta = stateDict.get(l6 + ".beta");
		add(snake(shape(batchSize, BASE_CHANNELS, currentLength), l6_alpha, l6_beta));

		String l7 = "decoder.layers.7";
		PackedCollection l7_g = stateDict.get(l7 + ".weight_g");
		PackedCollection l7_v = stateDict.get(l7 + ".weight_v");
		add(wnConv1d(batchSize, BASE_CHANNELS, 2, currentLength, 7, 1, 3,
				l7_g, l7_v, null));
	}

	private Block buildDecoderBlock(int batchSize, int inChannels, int outChannels,
									int seqLength, int outLength, int stride, int layerIdx) {
		String prefix = String.format("decoder.layers.%d", layerIdx);
		SequentialBlock block = new SequentialBlock(shape(batchSize, inChannels, seqLength));

		String snakePrefix = prefix + ".layers.0";
		PackedCollection snakeAlpha = stateDict.get(snakePrefix + ".alpha");
		PackedCollection snakeBeta = stateDict.get(snakePrefix + ".beta");
		block.add(snake(shape(batchSize, inChannels, seqLength), snakeAlpha, snakeBeta));

		String convPrefix = prefix + ".layers.1";
		PackedCollection conv_g = stateDict.get(convPrefix + ".weight_g");
		PackedCollection conv_v = stateDict.get(convPrefix + ".weight_v");
		PackedCollection conv_b = stateDict.get(convPrefix + ".bias");

		int kernel = stride;
		int padding = (kernel - 1) / 2;
		int outputPadding = stride - 1;
		block.add(wnConvTranspose1d(batchSize, inChannels, outChannels, seqLength,
				kernel, stride, padding, outputPadding, conv_g, conv_v, conv_b));

		for (int resIdx = 0; resIdx < NUM_RES_BLOCKS; resIdx++) {
			block.add(buildResidualBlock(batchSize, outChannels, outLength,
					prefix + ".layers." + (resIdx + 2)));
		}

		return block;
	}

	private Block buildResidualBlock(int batchSize, int channels, int seqLength, String prefix) {
		TraversalPolicy inputShape = shape(batchSize, channels, seqLength);
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		PackedCollection snake0_alpha = stateDict.get(prefix + ".layers.0.alpha");
		PackedCollection snake0_beta = stateDict.get(prefix + ".layers.0.beta");
		mainPath.add(snake(inputShape, snake0_alpha, snake0_beta));

		PackedCollection conv1_g = stateDict.get(prefix + ".layers.1.weight_g");
		PackedCollection conv1_v = stateDict.get(prefix + ".layers.1.weight_v");
		PackedCollection conv1_b = stateDict.get(prefix + ".layers.1.bias");
		mainPath.add(wnConv1d(batchSize, channels, channels, seqLength, 7, 1, 3,
				conv1_g, conv1_v, conv1_b));

		PackedCollection snake2_alpha = stateDict.get(prefix + ".layers.2.alpha");
		PackedCollection snake2_beta = stateDict.get(prefix + ".layers.2.beta");
		mainPath.add(snake(inputShape, snake2_alpha, snake2_beta));

		PackedCollection conv3_g = stateDict.get(prefix + ".layers.3.weight_g");
		PackedCollection conv3_v = stateDict.get(prefix + ".layers.3.weight_v");
		PackedCollection conv3_b = stateDict.get(prefix + ".layers.3.bias");
		mainPath.add(wnConv1d(batchSize, channels, channels, seqLength, 1, 1, 0,
				conv3_g, conv3_v, conv3_b));

		return residual(mainPath);
	}

	/**
	 * Gets the output audio sequence length after decoding.
	 *
	 * @return Output length
	 */
	public int getOutputLength() {
		return outputLength;
	}

	/**
	 * Gets the batch size this decoder was configured for.
	 *
	 * @return Batch size
	 */
	public int getBatchSize() {
		return batchSize;
	}
}
