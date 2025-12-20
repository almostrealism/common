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
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;

import java.io.IOException;

/**
 * Complete Oobleck Autoencoder implementation using the AR HPC framework.
 *
 * <p>This class assembles the encoder, VAE bottleneck, and decoder into a
 * complete autoencoder for audio compression and reconstruction. The model
 * achieves approximately 65536x compression of stereo audio.</p>
 *
 * <h2>Full Architecture</h2>
 * <pre>
 * Input: (B, 2, L) stereo audio at 44.1kHz
 *     |
 *     v
 * +-----------+
 * |  Encoder  |  Conv1d + 5 EncoderBlocks + Conv1d
 * +-----------+  2 -> 128 -> ... -> 2048 -> 128
 *     |
 *     v
 * (B, 128, L/65536) encoded representation
 *     |
 *     v
 * +------------+
 * | Bottleneck |  Split to mean/logvar, use mean for inference
 * +------------+
 *     |
 *     v
 * (B, 64, L/65536) latent representation
 *     |
 *     v
 * +-----------+
 * |  Decoder  |  Conv1d + 5 DecoderBlocks + Conv1d
 * +-----------+  64 -> 2048 -> ... -> 128 -> 2
 *     |
 *     v
 * Output: (B, 2, L) reconstructed stereo audio
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>
 * // Load weights
 * StateDictionary weights = new StateDictionary("/path/to/weights");
 *
 * // Create autoencoder
 * OobleckAutoEncoder autoencoder = new OobleckAutoEncoder(weights, batchSize, seqLength);
 *
 * // Compile for inference
 * CompiledModel model = autoencoder.compile();
 *
 * // Run inference
 * PackedCollection output = model.forward(inputAudio);
 * </pre>
 *
 * @see OobleckEncoder
 * @see OobleckDecoder
 * @see VAEBottleneck
 */
public class OobleckAutoEncoder implements LayerFeatures {

	private final OobleckEncoder encoder;
	private final VAEBottleneck bottleneck;
	private final OobleckDecoder decoder;
	private final Block autoencoder;
	private final int batchSize;
	private final int inputLength;

	/**
	 * Creates an OobleckAutoEncoder with the given weights.
	 *
	 * @param stateDict StateDictionary containing encoder and decoder weights
	 * @param batchSize Batch size for inference
	 * @param seqLength Input audio sequence length (e.g., 524288 for ~11.9s at 44.1kHz)
	 */
	public OobleckAutoEncoder(StateDictionary stateDict, int batchSize, int seqLength) {
		this.batchSize = batchSize;
		this.inputLength = seqLength;

		// Build encoder
		this.encoder = new OobleckEncoder(stateDict, batchSize, seqLength);
		int latentLength = encoder.getOutputLength();

		// Build bottleneck
		this.bottleneck = new VAEBottleneck(batchSize, latentLength);

		// Build decoder
		this.decoder = new OobleckDecoder(stateDict, batchSize, latentLength);

		// Assemble full autoencoder
		this.autoencoder = buildAutoencoder();
	}

	/**
	 * Creates an OobleckAutoEncoder by loading weights from a directory.
	 *
	 * @param weightsDirectory Directory containing encoder and decoder weight files
	 * @param batchSize Batch size for inference
	 * @param seqLength Input audio sequence length
	 * @return The constructed autoencoder
	 * @throws IOException If weights cannot be loaded
	 */
	public static OobleckAutoEncoder load(String weightsDirectory, int batchSize, int seqLength)
			throws IOException {
		StateDictionary stateDict = new StateDictionary(weightsDirectory);
		return new OobleckAutoEncoder(stateDict, batchSize, seqLength);
	}

	private Block buildAutoencoder() {
		TraversalPolicy inputShape = shape(batchSize, 2, inputLength);
		SequentialBlock block = new SequentialBlock(inputShape);

		block.add(encoder.getEncoder());
		block.add(bottleneck.getBottleneck());
		block.add(decoder.getDecoder());

		return block;
	}

	/**
	 * Compiles the autoencoder for inference.
	 *
	 * @return CompiledModel ready for forward pass
	 */
	public CompiledModel compile() {
		Model model = new Model(shape(batchSize, 2, inputLength));
		model.add(autoencoder);
		return model.compile();
	}

	/**
	 * Gets just the encoder portion for encoding audio to latents.
	 *
	 * @return The encoder Block
	 */
	public Block getEncoder() {
		return encoder.getEncoder();
	}

	/**
	 * Gets just the decoder portion for decoding latents to audio.
	 *
	 * @return The decoder Block
	 */
	public Block getDecoder() {
		return decoder.getDecoder();
	}

	/**
	 * Gets the full autoencoder block.
	 *
	 * @return The complete autoencoder Block
	 */
	public Block getAutoencoder() {
		return autoencoder;
	}

	/**
	 * Gets the batch size this autoencoder was configured for.
	 *
	 * @return Batch size
	 */
	public int getBatchSize() {
		return batchSize;
	}

	/**
	 * Gets the expected input audio length.
	 *
	 * @return Input sequence length
	 */
	public int getInputLength() {
		return inputLength;
	}

	/**
	 * Gets the latent sequence length (approximately input / 32768).
	 *
	 * @return Latent sequence length
	 */
	public int getLatentLength() {
		return encoder.getOutputLength();
	}

	/**
	 * Gets the output audio length (should equal input length).
	 *
	 * @return Output sequence length
	 */
	public int getOutputLength() {
		return decoder.getOutputLength();
	}

	/**
	 * Compiles just the encoder for encoding audio to latents.
	 *
	 * <p>The output is the 128-channel encoder representation before
	 * the VAE bottleneck split.</p>
	 *
	 * @return CompiledModel for encoding
	 */
	public CompiledModel compileEncoder() {
		Model model = new Model(shape(batchSize, 2, inputLength));
		model.add(encoder.getEncoder());
		return model.compile();
	}

	/**
	 * Compiles just the decoder for decoding latents to audio.
	 *
	 * @return CompiledModel for decoding
	 */
	public CompiledModel compileDecoder() {
		int latentLength = encoder.getOutputLength();
		Model model = new Model(shape(batchSize, 64, latentLength));
		model.add(decoder.getDecoder());
		return model.compile();
	}
}
