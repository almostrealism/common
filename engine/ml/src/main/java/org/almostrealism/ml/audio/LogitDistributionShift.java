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
 * A length-dependent shift of the timestep schedule in logit space.
 *
 * <p>The timestep is mapped through {@code t' = 1 - exp(mu) / (exp(mu) + (1 / (1 - t) - 1))}, where
 * {@code mu} interpolates linearly from {@code -baseShift} at {@code minLength} to {@code -maxShift}
 * at {@code maxLength}. Larger shifts move the schedule toward higher noise levels for longer
 * sequences. An optional final {@code sin(t' * pi / 2)} warp bends the schedule further toward
 * noise.</p>
 */
public class LogitDistributionShift implements DistributionShift {

	/** Shift magnitude at {@code minLength}. */
	private final double baseShift;

	/** Shift magnitude at {@code maxLength}. */
	private final double maxShift;

	/** Sequence length at which the shift equals {@code baseShift}. */
	private final double minLength;

	/** Sequence length at which the shift equals {@code maxShift}. */
	private final double maxLength;

	/** Whether the final sine warp is applied. */
	private final boolean sine;

	/**
	 * Creates the shift with the reference defaults
	 * ({@code baseShift = 0.5}, {@code maxShift = 1.15}, lengths {@code 256 .. 4096}, no sine warp).
	 */
	public LogitDistributionShift() {
		this(0.5, 1.15, 256, 4096, false);
	}

	/**
	 * Creates a logit-space shift.
	 *
	 * @param baseShift shift magnitude at {@code minLength}
	 * @param maxShift  shift magnitude at {@code maxLength}
	 * @param minLength sequence length at which the shift equals {@code baseShift}
	 * @param maxLength sequence length at which the shift equals {@code maxShift}
	 * @param sine      whether to apply the final {@code sin(t * pi / 2)} warp
	 */
	public LogitDistributionShift(double baseShift, double maxShift,
								  double minLength, double maxLength, boolean sine) {
		if (minLength <= 0 || maxLength <= minLength) {
			throw new IllegalArgumentException("Require 0 < minLength < maxLength");
		}

		this.baseShift = baseShift;
		this.maxShift = maxShift;
		this.minLength = minLength;
		this.maxLength = maxLength;
		this.sine = sine;
	}

	/**
	 * The logit-space offset {@code mu} for a sequence length.
	 *
	 * @param sequenceLength latent sequence length (clamped to {@code [minLength, maxLength]})
	 * @return the (negative) offset applied in logit space
	 */
	public double getMu(int sequenceLength) {
		double length = Math.min(Math.max(sequenceLength, minLength), maxLength);
		return -(baseShift + (maxShift - baseShift) * (length - minLength) / (maxLength - minLength));
	}

	@Override
	public boolean isLengthDependent() {
		return true;
	}

	@Override
	public double shift(double t, int sequenceLength) {
		if (t <= 0.0) {
			return 0.0;
		} else if (t >= 1.0) {
			return 1.0;
		}

		double expMu = Math.exp(getMu(sequenceLength));
		double out = 1.0 - expMu / (expMu + (1.0 / (1.0 - t) - 1.0));
		return sine ? Math.sin(out * Math.PI / 2) : out;
	}
}
