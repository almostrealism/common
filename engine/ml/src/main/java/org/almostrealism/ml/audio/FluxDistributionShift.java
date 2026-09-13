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
 * The Flux / SD3 timestep shift {@code t' = alpha * t / (1 + (alpha - 1) * t)}.
 *
 * <p>{@code alpha > 1} pushes timesteps toward the noise end of the schedule, which suits longer
 * sequences where the structure-from-noise transition happens at higher noise levels. The shift
 * factor is interpolated log-linearly in sequence length between {@code alphaMin} at
 * {@code minLength} and {@code alphaMax} at {@code maxLength} (a power law, following the SD3
 * derivation where {@code alpha} grows with the square root of the length); lengths outside that
 * range are clamped. Setting {@code alphaMin == alphaMax} gives a constant shift independent of the
 * length.</p>
 */
public class FluxDistributionShift implements DistributionShift {

	/** Sequence length at which {@code alpha == alphaMin}. */
	private final double minLength;

	/** Sequence length at which {@code alpha == alphaMax}. */
	private final double maxLength;

	/** Shift factor at {@code minLength}. */
	private final double alphaMin;

	/** Shift factor at {@code maxLength}. */
	private final double alphaMax;

	/**
	 * Creates a constant shift ({@code alphaMin == alphaMax == alpha}).
	 *
	 * @param alpha shift factor ({@code 1.0} = no shift)
	 */
	public FluxDistributionShift(double alpha) {
		this(256, 4096, alpha, alpha);
	}

	/**
	 * Creates a length-interpolated shift.
	 *
	 * @param minLength sequence length at which {@code alpha == alphaMin}
	 * @param maxLength sequence length at which {@code alpha == alphaMax}
	 * @param alphaMin  shift factor at {@code minLength} ({@code 1.0} = no shift)
	 * @param alphaMax  shift factor at {@code maxLength} ({@code 1.0} = no shift)
	 */
	public FluxDistributionShift(double minLength, double maxLength, double alphaMin, double alphaMax) {
		if (minLength <= 0 || maxLength <= 0) {
			throw new IllegalArgumentException("minLength and maxLength must be positive");
		}

		if (alphaMin <= 0 || alphaMax <= 0) {
			throw new IllegalArgumentException("alphaMin and alphaMax must be positive");
		}

		this.minLength = minLength;
		this.maxLength = maxLength;
		this.alphaMin = alphaMin;
		this.alphaMax = alphaMax;
	}

	/**
	 * The shift factor for a sequence length, interpolated log-linearly between the two anchors.
	 *
	 * @param sequenceLength latent sequence length (clamped to {@code [minLength, maxLength]})
	 * @return the shift factor {@code alpha}
	 */
	public double getAlpha(int sequenceLength) {
		if (alphaMin == alphaMax) {
			return alphaMin;
		}

		double length = Math.max(Math.min(sequenceLength, maxLength), minLength);
		double logMin = Math.log(minLength);
		double logMax = Math.log(maxLength);

		if (logMax == logMin) {
			return alphaMin;
		}

		double frac = (Math.log(length) - logMin) / (logMax - logMin);
		double logAlpha = Math.log(alphaMin) + frac * (Math.log(alphaMax) - Math.log(alphaMin));
		return Math.exp(logAlpha);
	}

	@Override
	public boolean isLengthDependent() {
		return alphaMin != alphaMax;
	}

	@Override
	public double shift(double t, int sequenceLength) {
		double alpha = getAlpha(sequenceLength);
		return alpha * t / (1.0 + (alpha - 1.0) * t);
	}
}
