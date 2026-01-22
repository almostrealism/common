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
public class PingPongSamplingStrategy implements SamplingStrategy {

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
	public PackedCollection step(PackedCollection x, PackedCollection modelOutput,
								 double t, double tPrev, Random random) {
		int size = (int) x.getMemLength();
		double[] xData = x.toArray();
		double[] outputData = modelOutput.toArray();

		// Compute denoised prediction: denoised = x - t * model_output
		double[] denoised = new double[size];
		for (int i = 0; i < size; i++) {
			denoised[i] = xData[i] - (t * outputData[i]);
		}

		// Sample fresh noise
		PackedCollection newNoise = new PackedCollection(x.getShape()).randnFill(random);
		double[] noiseData = newNoise.toArray();

		// Interpolate: x_prev = (1 - tPrev) * denoised + tPrev * noise
		PackedCollection result = new PackedCollection(x.getShape());
		float[] resultData = new float[size];
		for (int i = 0; i < size; i++) {
			resultData[i] = (float) ((1.0 - tPrev) * denoised[i] + tPrev * noiseData[i]);
		}
		result.setMem(resultData);

		return result;
	}

	@Override
	public PackedCollection addNoise(PackedCollection cleanSample, double t, Random random) {
		PackedCollection noise = new PackedCollection(cleanSample.getShape()).randnFill(random);
		int size = (int) cleanSample.getMemLength();

		double[] cleanData = cleanSample.toArray();
		double[] noiseData = noise.toArray();

		// Interpolate: noisy = (1 - t) * clean + t * noise
		PackedCollection result = new PackedCollection(cleanSample.getShape());
		float[] resultData = new float[size];
		for (int i = 0; i < size; i++) {
			resultData[i] = (float) ((1.0 - t) * cleanData[i] + t * noiseData[i]);
		}
		result.setMem(resultData);

		return result;
	}
}
