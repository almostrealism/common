/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.ml;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.ml.audio.DiffusionModel;
import org.almostrealism.ml.audio.AudioAttentionConditioner;
import org.almostrealism.ml.audio.AutoEncoder;

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
 *   <li>{@link DiffusionModel} - diffusion transformer for latent generation</li>
 * </ul>
 * <p>
 * Implementations can use different backends (ONNX, native, etc.) by providing
 * appropriate implementations of these interfaces.
 *
 * @see Tokenizer
 * @see AudioAttentionConditioner
 * @see AutoEncoder
 * @see DiffusionModel
 */
public abstract class ConditionalAudioSystem implements Destroyable, CodeFeatures {

	/** Maximum audio generation duration in seconds. */
	public static double MAX_DURATION = 11.0;

	/** Audio sample rate used throughout the system. */
	protected static final int SAMPLE_RATE = 44100;

	/** Number of diffusion sampling steps. */
	protected static final int NUM_STEPS = 8;

	/** Maximum log-SNR value for the diffusion schedule. */
	protected static final float LOGSNR_MAX = -6.0f;

	/** Minimum sigma value for the noise schedule. */
	protected static final float SIGMA_MIN = 0.0f;

	/** Maximum sigma value for the noise schedule. */
	protected static final float SIGMA_MAX = 1.0f;

	/** Standard latent dimensions for audio diffusion models. */
	public static final int LATENT_DIMENSIONS = 64;

	/** Standard latent time steps for audio diffusion models. */
	public static final int LATENT_TIME_STEPS = 256;

	/** Traversal policy representing a single latent tensor [1 x LATENT_DIMENSIONS x LATENT_TIME_STEPS]. */
	protected static final TraversalPolicy DIT_X_SHAPE = new TraversalPolicy(1, LATENT_DIMENSIONS, LATENT_TIME_STEPS);

	/** Total number of elements in a single latent tensor. */
	protected static final int DIT_X_SIZE = LATENT_DIMENSIONS * LATENT_TIME_STEPS;

	/** Tokenizer for encoding text conditioning inputs. */
	private final Tokenizer tokenizer;

	/** Conditioner that generates attention inputs from token embeddings. */
	private final AudioAttentionConditioner conditioner;

	/** Autoencoder used to encode audio into latent space and decode back. */
	private final AutoEncoder autoencoder;

	/** The diffusion transformer model that generates audio in latent space. */
	private final DiffusionModel ditModel;

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
	public DiffusionModel getDiffusionModel() { return ditModel; }

	@Override
	public void destroy() {
		if (conditioner != null) conditioner.destroy();
		if (autoencoder != null) autoencoder.destroy();
		if (ditModel != null) ditModel.destroy();
	}
}
