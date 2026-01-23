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
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;

/**
 * Noise scheduler for diffusion model training and sampling.
 *
 * <p>Implements a cosine noise schedule as used in improved diffusion models.
 * The scheduler handles adding noise during training and denoising during
 * inference (sampling).</p>
 *
 * <p>All operations use the Producer pattern for GPU-accelerated computation.</p>
 *
 * <h2>Training Usage</h2>
 * <pre>{@code
 * DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(1000);
 *
 * // For each training step
 * int t = scheduler.sampleTimestep();
 * PackedCollection noise = scheduler.sampleNoiseLike(latent).evaluate();
 * PackedCollection noisyLatent = scheduler.addNoise(latent, noise, t).evaluate();
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
 * PackedCollection x = scheduler.sampleNoise(shape).evaluate();
 *
 * // Iteratively denoise (but prefer DiffusionSampler over manual loops)
 * for (int t = scheduler.getNumSteps() - 1; t >= 0; t--) {
 *     PackedCollection predictedNoise = model.forward(x, t);
 *     PackedCollection noise = (t > 0) ? scheduler.sampleNoiseLike(x).evaluate() : null;
 *     x = scheduler.step(x, predictedNoise, t, noise).evaluate();
 * }
 * }</pre>
 *
 * @see DiffusionSampler
 * @author Michael Murray
 */
public class DiffusionNoiseScheduler implements CodeFeatures {

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
	 * Returns a Producer for Gaussian noise matching the given shape.
	 *
	 * <p>Returns a {@link CollectionProducer} for GPU-accelerated computation.
	 * Call {@code .evaluate()} to materialize the noise values.</p>
	 *
	 * @param shape Shape of the noise tensor
	 * @return Producer for noise tensor with standard normal values
	 */
	public CollectionProducer sampleNoise(TraversalPolicy shape) {
		return randn(shape, random);
	}

	/**
	 * Returns a Producer for Gaussian noise matching another collection's shape.
	 *
	 * <p>Returns a {@link CollectionProducer} for GPU-accelerated computation.
	 * Call {@code .evaluate()} to materialize the noise values.</p>
	 *
	 * @param like Collection to match shape
	 * @return Producer for noise tensor with standard normal values
	 */
	public CollectionProducer sampleNoiseLike(PackedCollection like) {
		return randn(like.getShape(), random);
	}

	/**
	 * Adds noise to a clean sample at the given timestep.
	 *
	 * <p>Implements the forward diffusion process:
	 * x_t = sqrt(alpha_bar_t) * x_0 + sqrt(1 - alpha_bar_t) * noise</p>
	 *
	 * <p>Returns a {@link CollectionProducer} for GPU-accelerated computation.
	 * Caller decides when to materialize with {@code .evaluate()}.</p>
	 *
	 * @param x0 Clean sample
	 * @param noise Gaussian noise (same shape as x0)
	 * @param t Timestep
	 * @return Producer for the noisy sample at timestep t
	 */
	public CollectionProducer addNoise(PackedCollection x0, PackedCollection noise, int t) {
		double sqrtAlpha = sqrtAlphasCumprod[t];
		double sqrtOneMinusAlpha = sqrtOneMinusAlphasCumprod[t];

		// GPU-accelerated: x_t = sqrt(alpha) * x_0 + sqrt(1-alpha) * noise
		return cp(x0).multiply(sqrtAlpha).add(cp(noise).multiply(sqrtOneMinusAlpha));
	}

	/**
	 * Performs one reverse diffusion step (DDPM sampling).
	 *
	 * <p>Given x_t and predicted noise, computes x_{t-1}.</p>
	 *
	 * <p>Returns a {@link CollectionProducer} for GPU-accelerated computation.
	 * Caller decides when to materialize with {@code .evaluate()}.</p>
	 *
	 * @param xt Current noisy sample
	 * @param predictedNoise Model's noise prediction
	 * @param t Current timestep
	 * @param noise Pre-sampled noise for stochastic step (may be null for t=0)
	 * @return Producer for the denoised sample at timestep t-1
	 */
	public CollectionProducer step(PackedCollection xt, PackedCollection predictedNoise,
									  int t, PackedCollection noise) {
		double alpha = alphas[t];
		double alphaBar = alphasCumprod[t];
		double alphaBarPrev = t > 0 ? alphasCumprod[t - 1] : 1.0;

		double sqrtAlphaBar = sqrtAlphasCumprod[t];
		double sqrtOneMinusAlphaBar = sqrtOneMinusAlphasCumprod[t];

		// Posterior variance
		double betaT = 1.0 - alpha;
		double posteriorVariance = betaT * (1.0 - alphaBarPrev) / (1.0 - alphaBar);

		// Posterior mean coefficient for x0
		double coef1 = Math.sqrt(alphaBarPrev) * betaT / (1.0 - alphaBar);
		// Posterior mean coefficient for xt
		double coef2 = Math.sqrt(alpha) * (1.0 - alphaBarPrev) / (1.0 - alphaBar);

		// GPU-accelerated computation:
		// x0_pred = (xt - sqrt(1-alpha_bar) * noise) / sqrt(alpha_bar)
		CollectionProducer x0Pred = cp(xt).subtract(cp(predictedNoise).multiply(sqrtOneMinusAlphaBar))
				.divide(sqrtAlphaBar);

		// Clip x0 prediction to [-10, 10]
		CollectionProducer x0Clamped = min(max(x0Pred, c(-10.0)), c(10.0));

		// mean = coef1 * x0_pred + coef2 * xt
		CollectionProducer mean = x0Clamped.multiply(coef1).add(cp(xt).multiply(coef2));

		// Add noise (except at t=0)
		if (t > 0 && noise != null) {
			double noiseScale = Math.sqrt(posteriorVariance);
			return mean.add(cp(noise).multiply(noiseScale));
		} else {
			return mean;
		}
	}

	/**
	 * Performs DDIM deterministic sampling step.
	 *
	 * <p>DDIM allows faster sampling with fewer steps while maintaining quality.</p>
	 *
	 * <p>Returns a {@link CollectionProducer} for GPU-accelerated computation.
	 * Caller decides when to materialize with {@code .evaluate()}.</p>
	 *
	 * @param xt Current noisy sample
	 * @param predictedNoise Model's noise prediction
	 * @param t Current timestep
	 * @param tPrev Previous timestep (can skip steps)
	 * @param eta DDIM stochasticity parameter (0 = deterministic, 1 = DDPM)
	 * @param noise Pre-sampled noise for stochastic step (may be null for deterministic)
	 * @return Producer for the denoised sample
	 */
	public CollectionProducer stepDDIM(PackedCollection xt, PackedCollection predictedNoise,
										  int t, int tPrev, double eta, PackedCollection noise) {
		double alphaBar = alphasCumprod[t];
		double alphaBarPrev = tPrev >= 0 ? alphasCumprod[tPrev] : 1.0;

		double sqrtAlphaBar = sqrtAlphasCumprod[t];
		double sqrtOneMinusAlphaBar = sqrtOneMinusAlphasCumprod[t];

		// Compute sigma for optional stochasticity
		double sigma = eta * Math.sqrt((1.0 - alphaBarPrev) / (1.0 - alphaBar))
				* Math.sqrt(1.0 - alphaBar / alphaBarPrev);

		double sqrtAlphaBarPrev = Math.sqrt(alphaBarPrev);
		double directionCoef = Math.sqrt(1.0 - alphaBarPrev - sigma * sigma);

		// GPU-accelerated computation:
		// x0_pred = (xt - sqrt(1-alpha_bar) * noise) / sqrt(alpha_bar)
		CollectionProducer x0Pred = cp(xt).subtract(cp(predictedNoise).multiply(sqrtOneMinusAlphaBar))
				.divide(sqrtAlphaBar);

		// Clip x0 prediction to [-10, 10]
		CollectionProducer x0Clamped = min(max(x0Pred, c(-10.0)), c(10.0));

		// result = sqrt(alpha_bar_prev) * x0_pred + directionCoef * predictedNoise
		CollectionProducer result = x0Clamped.multiply(sqrtAlphaBarPrev)
				.add(cp(predictedNoise).multiply(directionCoef));

		// Add noise if eta > 0
		if (eta > 0 && tPrev >= 0 && noise != null) {
			result = result.add(cp(noise).multiply(sigma));
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
