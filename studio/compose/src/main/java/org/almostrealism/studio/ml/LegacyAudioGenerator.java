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
import org.almostrealism.ml.audio.PingPongSamplingStrategy;
import org.almostrealism.ml.audio.AudioAttentionConditioner;
import org.almostrealism.ml.audio.DiffusionSampler;
import org.almostrealism.ml.audio.AutoEncoder;

import org.almostrealism.audio.WavFile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;

import java.io.File;
import java.io.IOException;
import java.util.function.DoubleConsumer;

/**
 * Legacy implementation of AudioGenerator preserved for validation testing.
 * This class contains the original implementation before refactoring to use
 * AudioDiffusionGenerator internally.
 *
 * @deprecated Use {@link AudioGenerator} instead. This class exists only for
 *             validation testing and will be removed after verification.
 */
@Deprecated
public class LegacyAudioGenerator extends ConditionalAudioSystem {

	/** Target audio duration in seconds. */
	private double audioDurationSeconds;

	/** Optional consumer notified of generation progress in [0, 1]. */
	private DoubleConsumer progressMonitor;

	/** Composer used to interpolate between latent audio representations. */
	private final AudioComposer composer;

	/** The diffusion sampler that owns the denoising loop. */
	private final DiffusionSampler sampler;

	/** Strength controlling how much the seed audio is preserved (0 = preserve, 1 = full noise). */
	private double strength;

	/**
	 * Creates a LegacyAudioGenerator with the provided components.
	 */
	public LegacyAudioGenerator(Tokenizer tokenizer,
						  AudioAttentionConditioner conditioner,
						  AutoEncoder autoencoder,
						  StateDictionary ditStates) {
		this(tokenizer, conditioner, autoencoder, ditStates, 8, System.currentTimeMillis());
	}

	/**
	 * Creates a LegacyAudioGenerator with the provided components and composer configuration.
	 */
	public LegacyAudioGenerator(Tokenizer tokenizer,
						  AudioAttentionConditioner conditioner,
						  AutoEncoder autoencoder,
						  StateDictionary ditStates,
						  int composerDim,
						  long composerSeed) {
		super(tokenizer, conditioner, autoencoder, ditStates);
		composer = new AudioComposer(getAutoencoder(), composerDim, composerSeed);
		audioDurationSeconds = 10.0;
		strength = 0.5;

		// Create sampler with ping-pong strategy - IT OWNS THE LOOP
		this.sampler = new DiffusionSampler(
				getDiffusionModel(),
				new PingPongSamplingStrategy(),
				NUM_STEPS,
				DIT_X_SHAPE
		);
	}

	/** Returns the target audio generation duration in seconds. */
	public double getAudioDuration() { return audioDurationSeconds; }

	/**
	 * Sets the target audio generation duration.
	 *
	 * @param seconds the desired duration in seconds
	 */
	public void setAudioDurationSeconds(double seconds) {
		this.audioDurationSeconds = seconds;
	}

	/** Returns the progress monitor consumer, or {@code null} if not configured. */
	public DoubleConsumer getProgressMonitor() { return progressMonitor; }

	/**
	 * Sets the progress monitor consumer for generation progress updates.
	 *
	 * @param monitor the consumer receiving progress in [0, 1]
	 */
	public void setProgressMonitor(DoubleConsumer monitor) {
		this.progressMonitor = monitor;
		sampler.setProgressCallback(monitor);
	}

	/**
	 * Sets the diffusion strength parameter.
	 *
	 * @param strength a value in [0, 1]: 0 preserves the seed audio, 1 generates from pure noise
	 * @throws IllegalArgumentException if strength is outside [0, 1]
	 */
	public void setStrength(double strength) {
		if (strength < 0.0 || strength > 1.0) {
			throw new IllegalArgumentException("Strength must be between 0.0 and 1.0");
		}
		this.strength = strength;
	}

	/** Returns the current diffusion strength parameter. */
	public double getStrength() { return strength; }

	/** Returns the audio composer used for latent interpolation. */
	public AudioComposer getComposer() { return composer; }

	/** Returns the dimensionality of the audio composer's interpolation space. */
	public int getComposerDimension() { return composer.getEmbeddingDimension(); }

	/**
	 * Encodes and adds the given raw audio as a composable source.
	 *
	 * @param audio the raw audio to encode and add
	 */
	public void addAudio(PackedCollection audio) {
		composer.addAudio(cp(audio));
	}

	/**
	 * Adds pre-encoded latent features as a composable source.
	 *
	 * @param features the pre-encoded latent features to add
	 */
	public void addFeatures(PackedCollection features) {
		composer.addSource(cp(features));
	}

	/**
	 * Generates audio from the given text prompt and seed, writing WAV output to the given path.
	 *
	 * @param prompt     the text prompt for conditioning
	 * @param seed       the random seed for reproducible generation
	 * @param outputPath the WAV output file path
	 * @throws IOException if writing the output file fails
	 */
	public void generateAudio(String prompt, long seed, String outputPath) throws IOException {
		generateAudio(null, prompt, seed, outputPath);
	}

	/**
	 * Generates audio from an optional latent position and text prompt, writing WAV output.
	 *
	 * @param position   optional latent position for sample-based generation; {@code null} for text-only
	 * @param prompt     the text prompt for conditioning
	 * @param seed       the random seed
	 * @param outputPath the WAV output file path
	 * @throws IOException if writing the output file fails
	 */
	public void generateAudio(PackedCollection position, String prompt,
							  long seed, String outputPath) throws IOException {
		double[][] audio = generateAudio(position, prompt, seed);
		try (WavFile f = WavFile.newWavFile(new File(outputPath), 2, audio[0].length, 32, SAMPLE_RATE)) {
			f.writeFrames(audio);
		}
	}

	/**
	 * Generates stereo audio from an optional latent position and text prompt.
	 *
	 * @param position optional latent position; {@code null} for text-only generation
	 * @param prompt   the text prompt
	 * @param seed     the random seed
	 * @return two-channel audio as a 2D double array
	 */
	public double[][] generateAudio(PackedCollection position, String prompt, long seed) {
		try {
			return generateAudio(position, getTokenizer().encodeAsLong(prompt), seed);
		} finally {
			if (progressMonitor != null) {
				progressMonitor.accept(1.0);
			}
		}
	}

	/**
	 * Generates stereo audio from an optional latent position and pre-tokenized prompt.
	 *
	 * @param position  optional latent position; {@code null} for text-only generation
	 * @param tokenIds  the pre-tokenized prompt token IDs
	 * @param seed      the random seed
	 * @return two-channel audio as a 2D double array
	 */
	public double[][] generateAudio(PackedCollection position, long[] tokenIds, long seed) {
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

			// 3. Run diffusion - DELEGATE TO DiffusionSampler - NO LOOP HERE
			PackedCollection finalLatent;
			if (interpolatedLatent == null) {
				finalLatent = sampler.sample(seed, crossAttnCond, globalCond);
			} else {
				finalLatent = sampler.sampleFrom(interpolatedLatent, strength, seed,
						crossAttnCond, globalCond);
			}

			// 4. Decode audio
			long start = System.currentTimeMillis();
			double[][] audio = decodeAudio(finalLatent.reshape(LATENT_DIMENSIONS, -1));
			log((System.currentTimeMillis() - start) + "ms for autoencoder");
			return audio;
		} finally {
			if (progressMonitor != null) {
				progressMonitor.accept(1.0);
			}
		}
	}

	/**
	 * Decodes the given latent tensor to stereo audio using the autoencoder.
	 *
	 * @param latent the latent tensor to decode
	 * @return two-channel audio as a 2D double array
	 */
	private double[][] decodeAudio(PackedCollection latent) {
		PackedCollection result = getAutoencoder().decode(cp(latent)).evaluate();

		double[] data = result.toArray();
		int totalSamples = data.length;
		int channelSamples = totalSamples / 2; // Stereo audio, 2 channels
		int finalSamples = (int) (getAudioDuration() * SAMPLE_RATE);

		double[][] stereoAudio = new double[2][finalSamples];
		for (int i = 0; i < finalSamples; i++) {
			stereoAudio[0][i] = data[i];
			stereoAudio[1][i] = data[i + channelSamples];
		}

		return stereoAudio;
	}

	/**
	 * Returns the diffusion sampler used for the denoising loop.
	 *
	 * @return the {@link DiffusionSampler} instance
	 */
	public DiffusionSampler getSampler() {
		return sampler;
	}
}
