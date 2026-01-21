/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Generates audio using a trained diffusion model.
 *
 * <p>This class handles the complete generation pipeline:
 * <ol>
 *   <li>Sample noise in latent space</li>
 *   <li>Iteratively denoise using the diffusion model</li>
 *   <li>Decode latent to audio using OobleckDecoder</li>
 *   <li>Save as WAV file</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create generator
 * AudioDiffusionGenerator generator = new AudioDiffusionGenerator(
 *     diffusionModel, decoderWeights, scheduler
 * );
 *
 * // Generate audio
 * WaveData audio = generator.generate(5.0);  // 5 seconds
 *
 * // Save to file
 * generator.generateAndSave(5.0, Path.of("output.wav"));
 * }</pre>
 *
 * @see DiffusionNoiseScheduler
 * @see OobleckDecoder
 * @author Michael Murray
 */
public class AudioDiffusionGenerator implements ConsoleFeatures {

	private static final int SAMPLE_RATE = 44100;
	private static final int LATENT_CHANNELS = 64;
	private static final int COMPRESSION_RATIO = 2048; // Approximate compression of autoencoder

	private final CompiledModel diffusionModel;
	private final CompiledModel decoder;
	private final DiffusionNoiseScheduler scheduler;
	private final int latentLength;
	private final PackedCollection[] conditioningInputs;

	private int numInferenceSteps = 50;
	private double ddimEta = 0.0; // Deterministic by default
	private boolean verbose = true;

	/**
	 * Creates an audio generator.
	 *
	 * @param diffusionModel Compiled diffusion model
	 * @param decoderWeights Weights for the OobleckDecoder
	 * @param scheduler Noise scheduler
	 * @param audioSeconds Target audio length in seconds
	 */
	public AudioDiffusionGenerator(CompiledModel diffusionModel,
								   StateDictionary decoderWeights,
								   DiffusionNoiseScheduler scheduler,
								   double audioSeconds) {
		this.diffusionModel = diffusionModel;
		this.scheduler = scheduler;

		// Calculate latent length for target audio duration
		int audioSamples = (int) (audioSeconds * SAMPLE_RATE);
		this.latentLength = audioSamples / COMPRESSION_RATIO;

		// Build decoder
		log("Building decoder for " + audioSeconds + "s audio (latent length: " + latentLength + ")");
		OobleckDecoder decoderBlock = new OobleckDecoder(decoderWeights, 1, latentLength);
		Model decoderModel = new Model(new TraversalPolicy(1, LATENT_CHANNELS, latentLength));
		decoderModel.add(decoderBlock);
		this.decoder = decoderModel.compile(false); // Inference only, no backprop needed
		log("Decoder built. Output length: " + decoderBlock.getOutputLength());
		this.conditioningInputs = createConditioningInputs();
	}

	/**
	 * Creates an audio generator with pre-compiled decoder.
	 *
	 * @param diffusionModel Compiled diffusion model
	 * @param decoder Compiled decoder model
	 * @param scheduler Noise scheduler
	 * @param latentLength Latent sequence length
	 */
	public AudioDiffusionGenerator(CompiledModel diffusionModel,
								   CompiledModel decoder,
								   DiffusionNoiseScheduler scheduler,
								   int latentLength) {
		this.diffusionModel = diffusionModel;
		this.decoder = decoder;
		this.scheduler = scheduler;
		this.latentLength = latentLength;
		this.conditioningInputs = createConditioningInputs();
	}

	/**
	 * Creates zero-valued conditioning tensors for unconditional generation.
	 * The diffusion model may expect additional inputs beyond the main latent
	 * and timestep (e.g., cross-attention conditioning, global conditioning).
	 * For unconditional generation, we provide zero tensors.
	 */
	private PackedCollection[] createConditioningInputs() {
		int inputCount = diffusionModel.getInputCount();
		// Inputs: 0=main latent, 1=timestep, 2+=conditioning
		if (inputCount <= 2) {
			return new PackedCollection[0];
		}
		PackedCollection[] inputs = new PackedCollection[inputCount - 2];
		for (int i = 2; i < inputCount; i++) {
			TraversalPolicy shape = diffusionModel.getInputShape(i);
			inputs[i - 2] = new PackedCollection(shape); // Initialized to zeros
		}
		return inputs;
	}

	/**
	 * Sets the number of inference steps for DDIM sampling.
	 *
	 * @param steps Number of steps (fewer = faster, more = higher quality)
	 * @return This generator for chaining
	 */
	public AudioDiffusionGenerator setNumInferenceSteps(int steps) {
		this.numInferenceSteps = steps;
		return this;
	}

	/**
	 * Sets the DDIM stochasticity parameter.
	 *
	 * @param eta 0 = deterministic, 1 = DDPM stochastic
	 * @return This generator for chaining
	 */
	public AudioDiffusionGenerator setDDIMEta(double eta) {
		this.ddimEta = eta;
		return this;
	}

	/**
	 * Sets verbose logging.
	 *
	 * @param verbose Whether to log progress
	 * @return This generator for chaining
	 */
	public AudioDiffusionGenerator setVerbose(boolean verbose) {
		this.verbose = verbose;
		return this;
	}

	/**
	 * Generates audio latent using DDIM sampling.
	 *
	 * @return Generated latent representation
	 */
	public PackedCollection generateLatent() {
		if (verbose) log("Starting DDIM sampling with " + numInferenceSteps + " steps");

		// Start from pure noise
		PackedCollection x = scheduler.sampleNoise(1, LATENT_CHANNELS, latentLength);

		// Get DDIM timestep schedule
		int[] timesteps = scheduler.getDDIMTimesteps(numInferenceSteps);

		// Iterative denoising
		for (int i = 0; i < timesteps.length; i++) {
			int t = timesteps[i];
			int tPrev = i < timesteps.length - 1 ? timesteps[i + 1] : -1;

			// Create timestep tensor
			PackedCollection timestepTensor = createTimestepTensor(t);

			// Model predicts noise (include conditioning inputs for unconditional generation)
			PackedCollection predictedNoise = diffusionModel.forward(x, buildForwardArgs(timestepTensor));

			// DDIM step
			x = scheduler.stepDDIM(x, predictedNoise, t, tPrev, ddimEta);

			if (verbose && (i + 1) % 10 == 0) {
				log(String.format("Step %d/%d (t=%d)", i + 1, timesteps.length, t));
			}
		}

		if (verbose) log("Sampling complete");
		return x;
	}

	/**
	 * Generates audio waveform.
	 *
	 * @return Generated audio as WaveData
	 */
	public WaveData generate() {
		// Generate latent
		PackedCollection latent = generateLatent();

		// Decode to audio
		if (verbose) log("Decoding latent to audio...");
		PackedCollection audioData = decoder.forward(latent);

		// Convert to WaveData
		int audioLength = (int) (audioData.getMemLength() / 2); // 2 channels
		PackedCollection stereoData = new PackedCollection(new TraversalPolicy(2, audioLength));

		// Copy channels
		for (int i = 0; i < audioLength; i++) {
			stereoData.setMem(i, audioData.toDouble(i));
			stereoData.setMem(audioLength + i, audioData.toDouble(audioLength + i));
		}

		if (verbose) log("Generated " + (audioLength / (double) SAMPLE_RATE) + " seconds of audio");

		return new WaveData(stereoData, SAMPLE_RATE);
	}

	/**
	 * Generates audio and saves to a WAV file.
	 *
	 * @param outputPath Path for the output WAV file
	 * @throws IOException If writing fails
	 */
	public void generateAndSave(Path outputPath) throws IOException {
		WaveData audio = generate();

		// Normalize before saving
		normalizeAudio(audio);

		audio.save(outputPath.toFile());
		log("Saved audio to: " + outputPath);
	}

	/**
	 * Generates audio from a specific starting latent (useful for interpolation/variation).
	 *
	 * @param startLatent Starting latent to denoise from
	 * @param startTimestep Timestep to start from (higher = more noise)
	 * @return Generated audio
	 */
	public WaveData generateFromLatent(PackedCollection startLatent, int startTimestep) {
		if (verbose) log("Generating from latent at timestep " + startTimestep);

		// Add noise to the starting latent
		PackedCollection noise = scheduler.sampleNoiseLike(startLatent);
		PackedCollection x = scheduler.addNoise(startLatent, noise, startTimestep);

		// Get timesteps from startTimestep down to 0
		int[] allTimesteps = scheduler.getDDIMTimesteps(numInferenceSteps);
		int startIdx = 0;
		for (int i = 0; i < allTimesteps.length; i++) {
			if (allTimesteps[i] <= startTimestep) {
				startIdx = i;
				break;
			}
		}

		// Denoise
		for (int i = startIdx; i < allTimesteps.length; i++) {
			int t = allTimesteps[i];
			int tPrev = i < allTimesteps.length - 1 ? allTimesteps[i + 1] : -1;

			PackedCollection timestepTensor = createTimestepTensor(t);
			PackedCollection predictedNoise = diffusionModel.forward(x, buildForwardArgs(timestepTensor));
			x = scheduler.stepDDIM(x, predictedNoise, t, tPrev, ddimEta);
		}

		// Decode
		PackedCollection audioData = decoder.forward(x);
		int audioLength = (int) (audioData.getMemLength() / 2);
		PackedCollection stereoData = new PackedCollection(new TraversalPolicy(2, audioLength));

		for (int i = 0; i < audioLength; i++) {
			stereoData.setMem(i, audioData.toDouble(i));
			stereoData.setMem(audioLength + i, audioData.toDouble(audioLength + i));
		}

		return new WaveData(stereoData, SAMPLE_RATE);
	}

	private PackedCollection createTimestepTensor(int t) {
		double normalizedT = (double) t / scheduler.getNumSteps();
		PackedCollection timestep = new PackedCollection(1);
		timestep.setMem(0, normalizedT);
		return timestep;
	}

	/**
	 * Builds the full argument array for model forward call,
	 * including timestep and any conditioning inputs.
	 */
	private PackedCollection[] buildForwardArgs(PackedCollection timestepTensor) {
		PackedCollection[] args = new PackedCollection[1 + conditioningInputs.length];
		args[0] = timestepTensor;
		System.arraycopy(conditioningInputs, 0, args, 1, conditioningInputs.length);
		return args;
	}

	private void normalizeAudio(WaveData audio) {
		PackedCollection data = audio.getData();
		double maxAbs = 0;

		for (int i = 0; i < data.getMemLength(); i++) {
			maxAbs = Math.max(maxAbs, Math.abs(data.toDouble(i)));
		}

		if (maxAbs > 1.0) {
			double scale = 0.95 / maxAbs; // Leave some headroom
			for (int i = 0; i < data.getMemLength(); i++) {
				data.setMem(i, data.toDouble(i) * scale);
			}
			if (verbose) log("Normalized audio (max was " + String.format("%.2f", maxAbs) + ")");
		}
	}
}
