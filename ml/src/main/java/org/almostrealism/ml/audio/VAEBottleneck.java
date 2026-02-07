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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;

/**
 * VAE Bottleneck for the Oobleck Autoencoder.
 *
 * <p>The bottleneck takes the encoder output (128 channels) and splits it into
 * mean and log-variance components (64 channels each). For inference, we use
 * only the mean for deterministic output. For training, the reparameterization
 * trick would be used: z = mean + std * epsilon.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Encoder Output: (B, 128, L)
 *         |
 *         v
 *    Split channels
 *    /           \
 * mean[:64]    logvar[64:128]
 *    |              |
 *    v              v
 * (B, 64, L)    (unused in inference)
 *    |
 *    v
 * Decoder Input: (B, 64, L)
 * </pre>
 *
 * @see OobleckEncoder
 * @see OobleckDecoder
 * @see OobleckAutoEncoder
 */
public class VAEBottleneck implements LayerFeatures {

	/** Encoder output dimension (mean + logvar concatenated). */
	private static final int ENCODER_DIM = 128;

	/** Latent dimension (mean or logvar individually). */
	private static final int LATENT_DIM = 64;

	private final Block bottleneck;
	private final int batchSize;
	private final int seqLength;

	/**
	 * Creates a VAE bottleneck for inference (deterministic, uses mean only).
	 *
	 * @param batchSize Batch size
	 * @param seqLength Latent sequence length
	 */
	public VAEBottleneck(int batchSize, int seqLength) {
		this.batchSize = batchSize;
		this.seqLength = seqLength;
		this.bottleneck = buildBottleneck(batchSize, seqLength);
	}

	private Block buildBottleneck(int batchSize, int seqLength) {
		TraversalPolicy inputShape = shape(batchSize, ENCODER_DIM, seqLength);
		TraversalPolicy outputShape = shape(batchSize, LATENT_DIM, seqLength);

		// For inference: extract mean (first 64 channels), ignore logvar
		return layer("vaeBottleneck", inputShape.traverseEach(), outputShape.traverseEach(),
				input -> {
					CollectionProducer in = c(input);
					// Extract first 64 channels (mean)
					// Input shape: (batch, 128, seqLength)
					// Output shape: (batch, 64, seqLength)
					return subset(outputShape, in, 0, 0, 0).traverseEach();
				});
	}

	/**
	 * Gets the bottleneck block.
	 *
	 * @return The bottleneck Block
	 */
	public Block getBottleneck() {
		return bottleneck;
	}

	/**
	 * Gets the input dimension (encoder output channels).
	 *
	 * @return Input dimension (128)
	 */
	public int getInputDim() {
		return ENCODER_DIM;
	}

	/**
	 * Gets the output dimension (latent channels).
	 *
	 * @return Output dimension (64)
	 */
	public int getOutputDim() {
		return LATENT_DIM;
	}

	/**
	 * Gets the batch size this bottleneck was configured for.
	 *
	 * @return Batch size
	 */
	public int getBatchSize() {
		return batchSize;
	}

	/**
	 * Gets the sequence length this bottleneck was configured for.
	 *
	 * @return Sequence length
	 */
	public int getSeqLength() {
		return seqLength;
	}
}
