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

import org.almostrealism.collect.PackedCollection;

import java.util.Random;

/**
 * Noise scheduler for diffusion model training and sampling.
 *
 * <p>Implements a cosine noise schedule as used in improved diffusion models.
 * The scheduler handles adding noise during training and denoising during
 * inference (sampling).</p>
 *
 * <h2>Training Usage</h2>
 * <pre>{@code
 * DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(1000);
 *
 * // For each training step
 * int t = scheduler.sampleTimestep();
 * PackedCollection noise = scheduler.sampleNoise(latent.getShape());
 * PackedCollection noisyLatent = scheduler.addNoise(latent, noise, t);
 *
 * // Model predicts the noise
 * PackedCollection predictedNoise = model.forward(noisyLatent, timestep);
 *
 * // Loss = MSE(predictedNoise, noise)
 * }</pre>
 *
 * <h2>Sampling (Inference)</h2>
 * <pre>{@code
 * // Start from pure noise
 * PackedCollection x = scheduler.sampleNoise(shape);
 *
 * // Iteratively denoise
 * for (int t = scheduler.getNumSteps() - 1; t >= 0; t--) {
 *     PackedCollection predictedNoise = model.forward(x, t);
 *     x = scheduler.step(x, predictedNoise, t);
 * }
 * }</pre>
 *
 * @author Michael Murray
 */
public class DiffusionNoiseScheduler {

	private final int numSteps;
	private final double[] alphas;
	private final double[] alphasCumprod;
	private final double[] sqrtAlphasCumprod;
	private final double[] sqrtOneMinusAlphasCumprod;
	private final Random random;

	/**
	 * Creates a noise scheduler with cosine schedule.
	 *
	 * @param numSteps Number of diffusion timesteps (typically 1000)
	 */
	public DiffusionNoiseScheduler(int numSteps) {
		this(numSteps, new Random());
	}

	/**
	 * Creates a noise scheduler with specified random generator.
	 *
	 * @param numSteps Number of diffusion timesteps
	 * @param random Random number generator
	 */
	public DiffusionNoiseScheduler(int numSteps, Random random) {
		this.numSteps = numSteps;
		this.random = random;

		// Compute cosine schedule
		double s = 0.008; // Small offset to avoid singularity at t=0
		this.alphasCumprod = new double[numSteps];
		this.alphas = new double[numSteps];
		this.sqrtAlphasCumprod = new double[numSteps];
		this.sqrtOneMinusAlphasCumprod = new double[numSteps];

		for (int t = 0; t < numSteps; t++) {
			double progress = (double) t / numSteps;
			// Cosine schedule: alpha_bar(t) = cos^2((t/T + s) / (1 + s) * pi/2)
			double f_t = Math.cos((progress + s) / (1 + s) * Math.PI / 2);
			alphasCumprod[t] = f_t * f_t;
		}

		// Clip to avoid numerical issues
		for (int t = 0; t < numSteps; t++) {
			alphasCumprod[t] = Math.max(0.0001, Math.min(0.9999, alphasCumprod[t]));
		}

		// Compute individual alphas from cumulative product
		alphas[0] = alphasCumprod[0];
		for (int t = 1; t < numSteps; t++) {
			alphas[t] = alphasCumprod[t] / alphasCumprod[t - 1];
		}

		// Precompute useful quantities
		for (int t = 0; t < numSteps; t++) {
			sqrtAlphasCumprod[t] = Math.sqrt(alphasCumprod[t]);
			sqrtOneMinusAlphasCumprod[t] = Math.sqrt(1.0 - alphasCumprod[t]);
		}
	}

	/**
	 * Samples a random timestep for training.
	 *
	 * @return Random timestep in [0, numSteps)
	 */
	public int sampleTimestep() {
		return random.nextInt(numSteps);
	}

	/**
	 * Samples Gaussian noise matching the given shape.
	 *
	 * @param shape Shape of the noise tensor
	 * @return Noise tensor with standard normal values
	 */
	public PackedCollection sampleNoise(int... shape) {
		int totalSize = 1;
		for (int dim : shape) {
			totalSize *= dim;
		}

		PackedCollection noise = new PackedCollection(shape);
		for (int i = 0; i < totalSize; i++) {
			noise.setMem(i, random.nextGaussian());
		}
		return noise;
	}

	/**
	 * Samples Gaussian noise matching another collection's shape.
	 *
	 * @param like Collection to match shape
	 * @return Noise tensor with standard normal values
	 */
	public PackedCollection sampleNoiseLike(PackedCollection like) {
		PackedCollection noise = new PackedCollection(like.getShape());
		int totalSize = (int) like.getMemLength();
		for (int i = 0; i < totalSize; i++) {
			noise.setMem(i, random.nextGaussian());
		}
		return noise;
	}

	/**
	 * Adds noise to a clean sample at the given timestep.
	 *
	 * <p>Implements the forward diffusion process:
	 * x_t = sqrt(alpha_bar_t) * x_0 + sqrt(1 - alpha_bar_t) * noise</p>
	 *
	 * @param x0 Clean sample
	 * @param noise Gaussian noise (same shape as x0)
	 * @param t Timestep
	 * @return Noisy sample at timestep t
	 */
	public PackedCollection addNoise(PackedCollection x0, PackedCollection noise, int t) {
		double sqrtAlpha = sqrtAlphasCumprod[t];
		double sqrtOneMinusAlpha = sqrtOneMinusAlphasCumprod[t];

		PackedCollection result = new PackedCollection(x0.getShape());
		int size = (int) x0.getMemLength();

		for (int i = 0; i < size; i++) {
			double val = sqrtAlpha * x0.toDouble(i) + sqrtOneMinusAlpha * noise.toDouble(i);
			result.setMem(i, val);
		}

		return result;
	}

	/**
	 * Performs one reverse diffusion step (DDPM sampling).
	 *
	 * <p>Given x_t and predicted noise, computes x_{t-1}.</p>
	 *
	 * @param xt Current noisy sample
	 * @param predictedNoise Model's noise prediction
	 * @param t Current timestep
	 * @return Denoised sample at timestep t-1
	 */
	public PackedCollection step(PackedCollection xt, PackedCollection predictedNoise, int t) {
		double alpha = alphas[t];
		double alphaBar = alphasCumprod[t];
		double alphaBarPrev = t > 0 ? alphasCumprod[t - 1] : 1.0;

		// Predicted x0 from noise prediction
		double sqrtAlphaBar = sqrtAlphasCumprod[t];
		double sqrtOneMinusAlphaBar = sqrtOneMinusAlphasCumprod[t];

		// Compute x0 prediction: x0 = (xt - sqrt(1-alpha_bar)*noise) / sqrt(alpha_bar)
		// Then compute x_{t-1} using posterior mean

		// Posterior variance
		double betaT = 1.0 - alpha;
		double posteriorVariance = betaT * (1.0 - alphaBarPrev) / (1.0 - alphaBar);

		// Posterior mean coefficient for x0
		double coef1 = Math.sqrt(alphaBarPrev) * betaT / (1.0 - alphaBar);
		// Posterior mean coefficient for xt
		double coef2 = Math.sqrt(alpha) * (1.0 - alphaBarPrev) / (1.0 - alphaBar);

		PackedCollection result = new PackedCollection(xt.getShape());
		int size = (int) xt.getMemLength();

		for (int i = 0; i < size; i++) {
			// First predict x0
			double x0_pred = (xt.toDouble(i) - sqrtOneMinusAlphaBar * predictedNoise.toDouble(i)) / sqrtAlphaBar;
			// Clip x0 prediction to reasonable range
			x0_pred = Math.max(-10.0, Math.min(10.0, x0_pred));

			// Compute posterior mean
			double mean = coef1 * x0_pred + coef2 * xt.toDouble(i);

			// Add noise (except at t=0)
			double noiseScale = t > 0 ? Math.sqrt(posteriorVariance) : 0.0;
			double val = mean + noiseScale * random.nextGaussian();

			result.setMem(i, val);
		}

		return result;
	}

	/**
	 * Performs DDIM deterministic sampling step.
	 *
	 * <p>DDIM allows faster sampling with fewer steps while maintaining quality.</p>
	 *
	 * @param xt Current noisy sample
	 * @param predictedNoise Model's noise prediction
	 * @param t Current timestep
	 * @param tPrev Previous timestep (can skip steps)
	 * @param eta DDIM stochasticity parameter (0 = deterministic, 1 = DDPM)
	 * @return Denoised sample
	 */
	public PackedCollection stepDDIM(PackedCollection xt, PackedCollection predictedNoise,
									 int t, int tPrev, double eta) {
		double alphaBar = alphasCumprod[t];
		double alphaBarPrev = tPrev >= 0 ? alphasCumprod[tPrev] : 1.0;

		double sqrtAlphaBar = sqrtAlphasCumprod[t];
		double sqrtOneMinusAlphaBar = sqrtOneMinusAlphasCumprod[t];

		// Predict x0
		PackedCollection result = new PackedCollection(xt.getShape());
		int size = (int) xt.getMemLength();

		// Compute sigma for optional stochasticity
		double sigma = eta * Math.sqrt((1.0 - alphaBarPrev) / (1.0 - alphaBar))
				* Math.sqrt(1.0 - alphaBar / alphaBarPrev);

		double sqrtAlphaBarPrev = Math.sqrt(alphaBarPrev);
		double directionCoef = Math.sqrt(1.0 - alphaBarPrev - sigma * sigma);

		for (int i = 0; i < size; i++) {
			// Predict x0
			double x0_pred = (xt.toDouble(i) - sqrtOneMinusAlphaBar * predictedNoise.toDouble(i)) / sqrtAlphaBar;
			x0_pred = Math.max(-10.0, Math.min(10.0, x0_pred));

			// Compute x_{t-1}
			double val = sqrtAlphaBarPrev * x0_pred + directionCoef * predictedNoise.toDouble(i);

			// Add noise if eta > 0
			if (eta > 0 && tPrev >= 0) {
				val += sigma * random.nextGaussian();
			}

			result.setMem(i, val);
		}

		return result;
	}

	/**
	 * Gets the number of diffusion steps.
	 *
	 * @return Number of timesteps
	 */
	public int getNumSteps() {
		return numSteps;
	}

	/**
	 * Gets the cumulative alpha (alpha_bar) at timestep t.
	 *
	 * @param t Timestep
	 * @return Alpha cumulative product at t
	 */
	public double getAlphaCumprod(int t) {
		return alphasCumprod[t];
	}

	/**
	 * Creates timestep indices for DDIM sampling with fewer steps.
	 *
	 * @param numInferenceSteps Number of steps for inference (e.g., 50)
	 * @return Array of timestep indices
	 */
	public int[] getDDIMTimesteps(int numInferenceSteps) {
		int[] timesteps = new int[numInferenceSteps];
		double step = (double) (numSteps - 1) / (numInferenceSteps - 1);
		for (int i = 0; i < numInferenceSteps; i++) {
			timesteps[i] = (int) Math.round((numInferenceSteps - 1 - i) * step);
		}
		return timesteps;
	}
}
