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
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;

/**
 * Base class for conditional audio generation systems that combine text conditioning
 * with diffusion-based audio synthesis.
 * <p>
 * This class coordinates the interaction between:
 * <ul>
 *   <li>{@link Tokenizer} - text tokenization</li>
 *   <li>{@link AudioAttentionConditioner} - conditioning from token IDs</li>
 *   <li>{@link AutoEncoder} - audio encoding/decoding to/from latent space</li>
 *   <li>{@link DitModel} - diffusion transformer for latent generation</li>
 * </ul>
 * <p>
 * Implementations can use different backends (ONNX, native, etc.) by providing
 * appropriate implementations of these interfaces.
 *
 * @see Tokenizer
 * @see AudioAttentionConditioner
 * @see AutoEncoder
 * @see DitModel
 */
public abstract class ConditionalAudioSystem implements Destroyable, CodeFeatures {

	public static double MAX_DURATION = 11.0;

	protected static final int SAMPLE_RATE = 44100;
	protected static final int NUM_STEPS = 8;
	protected static final float LOGSNR_MAX = -6.0f;
	protected static final float SIGMA_MIN = 0.0f;
	protected static final float SIGMA_MAX = 1.0f;

	/** Standard latent dimensions for audio diffusion models. */
	public static final int LATENT_DIMENSIONS = 64;

	/** Standard latent time steps for audio diffusion models. */
	public static final int LATENT_TIME_STEPS = 256;

	// Model dimensions
	protected static final TraversalPolicy DIT_X_SHAPE = new TraversalPolicy(1, LATENT_DIMENSIONS, LATENT_TIME_STEPS);
	protected static final int DIT_X_SIZE = LATENT_DIMENSIONS * LATENT_TIME_STEPS;

	private final Tokenizer tokenizer;
	private final AudioAttentionConditioner conditioner;
	private final AutoEncoder autoencoder;
	private final DitModel ditModel;

	/**
	 * Creates a ConditionalAudioSystem with the provided components.
	 *
	 * @param tokenizer the tokenizer for text processing
	 * @param conditioner the conditioner for generating attention inputs
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 * @param ditStates state dictionary for the diffusion transformer weights
	 */
	public ConditionalAudioSystem(Tokenizer tokenizer,
								  AudioAttentionConditioner conditioner,
								  AutoEncoder autoencoder,
								  StateDictionary ditStates) {
		this(tokenizer, conditioner, autoencoder, ditStates, false);
	}

	/**
	 * Creates a ConditionalAudioSystem with the provided components.
	 *
	 * @param tokenizer the tokenizer for text processing
	 * @param conditioner the conditioner for generating attention inputs
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 * @param ditStates state dictionary for the diffusion transformer weights
	 * @param captureAttentionScores whether to capture attention scores during inference
	 */
	public ConditionalAudioSystem(Tokenizer tokenizer,
								  AudioAttentionConditioner conditioner,
								  AutoEncoder autoencoder,
								  StateDictionary ditStates,
								  boolean captureAttentionScores) {
		this.tokenizer = tokenizer;
		this.conditioner = conditioner;
		this.autoencoder = autoencoder;
		this.ditModel = new DiffusionTransformer(
				64,
				1024,
				16,
				8,
				1,
				768,
				768,
				"rf_denoiser",
				ditStates, captureAttentionScores
		);
	}

	/**
	 * Returns the tokenizer used for text processing.
	 */
	public Tokenizer getTokenizer() { return tokenizer; }

	/**
	 * Returns the audio attention conditioner.
	 */
	public AudioAttentionConditioner getConditioner() { return conditioner; }

	/**
	 * Returns the autoencoder used for audio latent space operations.
	 */
	public AutoEncoder getAutoencoder() { return autoencoder; }

	/**
	 * Returns the diffusion transformer model.
	 */
	public DitModel getDitModel() { return ditModel; }

	@Override
	public void destroy() {
		if (conditioner != null) conditioner.destroy();
		if (autoencoder != null) autoencoder.destroy();
		if (ditModel != null) ditModel.destroy();
	}
}
