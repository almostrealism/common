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

import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;

/**
 * Ping-pong (rectified flow) sampling strategy.
 *
 * <p>This strategy uses a sigmoid-based noise schedule and updates samples
 * by interpolating between the denoised prediction and fresh noise.
 *
 * <p>Formula: x_{t-1} = (1 - sigma_{t-1}) * denoised + sigma_{t-1} * noise
 *
 * @see SamplingStrategy
 * @author Michael Murray
 */
public class PingPongSamplingStrategy implements SamplingStrategy, CodeFeatures {

	private final float logSnrMax;
	private final float logSnrMin;
	private final float sigmaMax;
	private final float sigmaMin;

	/**
	 * Creates a ping-pong sampling strategy with default parameters.
	 */
	public PingPongSamplingStrategy() {
		this(-6.0f, 2.0f, 1.0f, 0.0f);
	}

	/**
	 * Creates a ping-pong sampling strategy with custom parameters.
	 *
	 * @param logSnrMax Maximum log-SNR value (typically negative, e.g., -6)
	 * @param logSnrMin Minimum log-SNR value (typically positive, e.g., 2)
	 * @param sigmaMax Maximum sigma value (typically 1.0)
	 * @param sigmaMin Minimum sigma value (typically 0.0)
	 */
	public PingPongSamplingStrategy(float logSnrMax, float logSnrMin,
									float sigmaMax, float sigmaMin) {
		this.logSnrMax = logSnrMax;
		this.logSnrMin = logSnrMin;
		this.sigmaMax = sigmaMax;
		this.sigmaMin = sigmaMin;
	}

	@Override
	public double[] getTimesteps(int numSteps, int numInferenceSteps) {
		double[] timesteps = new double[numInferenceSteps + 1];
		float step = (logSnrMin - logSnrMax) / numInferenceSteps;

		// Generate linspace from logSnrMax to logSnrMin
		for (int i = 0; i <= numInferenceSteps; i++) {
			timesteps[i] = logSnrMax + i * step;
		}

		// Apply sigmoid transformation
		for (int i = 0; i <= numInferenceSteps; i++) {
			timesteps[i] = 1.0 / (1.0 + Math.exp(timesteps[i]));
		}

		// Set boundaries
		timesteps[0] = sigmaMax;
		timesteps[numInferenceSteps] = sigmaMin;

		return timesteps;
	}

	@Override
	public CollectionProducer step(PackedCollection x, PackedCollection modelOutput,
									  double t, double tPrev, PackedCollection noise) {
		// GPU-accelerated computation (caller decides when to evaluate):
		// denoised = x - t * modelOutput
		// result = (1 - tPrev) * denoised + tPrev * noise
		CollectionProducer denoised = cp(x).subtract(cp(modelOutput).multiply(t));

		if (noise != null && tPrev > 0) {
			return denoised.multiply(1.0 - tPrev).add(cp(noise).multiply(tPrev));
		} else {
			// No noise injection at final step
			return denoised;
		}
	}

	@Override
	public CollectionProducer addNoise(PackedCollection cleanSample, double t, PackedCollection noise) {
		// GPU-accelerated computation (caller decides when to evaluate):
		// noisy = (1 - t) * clean + t * noise
		return cp(cleanSample).multiply(1.0 - t).add(cp(noise).multiply(t));
	}
}
