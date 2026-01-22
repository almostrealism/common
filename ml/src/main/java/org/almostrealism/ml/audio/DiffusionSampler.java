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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Random;
import java.util.function.DoubleConsumer;

/**
 * Diffusion sampler that owns the sampling (inference) loop.
 *
 * <p>This class is the single authoritative implementation of the diffusion
 * sampling loop. All audio generators should delegate to this class rather
 * than implementing their own loops.
 *
 * <p><b>YOU DO NOT OWN THE SAMPLING LOOP.</b> If you find yourself writing
 * a for-loop that iterates through diffusion timesteps, you are doing it wrong.
 * Use this class instead.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create sampler with strategy
 * DiffusionSampler sampler = new DiffusionSampler(
 *     model::forward,
 *     new PingPongSamplingStrategy(),
 *     numSteps,
 *     latentShape
 * );
 *
 * // Sample from noise
 * PackedCollection latent = sampler.sample(seed);
 *
 * // Sample from existing latent (img2img style)
 * PackedCollection latent = sampler.sampleFrom(startLatent, strength, seed);
 * }</pre>
 *
 * @see SamplingStrategy
 * @see PingPongSamplingStrategy
 * @see DDIMSamplingStrategy
 * @author Michael Murray
 */
public class DiffusionSampler implements ConsoleFeatures {

	/**
	 * Functional interface for the diffusion model's forward pass.
	 */
	@FunctionalInterface
	public interface DiffusionModel {
		/**
		 * Runs the model forward pass.
		 *
		 * @param x Current noisy sample
		 * @param t Timestep tensor
		 * @param conditioning Optional conditioning inputs
		 * @return Model prediction (noise or velocity)
		 */
		PackedCollection forward(PackedCollection x, PackedCollection t,
								 PackedCollection... conditioning);
	}

	private final DiffusionModel model;
	private final SamplingStrategy strategy;
	private final int numSteps;
	private final TraversalPolicy latentShape;
	private final int latentSize;

	private int numInferenceSteps;
	private DoubleConsumer progressCallback;
	private boolean verbose = true;

	/**
	 * Creates a diffusion sampler.
	 *
	 * @param model The diffusion model
	 * @param strategy Sampling strategy (DDIM, ping-pong, etc.)
	 * @param numSteps Total number of diffusion steps
	 * @param latentShape Shape of the latent tensor
	 */
	public DiffusionSampler(DiffusionModel model, SamplingStrategy strategy,
							int numSteps, TraversalPolicy latentShape) {
		this.model = model;
		this.strategy = strategy;
		this.numSteps = numSteps;
		this.latentShape = latentShape;
		this.latentSize = latentShape.getTotalSize();
		this.numInferenceSteps = numSteps;
	}

	/**
	 * Sets the number of inference steps.
	 *
	 * @param steps Number of steps (fewer = faster, more = higher quality)
	 * @return This sampler for chaining
	 */
	public DiffusionSampler setNumInferenceSteps(int steps) {
		this.numInferenceSteps = steps;
		return this;
	}

	/**
	 * Sets a progress callback that receives values from 0.0 to 1.0.
	 *
	 * @param callback Progress callback
	 * @return This sampler for chaining
	 */
	public DiffusionSampler setProgressCallback(DoubleConsumer callback) {
		this.progressCallback = callback;
		return this;
	}

	/**
	 * Sets verbose logging.
	 *
	 * @param verbose Whether to log progress
	 * @return This sampler for chaining
	 */
	public DiffusionSampler setVerbose(boolean verbose) {
		this.verbose = verbose;
		return this;
	}

	/**
	 * Returns the number of inference steps.
	 *
	 * @return Number of inference steps
	 */
	public int getNumInferenceSteps() {
		return numInferenceSteps;
	}

	/**
	 * Samples a latent from pure noise.
	 *
	 * @param seed Random seed
	 * @param conditioning Optional conditioning inputs for the model
	 * @return Generated latent
	 */
	public PackedCollection sample(long seed, PackedCollection... conditioning) {
		Random random = new Random(seed);
		int[] shapeArray = latentShape.extent();
		PackedCollection x = strategy.sampleInitialNoise(shapeArray, random);

		return runSamplingLoop(x, 0, random, conditioning);
	}

	/**
	 * Samples starting from an existing latent (img2img style).
	 *
	 * @param startLatent The starting latent
	 * @param strength Strength parameter (0 = keep original, 1 = full generation)
	 * @param seed Random seed
	 * @param conditioning Optional conditioning inputs
	 * @return Generated latent
	 */
	public PackedCollection sampleFrom(PackedCollection startLatent, double strength,
									   long seed, PackedCollection... conditioning) {
		if (strength < 0.0 || strength > 1.0) {
			throw new IllegalArgumentException("Strength must be between 0.0 and 1.0");
		}

		// Special case: strength = 0.0 means no diffusion
		if (strength == 0.0) {
			if (verbose) log("Strength is 0.0 - returning input latent directly");
			return startLatent;
		}

		Random random = new Random(seed);
		double[] timesteps = strategy.getTimesteps(numSteps, numInferenceSteps);

		// Calculate start step based on strength
		int startStep = (int) ((1.0 - strength) * numInferenceSteps);
		if (startStep >= numInferenceSteps) startStep = numInferenceSteps - 1;

		// Add noise to start latent at the target timestep
		double startT = timesteps[startStep];
		PackedCollection x = strategy.addNoise(startLatent, startT, random);

		if (verbose) {
			log("Starting diffusion from step " + startStep + "/" + numInferenceSteps +
					" (t=" + String.format("%.4f", startT) + ", strength=" + strength + ")");
		}

		// Advance random state to match what would have happened if we started from beginning
		advanceRandomState(random, startStep);

		return runSamplingLoop(x, startStep, random, conditioning);
	}

	/**
	 * The core sampling loop. This is THE loop - no other class should have one.
	 */
	private PackedCollection runSamplingLoop(PackedCollection x, int startStep,
											 Random random, PackedCollection[] conditioning) {
		double[] timesteps = strategy.getTimesteps(numSteps, numInferenceSteps);

		if (verbose) {
			log("Starting sampling with " + (numInferenceSteps - startStep) + " steps");
		}

		long modelTotal = 0;
		long samplingTotal = 0;

		PackedCollection tTensor = new PackedCollection(1);
		int totalSteps = numInferenceSteps - startStep;

		for (int step = startStep; step < numInferenceSteps; step++) {
			double t = timesteps[step];
			double tPrev = timesteps[step + 1];

			// Set timestep tensor
			tTensor.setMem(0, t);

			// Model forward pass
			long start = System.currentTimeMillis();
			PackedCollection modelOutput = model.forward(x, tTensor, conditioning);
			modelTotal += System.currentTimeMillis() - start;

			// Check for NaN
			checkNan(x, "input at step " + step);
			checkNan(modelOutput, "output at step " + step);

			// Sampling step
			start = System.currentTimeMillis();
			x = strategy.step(x, modelOutput, t, tPrev, random);
			samplingTotal += System.currentTimeMillis() - start;

			checkNan(x, "result at step " + step);

			// Progress reporting
			if (progressCallback != null) {
				progressCallback.accept((double) (step - startStep + 1) / totalSteps);
			}

			if (verbose && (step - startStep + 1) % 10 == 0) {
				log(String.format("Step %d/%d (t=%.4f)", step - startStep + 1, totalSteps, t));
			}
		}

		if (verbose) {
			log("Sampling completed - " + samplingTotal + "ms sampling, " + modelTotal + "ms model");
		}

		return x;
	}

	/**
	 * Advances the random state to simulate having run earlier steps.
	 * This ensures reproducibility when starting from a non-zero step.
	 */
	private void advanceRandomState(Random random, int startStep) {
		// Each step uses latentSize random values for noise
		for (int i = 0; i < startStep * latentSize; i++) {
			random.nextGaussian();
		}
	}

	private void checkNan(PackedCollection x, String context) {
		if (verbose) {
			long nanCount = x.count(Double::isNaN);
			if (nanCount > 0) {
				warn(nanCount + " NaN values detected at " + context);
			}
		}
	}
}
