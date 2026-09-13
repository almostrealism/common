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

/**
 * A monotone warp of the rectified-flow timestep schedule.
 *
 * <p>Rectified-flow samplers start from a uniform schedule {@code t = linspace(sigmaMax, 0)} and warp
 * it so that more steps are spent at the noise levels where structure emerges. Because that critical
 * noise level moves with the length of the sequence being generated, a shift receives the latent
 * sequence length alongside the timestep. Implementations must map {@code 0 -> 0} and {@code 1 -> 1}
 * and be monotone on {@code [0, 1]} with {@code t = 0} meaning clean data and {@code t = 1} pure
 * noise.</p>
 *
 * <p>The schedule itself is built by {@link #schedule(int, double, int)}: the uniform grid is warped
 * point-wise and the first entry is then pinned back to {@code sigmaMax} so that the initial mixing of
 * data and noise stays consistent with the first model evaluation.</p>
 *
 * @see LogSNRShift
 * @see FluxDistributionShift
 * @see LogitDistributionShift
 * @see PingPongSamplingStrategy
 */
public interface DistributionShift {

	/**
	 * Warps a single timestep.
	 *
	 * @param t              timestep in {@code [0, 1]} ({@code 1} = noise, {@code 0} = data)
	 * @param sequenceLength latent sequence length the schedule is built for; implementations that do
	 *                       not depend on the length ignore it, and {@code 0} may be passed to them
	 * @return the warped timestep in {@code [0, 1]}
	 */
	double shift(double t, int sequenceLength);

	/**
	 * Whether {@link #shift(double, int)} depends on its {@code sequenceLength} argument.
	 *
	 * @return {@code true} when the warp changes with the sequence length
	 */
	boolean isLengthDependent();

	/**
	 * Builds a warped rectified-flow schedule of {@code numInferenceSteps + 1} points running from
	 * {@code sigmaMax} down to {@code 0}, the uniform grid warped by this shift and the first point
	 * pinned to {@code sigmaMax}.
	 *
	 * @param numInferenceSteps number of sampling steps
	 * @param sigmaMax          starting noise level ({@code 1.0} for full generation)
	 * @param sequenceLength    latent sequence length passed to {@link #shift(double, int)}
	 * @return the schedule, with {@code schedule[0] == sigmaMax} and {@code schedule[numInferenceSteps] == 0}
	 */
	default double[] schedule(int numInferenceSteps, double sigmaMax, int sequenceLength) {
		if (numInferenceSteps <= 0) {
			throw new IllegalArgumentException("numInferenceSteps must be positive");
		}

		if (isLengthDependent() && sequenceLength <= 0) {
			throw new IllegalArgumentException(getClass().getSimpleName() +
					" depends on the sequence length, which must be positive");
		}

		double[] schedule = new double[numInferenceSteps + 1];

		for (int i = 0; i <= numInferenceSteps; i++) {
			double t = sigmaMax * (1.0 - (double) i / numInferenceSteps);
			schedule[i] = shift(t, sequenceLength);
		}

		schedule[0] = sigmaMax;
		schedule[numInferenceSteps] = 0.0;
		return schedule;
	}

	/**
	 * The identity warp: the schedule stays uniform.
	 *
	 * @return a shift that returns every timestep unchanged
	 */
	static DistributionShift identity() {
		return new DistributionShift() {
			@Override
			public double shift(double t, int sequenceLength) { return t; }

			@Override
			public boolean isLengthDependent() { return false; }
		};
	}
}
