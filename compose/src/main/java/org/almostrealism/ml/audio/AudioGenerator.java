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

import org.almostrealism.audio.WavFile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.function.DoubleConsumer;

/**
 * Audio generation system that combines text conditioning with diffusion-based synthesis.
 * <p>
 * This class extends {@link ConditionalAudioSystem} to provide complete audio generation
 * from text prompts, optionally incorporating sample-based initialization for style transfer.
 * <p>
 * To create an instance, you must provide:
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
 */
public class AudioGenerator extends ConditionalAudioSystem {

	private double audioDurationSeconds;
	private DoubleConsumer progressMonitor;

	private final AudioComposer composer;
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
	}

	public double getAudioDuration() { return audioDurationSeconds; }
	public void setAudioDurationSeconds(double seconds) {
		this.audioDurationSeconds = seconds;
	}

	public DoubleConsumer getProgressMonitor() { return progressMonitor; }
	public void setProgressMonitor(DoubleConsumer monitor) {
		this.progressMonitor = monitor;
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

	public void generateAudio(String prompt, long seed, String outputPath) throws IOException {
		generateAudio(null, prompt, seed, outputPath);
	}

	/**
	 * Generate audio from samples and save to file.
	 */
	public void generateAudio(PackedCollection position, String prompt,
							  long seed, String outputPath) throws IOException {
		double[][] audio = generateAudio(position, prompt, seed);
		try (WavFile f = WavFile.newWavFile(new File(outputPath), 2, audio[0].length, 32, SAMPLE_RATE)) {
			f.writeFrames(audio);
		}
	}

	/**
	 * Generate audio from samples using a position vector to control interpolation.
	 * Samples must be added via addAudio() or addFeatures() before calling this method,
	 * unless position is null (for pure generation).
	 *
	 * @param position PackedCollection representing the position in latent space [composerDim]
	 * @param prompt Text prompt for conditioning
	 * @param seed Random seed for noise generation
	 * @return Generated stereo audio
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
	 * Generate audio with optional sample-based initialization.
	 * <p>
	 * If position is null, performs pure generation from noise.
	 * If position is provided, performs sample-based generation by interpolating
	 * samples (which must be added via addAudio() or addFeatures() first).
	 *
	 * @param position PackedCollection representing the position in latent space [composerDim], or null for pure generation
	 * @param tokenIds Tokenized prompt for conditioning
	 * @param seed Random seed for noise generation
	 * @return Generated stereo audio
	 */
	public double[][] generateAudio(PackedCollection position, long[] tokenIds, long seed) {
		try {
			if (position == null) {
				// Pure generation from noise
				log("Generating audio with seed " + seed +
						" (duration = " + getAudioDuration() + ")");
			} else {
				// Sample-based generation
				log("Generating audio from samples with seed " + seed +
						" (duration = " + getAudioDuration() + ", strength = " + getStrength() + ")");
			}

			// 1. Process tokens through conditioners
			AudioAttentionConditioner.ConditionerOutput conditionerOutputs =
					getConditioner().runConditioners(tokenIds, getAudioDuration());

			// 2. Generate interpolated latent from samples (if position provided)
			PackedCollection interpolatedLatent = null;
			if (position != null) {
				interpolatedLatent = composer.getInterpolatedLatent(cp(position)).evaluate();
			}

			// 3. Run diffusion (with or without sample initialization)
			PackedCollection finalLatent = runDiffusionSteps(
					conditionerOutputs.getCrossAttentionInput(),
					conditionerOutputs.getGlobalCond(),
					interpolatedLatent,
					seed
			);

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
	 * Run diffusion steps with optional sample-based initialization.
	 * <p>
	 * If interpolatedLatent is null, starts from pure noise at step 0.
	 * If interpolatedLatent is provided, adds matched noise and starts from a calculated step based on strength.
	 *
	 * @param crossAttentionInput Conditioning from text tokens
	 * @param globalCond Global conditioning vector
	 * @param interpolatedLatent Optional interpolated latent from samples (null for pure generation)
	 * @param seed Random seed
	 * @return Final latent after diffusion
	 */
	private PackedCollection runDiffusionSteps(PackedCollection crossAttentionInput,
												  PackedCollection globalCond,
												  PackedCollection interpolatedLatent,
												  long seed) {
		Random random = new Random(seed);
		PackedCollection x;
		int startStep;

		if (interpolatedLatent == null) {
			// Pure generation: start from random noise at step 0
			x = new PackedCollection(DIT_X_SHAPE).randnFill(random);
			startStep = 0;
		} else {
			// Special case: strength = 0.0 means no diffusion at all
			if (strength == 0.0) {
				log("Strength is 0.0 - skipping diffusion, returning interpolated latent directly");
				return interpolatedLatent;
			}

			// Sample-based generation: add matched noise and start from calculated step
			float[] sigmas = new float[NUM_STEPS + 1];
			fillSigmas(sigmas, LOGSNR_MAX, 2.0f);

			// Calculate start step based on strength
			// strength = 0.5 → start at middle step (balanced)
			// strength = 1.0 → start at first step (full diffusion from noise)
			startStep = (int) ((1.0 - strength) * NUM_STEPS);
			if (startStep >= NUM_STEPS) startStep = NUM_STEPS - 1;

			// Add matched noise to interpolated latent
			x = addMatchedNoise(interpolatedLatent, sigmas[startStep], random);

			log("Starting diffusion from step " + startStep + "/" + NUM_STEPS +
					" (sigma=" + sigmas[startStep] + ", strength=" + strength + ")");
		}

		return runDiffusionSteps(crossAttentionInput, globalCond, x, startStep, seed);
	}

	/**
	 * Core diffusion loop that can start from any step.
	 */
	private PackedCollection runDiffusionSteps(PackedCollection crossAttentionInput,
												  PackedCollection globalCond,
												  PackedCollection x,
												  int startStep,
												  long seed) {
		// Generate sigma values
		float[] sigmas = new float[NUM_STEPS + 1];
		fillSigmas(sigmas, LOGSNR_MAX, 2.0f);

		Random random = new Random(seed);
		// Advance random to same state as if we started from beginning
		for (int i = 0; i < startStep * DIT_X_SIZE; i++) {
			random.nextGaussian();
		}

		long samplingTotal = 0;
		long modelTotal = 0;

		PackedCollection tPC = new PackedCollection(1);

		double stepCount = NUM_STEPS - startStep;

		// Run diffusion steps starting from startStep
		for (int step = startStep; step < NUM_STEPS; step++) {
			float currT = sigmas[step];
			float nextT = sigmas[step + 1];
			tPC.setMem(0, currT);

			// Run DiffusionTransformer
			long start = System.currentTimeMillis();
			PackedCollection output = getDitModel().forward(x, tPC, crossAttentionInput, globalCond);

			if (progressMonitor != null) {
				progressMonitor.accept((1 + step - startStep) / stepCount);
			}

			checkNan(x, "input after model step " + step);
			checkNan(output, "output after model step " + step);

			modelTotal += System.currentTimeMillis() - start;
			start = System.currentTimeMillis();

			double[] xData = x.toArray();
			double[] outputData = output.toArray();

			// Apply ping-pong sampling
			for (int i = 0; i < DIT_X_SIZE; i++) {
				outputData[i] = xData[i] - (currT * outputData[i]);
			}

			// Generate new noise
			PackedCollection newNoise = new PackedCollection(DIT_X_SHAPE).randnFill(random);
			double[] newNoiseData = newNoise.toArray();

			// Update x for next step
			float[] newX = new float[DIT_X_SIZE];
			for (int i = 0; i < DIT_X_SIZE; i++) {
				newX[i] = (float) ((1.0f - nextT) * outputData[i] + nextT * newNoiseData[i]);
			}

			// Update x for next iteration
			x.setMem(newX);
			checkNan(x, "new input after model step " + step);

			samplingTotal += System.currentTimeMillis() - start;
		}

		log("Diffusion completed - " + samplingTotal + "ms sampling, " + modelTotal + "ms model");

		if (HardwareFeatures.outputMonitoring) {
			double total = x.doubleStream().map(Math::abs).sum();
			log("Average latent amplitude = " + (total / DIT_X_SIZE) + " (" + x.count(Double::isNaN) + " NaN values)");
		}

		return x;
	}

	/**
	 * Add noise to an interpolated latent matching the target sigma level.
	 * This implements the "matched noise addition" strategy for img2img-style generation.
	 * <p>
	 * Formula: noisy_latent = interpolated_latent + sigma * noise
	 *
	 * @param interpolatedLatent The clean interpolated latent from samples
	 * @param targetSigma The noise level to add (should match the starting diffusion step)
	 * @param random Random generator for noise
	 * @return Noisy latent ready for diffusion
	 */
	private PackedCollection addMatchedNoise(PackedCollection interpolatedLatent,
												float targetSigma,
												Random random) {
		// Generate noise and scale by target sigma
		PackedCollection noise = new PackedCollection(DIT_X_SHAPE).randnFill(random);

		// Add scaled noise to interpolated latent
		double[] interpData = interpolatedLatent.toArray();
		double[] noiseData = noise.toArray();
		float[] noisyData = new float[DIT_X_SIZE];

		for (int i = 0; i < DIT_X_SIZE; i++) {
			noisyData[i] = (float) (interpData[i] + targetSigma * noiseData[i]);
		}

		PackedCollection result = new PackedCollection(DIT_X_SHAPE);
		result.setMem(noisyData);

		if (HardwareFeatures.outputMonitoring) {
			double avgSignal = interpolatedLatent.doubleStream().map(Math::abs).average().orElse(0.0);
			double avgNoise = Math.abs(targetSigma);
			log("Added matched noise: signal amplitude=" + avgSignal +
					", noise level=" + avgNoise +
					", SNR=" + (avgSignal / Math.max(avgNoise, 0.001)));
		}

		return result;
	}

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

	private void fillSigmas(float[] arr, float start, float end) {
		int size = arr.length;
		float step = (end - start) / (size - 1);

		// Linspace
		arr[0] = start;
		arr[size - 1] = end;

		for (int i = 1; i < size - 1; i++) {
			arr[i] = arr[i - 1] + step;
		}

		// Apply sigmoid transformation
		for (int i = 0; i < size; i++) {
			arr[i] = 1.0f / (1.0f + (float) Math.exp(arr[i]));
		}

		// Set boundaries
		arr[0] = SIGMA_MAX;
		arr[size - 1] = SIGMA_MIN;
	}

	private void checkNan(PackedCollection x, String context) {
		if (HardwareFeatures.outputMonitoring) {
			long nanCount = x.count(Double::isNaN);

			if (nanCount > 0) {
				warn(nanCount + " NaN values detected at " + context);
			}
		}
	}
}