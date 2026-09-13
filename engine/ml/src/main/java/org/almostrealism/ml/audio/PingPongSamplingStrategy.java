/*
 * Copyright 2026 Michael Murray
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

/**
 * Ping-pong (rectified flow) sampling strategy.
 *
 * <p>The schedule is a uniform grid from {@code sigmaMax} down to {@code sigmaMin} warped by a
 * {@link DistributionShift}. The default shift is a fixed log-SNR spacing from {@code -6} to
 * {@code 2}; a length-adaptive {@link LogSNRShift} or any other warp can be supplied to match a
 * particular model's sampling schedule.
 *
 * <p>Each step interpolates between the denoised prediction and fresh noise:
 * {@code x_{t-1} = (1 - sigma_{t-1}) * denoised + sigma_{t-1} * noise}.
 *
 * @see SamplingStrategy
 * @see DistributionShift
 * @author Michael Murray
 */
public class PingPongSamplingStrategy implements SamplingStrategy, CodeFeatures {

	/** Warp applied to the uniform timestep grid. */
	private final DistributionShift shift;

	/** Maximum sigma value, clamped onto the first timestep boundary. */
	private final double sigmaMax;

	/** Minimum sigma value, clamped onto the last timestep boundary (typically 0). */
	private final double sigmaMin;

	/**
	 * Creates a ping-pong sampling strategy with the fixed log-SNR schedule from {@code -6} to {@code 2}.
	 */
	public PingPongSamplingStrategy() {
		this(-6.0f, 2.0f, 1.0f, 0.0f);
	}

	/**
	 * Creates a ping-pong sampling strategy with a fixed log-SNR schedule.
	 *
	 * @param logSnrMax Log-SNR value at the noise end of the schedule (typically negative, e.g., -6)
	 * @param logSnrMin Log-SNR value at the data end of the schedule (typically positive, e.g., 2)
	 * @param sigmaMax Maximum sigma value (typically 1.0)
	 * @param sigmaMin Minimum sigma value (typically 0.0)
	 */
	public PingPongSamplingStrategy(float logSnrMax, float logSnrMin,
									float sigmaMax, float sigmaMin) {
		this(LogSNRShift.fixed(logSnrMax, logSnrMin), sigmaMax, sigmaMin);
	}

	/**
	 * Creates a ping-pong sampling strategy whose schedule is warped by the given shift and runs
	 * from {@code 1} to {@code 0}.
	 *
	 * @param shift Warp applied to the uniform timestep grid
	 */
	public PingPongSamplingStrategy(DistributionShift shift) {
		this(shift, 1.0, 0.0);
	}

	/**
	 * Creates a ping-pong sampling strategy whose schedule is warped by the given shift.
	 *
	 * @param shift Warp applied to the uniform timestep grid
	 * @param sigmaMax Maximum sigma value (typically 1.0)
	 * @param sigmaMin Minimum sigma value (typically 0.0)
	 */
	public PingPongSamplingStrategy(DistributionShift shift, double sigmaMax, double sigmaMin) {
		if (shift == null) {
			throw new IllegalArgumentException("A DistributionShift is required");
		}

		this.shift = shift;
		this.sigmaMax = sigmaMax;
		this.sigmaMin = sigmaMin;
	}

	/**
	 * The warp applied to the timestep grid.
	 *
	 * @return the distribution shift
	 */
	public DistributionShift getShift() { return shift; }

	@Override
	public double[] getTimesteps(int numSteps, int numInferenceSteps) {
		return getTimesteps(numSteps, numInferenceSteps, 0);
	}

	@Override
	public double[] getTimesteps(int numSteps, int numInferenceSteps, int sequenceLength) {
		double[] timesteps = shift.schedule(numInferenceSteps, sigmaMax, sequenceLength);
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
