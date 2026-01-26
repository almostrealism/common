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

package org.almostrealism.ml.audio;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;

import java.io.File;
import java.util.function.DoubleConsumer;

/**
 * Audio generation system that combines text conditioning with diffusion-based synthesis.
 *
 * <p>This class extends {@link ConditionalAudioSystem} to provide complete audio generation
 * from text prompts, optionally incorporating sample-based initialization for style transfer.
 *
 * <p><b>This class does NOT own the sampling loop.</b> It delegates to
 * {@link AudioDiffusionGenerator} which in turn delegates to {@link DiffusionSampler}.
 *
 * <p>To create an instance, you must provide:
 * <ul>
 *   <li>{@link Tokenizer} - for text tokenization (e.g., SentencePieceTokenizer)</li>
 *   <li>{@link AudioAttentionConditioner} - for conditioning (e.g., OnnxAudioConditioner)</li>
 *   <li>{@link AutoEncoder} - for audio latent space operations (e.g., OnnxAutoEncoder)</li>
 *   <li>{@link StateDictionary} - weights for the diffusion transformer</li>
 * </ul>
 *
 * @see Tokenizer
 * @see AudioAttentionConditioner
 * @see AutoEncoder
 * @see AudioDiffusionGenerator
 * @see DiffusionSampler
 */
public class AudioGenerator extends ConditionalAudioSystem {

	private double audioDurationSeconds;
	private DoubleConsumer progressMonitor;

	private final AudioComposer composer;
	private final AudioDiffusionGenerator generator;
	private double strength;

	/**
	 * Creates an AudioGenerator with the provided components.
	 *
	 * @param tokenizer the tokenizer for text processing
	 * @param conditioner the conditioner for generating attention inputs
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 * @param ditStates state dictionary for the diffusion transformer weights
	 */
	public AudioGenerator(Tokenizer tokenizer,
						  AudioAttentionConditioner conditioner,
						  AutoEncoder autoencoder,
						  StateDictionary ditStates) {
		this(tokenizer, conditioner, autoencoder, ditStates, 8, System.currentTimeMillis());
	}

	/**
	 * Creates an AudioGenerator with the provided components and composer configuration.
	 *
	 * @param tokenizer the tokenizer for text processing
	 * @param conditioner the conditioner for generating attention inputs
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 * @param ditStates state dictionary for the diffusion transformer weights
	 * @param composerDim dimension of the latent interpolation space
	 * @param composerSeed random seed for the audio composer
	 */
	public AudioGenerator(Tokenizer tokenizer,
						  AudioAttentionConditioner conditioner,
						  AutoEncoder autoencoder,
						  StateDictionary ditStates,
						  int composerDim,
						  long composerSeed) {
		super(tokenizer, conditioner, autoencoder, ditStates);
		composer = new AudioComposer(getAutoencoder(), composerDim, composerSeed);
		audioDurationSeconds = 10.0;
		strength = 0.5;

		// Delegate to AudioDiffusionGenerator with PingPong strategy
		this.generator = new AudioDiffusionGenerator(
				getDiffusionModel(),
				getAutoencoder(),
				new PingPongSamplingStrategy(),
				NUM_STEPS,
				DIT_X_SHAPE
		);
	}

	public double getAudioDuration() { return audioDurationSeconds; }
	public void setAudioDurationSeconds(double seconds) {
		this.audioDurationSeconds = seconds;
	}

	public DoubleConsumer getProgressMonitor() { return progressMonitor; }
	public void setProgressMonitor(DoubleConsumer monitor) {
		this.progressMonitor = monitor;
		generator.getSampler().setProgressCallback(monitor);
	}

	/**
	 * Set the strength parameter for sample-based generation.
	 * 0.0 = preserve samples exactly (no diffusion)
	 * 0.5 = balanced between preservation and generation (default)
	 * 1.0 = full diffusion from noise (ignore samples)
	 *
	 * @param strength value between 0.0 and 1.0
	 */
	public void setStrength(double strength) {
		if (strength < 0.0 || strength > 1.0) {
			throw new IllegalArgumentException("Strength must be between 0.0 and 1.0");
		}
		this.strength = strength;
	}

	public double getStrength() { return strength; }

	public AudioComposer getComposer() { return composer; }

	public int getComposerDimension() { return composer.getEmbeddingDimension(); }

	/**
	 * Add raw audio to be used as a starting point for generation.
	 * The audio will be encoded into the latent space and aggregated with other samples.
	 *
	 * @param audio PackedCollection of audio data (stereo: [2, frames] or mono: [frames])
	 */
	public void addAudio(PackedCollection audio) {
		composer.addAudio(cp(audio));
	}

	/**
	 * Add pre-encoded latent features as a starting point for generation.
	 *
	 * @param features PackedCollection of latent features [64, 256]
	 */
	public void addFeatures(PackedCollection features) {
		composer.addSource(cp(features));
	}

	/**
	 * Generate audio and save to file.
	 *
	 * @param prompt Text prompt for conditioning
	 * @param seed Random seed for noise generation
	 * @param outputPath Path to save the WAV file
	 */
	public void generateAudio(String prompt, long seed, String outputPath) {
		generateAudio(null, prompt, seed, outputPath);
	}

	/**
	 * Generate audio from samples and save to file.
	 *
	 * @param position PackedCollection representing the position in latent space [composerDim], or null for pure generation
	 * @param prompt Text prompt for conditioning
	 * @param seed Random seed for noise generation
	 * @param outputPath Path to save the WAV file
	 */
	public void generateAudio(PackedCollection position, String prompt,
							  long seed, String outputPath) {
		WaveData waveData = generateAudio(position, prompt, seed);
		waveData.save(new File(outputPath));
	}

	/**
	 * Generate audio from samples using a position vector to control interpolation.
	 * Samples must be added via addAudio() or addFeatures() before calling this method,
	 * unless position is null (for pure generation).
	 *
	 * @param position PackedCollection representing the position in latent space [composerDim]
	 * @param prompt Text prompt for conditioning
	 * @param seed Random seed for noise generation
	 * @return Generated audio as WaveData
	 */
	public WaveData generateAudio(PackedCollection position, String prompt, long seed) {
		try {
			return generateAudio(position, getTokenizer().encodeAsLong(prompt), seed);
		} finally {
			if (progressMonitor != null) {
				progressMonitor.accept(1.0);
			}
		}
	}

	/**
	 * Generate audio with optional sample-based initialization.
	 * <p>
	 * If position is null, performs pure generation from noise.
	 * If position is provided, performs sample-based generation by interpolating
	 * samples (which must be added via addAudio() or addFeatures() first).
	 *
	 * @param position PackedCollection representing the position in latent space [composerDim], or null for pure generation
	 * @param tokenIds Tokenized prompt for conditioning
	 * @param seed Random seed for noise generation
	 * @return Generated audio as WaveData
	 */
	public WaveData generateAudio(PackedCollection position, long[] tokenIds, long seed) {
		try {
			if (position == null) {
				log("Generating audio with seed " + seed +
						" (duration = " + getAudioDuration() + ")");
			} else {
				log("Generating audio from samples with seed " + seed +
						" (duration = " + getAudioDuration() + ", strength = " + getStrength() + ")");
			}

			// 1. Process tokens through conditioners
			AudioAttentionConditioner.ConditionerOutput conditionerOutputs =
					getConditioner().runConditioners(tokenIds, getAudioDuration());

			PackedCollection crossAttnCond = conditionerOutputs.getCrossAttentionInput();
			PackedCollection globalCond = conditionerOutputs.getGlobalCond();

			// 2. Generate interpolated latent from samples (if position provided)
			PackedCollection interpolatedLatent = null;
			if (position != null) {
				interpolatedLatent = composer.getInterpolatedLatent(cp(position)).evaluate();
			}

			// 3. Run diffusion - DELEGATE TO AudioDiffusionGenerator - NO LOOP HERE
			if (interpolatedLatent == null) {
				return generator.generate(seed, crossAttnCond, globalCond);
			} else {
				return generator.generateFrom(interpolatedLatent, strength, seed,
						crossAttnCond, globalCond);
			}
		} finally {
			if (progressMonitor != null) {
				progressMonitor.accept(1.0);
			}
		}
	}

	/**
	 * Returns the underlying sampler for advanced configuration.
	 *
	 * @return The DiffusionSampler
	 */
	public DiffusionSampler getSampler() {
		return generator.getSampler();
	}

	/**
	 * Returns the underlying AudioDiffusionGenerator.
	 *
	 * @return The AudioDiffusionGenerator
	 */
	public AudioDiffusionGenerator getGenerator() {
		return generator;
	}
}
