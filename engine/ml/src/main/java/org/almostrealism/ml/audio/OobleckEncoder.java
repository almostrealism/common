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
import org.almostrealism.ml.StateDictionary;

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
 * <p>The structure of every stage lives in the {@value #CODEC_ASSET} asset (the input projection,
 * the {@code oobleck_encoder_block} stage and the encoder's output projection). This class only
 * supplies the stages' widths and strides above; {@link OobleckCodec#addEncoderStages} chains the
 * stages, each built from the asset with its weights.</p>
 *
 * @see OobleckDecoder
 * @see OobleckAutoEncoder
 */
public class OobleckEncoder extends OobleckCodec {

	/** Downsampling strides for each of the five encoder blocks. */
	private static final int[] STRIDES = {4, 8, 8, 16, 16};

	/** Number of output channels for each encoder block. */
	private static final int[] OUT_CHANNELS = {128, 256, 512, 1024, 2048};

	/** Channel count at the encoder input and the first encoder block. */
	private static final int BASE_CHANNELS = 128;

	/** Channel count at the encoder output (before the final projection). */
	private static final int LATENT_DIM = 128;

	/** Batch size this encoder was configured for. */
	private final int batchSize;

	/** Output latent sequence length after all downsampling strides are applied. */
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
		super(new TraversalPolicy(batchSize, 2, seqLength), stateDict);
		this.batchSize = batchSize;
		addEncoderStages("encoder", BASE_CHANNELS, OUT_CHANNELS, STRIDES, LATENT_DIM);
		this.outputLength = getOutputShape().length(2);
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
