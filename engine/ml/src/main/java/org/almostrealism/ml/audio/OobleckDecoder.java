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
 * <p>The structure of every stage lives in the {@value #CODEC_ASSET} asset (the input projection,
 * the {@code oobleck_decoder_block} stage and the decoder's output projection). This class only
 * supplies the stages' widths and strides above; {@link OobleckCodec#addDecoderStages} chains the
 * stages, each built from the asset with its weights.</p>
 *
 * @see OobleckEncoder
 * @see OobleckAutoEncoder
 */
public class OobleckDecoder extends OobleckCodec {

	/** Upsampling strides for each of the five decoder blocks. */
	private static final int[] STRIDES = {16, 16, 8, 8, 4};

	/** Number of output channels for each decoder block. */
	private static final int[] OUT_CHANNELS = {1024, 512, 256, 128, 128};

	/** Channel count the input projection widens the latent to: the first decoder block's input. */
	private static final int INITIAL_CHANNELS = 2048;

	/** Number of audio channels the output projection produces. */
	private static final int AUDIO_CHANNELS = 2;

	/** Latent dimension expected at the decoder input. */
	private static final int LATENT_DIM = 64;

	/** Batch size this decoder was configured for. */
	private final int batchSize;

	/** Output audio sequence length after all upsampling strides are applied. */
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
		super(new TraversalPolicy(batchSize, LATENT_DIM, latentLength), stateDict);
		this.batchSize = batchSize;
		addDecoderStages("decoder", INITIAL_CHANNELS, OUT_CHANNELS, STRIDES, AUDIO_CHANNELS);
		this.outputLength = getOutputShape().length(2);
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
