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
 * Oobleck Encoder implementation using the AR HPC framework.
 *
 * <p>This encoder is designed to match the Stable Audio Open autoencoder (pretransform)
 * architecture exactly. It compresses stereo audio into a compact latent representation
 * achieving approximately 65536x compression ratio.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Input: (B, 2, L) stereo audio
 *     |
 *     v
 * layers.0: WNConv1d(2 -> 128, k=7, p=3)            # Input projection
 *     |
 *     v
 * layers.1: EncoderBlock(128 -> 128, stride=4)     # 3 ResBlocks + Snake + Downsample
 * layers.2: EncoderBlock(128 -> 256, stride=8)
 * layers.3: EncoderBlock(256 -> 512, stride=8)
 * layers.4: EncoderBlock(512 -> 1024, stride=16)
 * layers.5: EncoderBlock(1024 -> 2048, stride=16)
 *     |
 *     v
 * layers.6: Snake(2048)                            # Final activation
 *     |
 *     v
 * layers.7: WNConv1d(2048 -> 128, k=3, p=1)         # Output projection
 *
 * Output: (B, 128, L/65536) latent
 * </pre>
 *
 * @see OobleckDecoder
 * @see OobleckAutoEncoder
 */
public class OobleckEncoder extends SequentialBlock {

	private static final int[] STRIDES = {4, 8, 8, 16, 16};
	private static final int[] OUT_CHANNELS = {128, 256, 512, 1024, 2048};
	private static final int BASE_CHANNELS = 128;
	private static final int LATENT_DIM = 128;
	private static final int NUM_RES_BLOCKS = 3;

	private final StateDictionary stateDict;
	private final int batchSize;
	private final int outputLength;

	/**
	 * Creates an OobleckEncoder with the given weights.
	 *
	 * @param stateDict StateDictionary containing encoder weights with keys matching
	 *                  the Stable Audio Open checkpoint format (encoder.layers.*)
	 * @param batchSize Batch size for inference
	 * @param seqLength Input audio sequence length
	 */
	public OobleckEncoder(StateDictionary stateDict, int batchSize, int seqLength) {
		super(new TraversalPolicy(batchSize, 2, seqLength));
		this.stateDict = stateDict;
		this.batchSize = batchSize;
		this.outputLength = computeOutputLength(seqLength);
		buildEncoder(batchSize, seqLength);
	}

	private static int computeOutputLength(int seqLength) {
		int length = seqLength;
		for (int stride : STRIDES) {
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			length = (length + 2 * padding - kernel) / stride + 1;
		}
		return length;
	}

	private void buildEncoder(int batchSize, int seqLength) {
		String l0 = "encoder.layers.0";
		PackedCollection l0_g = stateDict.get(l0 + ".weight_g");
		PackedCollection l0_v = stateDict.get(l0 + ".weight_v");
		PackedCollection l0_b = stateDict.get(l0 + ".bias");
		add(wnConv1d(batchSize, 2, BASE_CHANNELS, seqLength, 7, 1, 3, l0_g, l0_v, l0_b));

		int inChannels = BASE_CHANNELS;
		int currentLength = seqLength;

		for (int blockIdx = 0; blockIdx < 5; blockIdx++) {
			int outChannels = OUT_CHANNELS[blockIdx];
			int stride = STRIDES[blockIdx];
			int layerIdx = blockIdx + 1;

			add(buildEncoderBlock(batchSize, inChannels, outChannels,
					currentLength, stride, layerIdx));

			int kernel = stride;
			int padding = (kernel - 1) / 2;
			currentLength = (currentLength + 2 * padding - kernel) / stride + 1;
			inChannels = outChannels;
		}

		String l6 = "encoder.layers.6";
		PackedCollection l6_alpha = stateDict.get(l6 + ".alpha");
		PackedCollection l6_beta = stateDict.get(l6 + ".beta");
		add(snake(shape(batchSize, inChannels, currentLength), l6_alpha, l6_beta));

		String l7 = "encoder.layers.7";
		PackedCollection l7_g = stateDict.get(l7 + ".weight_g");
		PackedCollection l7_v = stateDict.get(l7 + ".weight_v");
		PackedCollection l7_b = stateDict.get(l7 + ".bias");
		add(wnConv1d(batchSize, inChannels, LATENT_DIM, currentLength, 3, 1, 1, l7_g, l7_v, l7_b));
	}

	private Block buildEncoderBlock(int batchSize, int inChannels, int outChannels,
									int seqLength, int stride, int layerIdx) {
		String prefix = String.format("encoder.layers.%d", layerIdx);
		SequentialBlock block = new SequentialBlock(shape(batchSize, inChannels, seqLength));

		for (int resIdx = 0; resIdx < NUM_RES_BLOCKS; resIdx++) {
			block.add(buildResidualBlock(batchSize, inChannels, seqLength,
					prefix + ".layers." + resIdx));
		}

		String snakePrefix = prefix + ".layers.3";
		PackedCollection snakeAlpha = stateDict.get(snakePrefix + ".alpha");
		PackedCollection snakeBeta = stateDict.get(snakePrefix + ".beta");
		block.add(snake(shape(batchSize, inChannels, seqLength), snakeAlpha, snakeBeta));

		String convPrefix = prefix + ".layers.4";
		PackedCollection conv_g = stateDict.get(convPrefix + ".weight_g");
		PackedCollection conv_v = stateDict.get(convPrefix + ".weight_v");
		PackedCollection conv_b = stateDict.get(convPrefix + ".bias");

		int kernel = stride;
		int padding = (kernel - 1) / 2;
		block.add(wnConv1d(batchSize, inChannels, outChannels, seqLength,
				kernel, stride, padding, conv_g, conv_v, conv_b));

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
	 * Gets the output sequence length after encoding.
	 *
	 * @return Output length
	 */
	public int getOutputLength() {
		return outputLength;
	}

	/**
	 * Gets the batch size this encoder was configured for.
	 *
	 * @return Batch size
	 */
	public int getBatchSize() {
		return batchSize;
	}
}
