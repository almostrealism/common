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
import org.almostrealism.model.CompiledModel;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Generates audio using a trained diffusion model.
 *
 * <p>This class is a thin wrapper that:
 * <ol>
 *   <li>Creates a {@link DiffusionSampler} with the appropriate strategy</li>
 *   <li>Delegates sampling to {@link DiffusionSampler}</li>
 *   <li>Decodes latents using the {@link AutoEncoder}</li>
 * </ol>
 *
 * <p><b>This class does NOT own the sampling loop.</b> {@link DiffusionSampler} owns
 * the sampling loop.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create generator
 * AudioDiffusionGenerator generator = new AudioDiffusionGenerator(
 *     diffusionModel, autoEncoder, scheduler, latentShape
 * );
 *
 * // Generate audio
 * WaveData audio = generator.generate(seed);
 *
 * // Save to file
 * generator.generateAndSave(seed, Path.of("output.wav"));
 * }</pre>
 *
 * @see DiffusionSampler
 * @see AutoEncoder
 * @author Michael Murray
 */
public class AudioDiffusionGenerator implements ConsoleFeatures {

	private static final int SAMPLE_RATE = 44100;

	private final CompiledModel diffusionModel;
	private final AutoEncoder autoEncoder;
	private final DiffusionSampler sampler;
	private final TraversalPolicy latentShape;

	private boolean verbose = true;

	/**
	 * Creates an audio generator with DDIM sampling.
	 *
	 * @param diffusionModel Compiled diffusion model
	 * @param autoEncoder AutoEncoder for decoding latents to audio
	 * @param scheduler Noise scheduler for DDIM
	 * @param latentShape Shape of the latent tensor
	 */
	public AudioDiffusionGenerator(CompiledModel diffusionModel,
								   AutoEncoder autoEncoder,
								   DiffusionNoiseScheduler scheduler,
								   TraversalPolicy latentShape) {
		this(diffusionModel, autoEncoder,
				new DDIMSamplingStrategy(scheduler),
				scheduler.getNumSteps(), latentShape);
	}

	/**
	 * Creates an audio generator with DDIM sampling and configurable eta.
	 *
	 * @param diffusionModel Compiled diffusion model
	 * @param autoEncoder AutoEncoder for decoding latents to audio
	 * @param scheduler Noise scheduler for DDIM
	 * @param eta DDIM stochasticity (0 = deterministic, 1 = DDPM)
	 * @param latentShape Shape of the latent tensor
	 */
	public AudioDiffusionGenerator(CompiledModel diffusionModel,
								   AutoEncoder autoEncoder,
								   DiffusionNoiseScheduler scheduler,
								   double eta,
								   TraversalPolicy latentShape) {
		this(diffusionModel, autoEncoder,
				new DDIMSamplingStrategy(scheduler, eta),
				scheduler.getNumSteps(), latentShape);
	}

	/**
	 * Creates an audio generator with a custom sampling strategy.
	 *
	 * @param diffusionModel Compiled diffusion model
	 * @param autoEncoder AutoEncoder for decoding latents to audio
	 * @param strategy Sampling strategy
	 * @param numSteps Number of diffusion steps
	 * @param latentShape Shape of the latent tensor
	 */
	public AudioDiffusionGenerator(CompiledModel diffusionModel,
								   AutoEncoder autoEncoder,
								   SamplingStrategy strategy,
								   int numSteps,
								   TraversalPolicy latentShape) {
		this.diffusionModel = diffusionModel;
		this.autoEncoder = autoEncoder;
		this.latentShape = latentShape;

		// Create sampler - IT OWNS THE LOOP
		this.sampler = new DiffusionSampler(
				this::modelForward,
				strategy,
				numSteps,
				latentShape
		);
	}

	/**
	 * Sets the number of inference steps.
	 *
	 * @param steps Number of steps (fewer = faster, more = higher quality)
	 * @return This generator for chaining
	 */
	public AudioDiffusionGenerator setNumInferenceSteps(int steps) {
		sampler.setNumInferenceSteps(steps);
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
		sampler.setVerbose(verbose);
		return this;
	}

	/**
	 * Generates audio from pure noise.
	 *
	 * @param seed Random seed
	 * @return Generated audio as WaveData
	 */
	public WaveData generate(long seed) {
		if (verbose) log("Generating audio with seed " + seed);

		// Delegate to DiffusionSampler - NO LOOP HERE
		PackedCollection latent = sampler.sample(seed);

		// Decode to audio
		return decodeLatent(latent);
	}

	/**
	 * Generates audio from an existing latent (img2img style).
	 *
	 * @param startLatent Starting latent
	 * @param strength How much to change (0 = keep original, 1 = full generation)
	 * @param seed Random seed
	 * @return Generated audio as WaveData
	 */
	public WaveData generateFrom(PackedCollection startLatent, double strength, long seed) {
		if (verbose) {
			log("Generating audio from latent with seed " + seed + " (strength=" + strength + ")");
		}

		// Delegate to DiffusionSampler - NO LOOP HERE
		PackedCollection latent = sampler.sampleFrom(startLatent, strength, seed);

		// Decode to audio
		return decodeLatent(latent);
	}

	/**
	 * Generates audio and saves to a WAV file.
	 *
	 * @param seed Random seed
	 * @param outputPath Path for the output WAV file
	 * @throws IOException If writing fails
	 */
	public void generateAndSave(long seed, Path outputPath) throws IOException {
		WaveData audio = generate(seed);
		normalizeAudio(audio);
		audio.save(outputPath.toFile());
		log("Saved audio to: " + outputPath);
	}

	/**
	 * Model forward pass adapter for DiffusionSampler.
	 */
	private PackedCollection modelForward(PackedCollection x, PackedCollection t,
										  PackedCollection... conditioning) {
		if (conditioning.length == 0) {
			return diffusionModel.forward(x, t);
		} else {
			PackedCollection[] args = new PackedCollection[conditioning.length + 1];
			args[0] = t;
			for (int i = 0; i < conditioning.length; i++) {
				args[i + 1] = conditioning[i];
			}
			return diffusionModel.forward(x, args);
		}
	}

	/**
	 * Decodes a latent to audio using the AutoEncoder.
	 */
	private WaveData decodeLatent(PackedCollection latent) {
		if (verbose) log("Decoding latent to audio...");

		long start = System.currentTimeMillis();
		PackedCollection audioData = autoEncoder.decode(() -> args -> latent).get().evaluate();

		int audioLength = (int) (audioData.getMemLength() / 2); // 2 channels
		PackedCollection stereoData = new PackedCollection(new TraversalPolicy(2, audioLength));

		// Copy channels
		for (int i = 0; i < audioLength; i++) {
			stereoData.setMem(i, audioData.toDouble(i));
			stereoData.setMem(audioLength + i, audioData.toDouble(audioLength + i));
		}

		if (verbose) {
			log("Decoded in " + (System.currentTimeMillis() - start) + "ms (" +
					(audioLength / (double) SAMPLE_RATE) + " seconds of audio)");
		}

		return new WaveData(stereoData, SAMPLE_RATE);
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

	/**
	 * Returns the underlying sampler for advanced configuration.
	 *
	 * @return The DiffusionSampler
	 */
	public DiffusionSampler getSampler() {
		return sampler;
	}

	/**
	 * Returns the AutoEncoder.
	 *
	 * @return The AutoEncoder
	 */
	public AutoEncoder getAutoEncoder() {
		return autoEncoder;
	}
}
