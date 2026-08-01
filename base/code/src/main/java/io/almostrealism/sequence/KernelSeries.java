/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.sequence;

import io.almostrealism.relation.Series;

import java.util.List;
import java.util.OptionalInt;

/**
 * Represents the repeating pattern (series) of a kernel-index expression.
 *
 * <p>A {@code KernelSeries} captures the periodicity and scale of an expression's values
 * as a function of the kernel index. It is used during optimization to determine how often
 * an expression repeats and how large its values can grow.</p>
 *
 * <p>Key properties:</p>
 * <ul>
 *   <li><b>period</b>: how many kernel steps before the pattern repeats (empty = infinite)</li>
 *   <li><b>scale</b>: a multiplier on the kernel index (empty = unbounded growth)</li>
 *   <li><b>inverseScale</b>: a divisor applied to scale, used for fractional scaling</li>
 * </ul>
 *
 * @see io.almostrealism.relation.Series
 * @see io.almostrealism.kernel.KernelSeriesProvider
 */
public class KernelSeries implements Series {
	/** Global maximum period cap; zero means no cap. */
	public static int maximumPeriod = 0;

	/** The repeating period of this series, or empty if it does not repeat within a finite window. */
	private OptionalInt period;
	/** The scale multiplier applied to the kernel index, or empty if unbounded. */
	private OptionalInt scale;
	/** An inverse divisor for the scale, used when the effective scale is fractional. */
	private int inverseScale;

	/**
	 * Creates an infinite, unscaled kernel series with no known period or scale.
	 */
	protected KernelSeries() {
		this(OptionalInt.empty(), OptionalInt.empty());
	}

	/**
	 * Creates a kernel series with the given period and scale, and an inverse scale of 1.
	 *
	 * @param period the repeating period, or empty if not periodic
	 * @param scale the scale multiplier, or empty if unbounded
	 */
	protected KernelSeries(OptionalInt period, OptionalInt scale) {
		this(period, scale, 1);
	}

	/**
	 * Creates a kernel series with the given period, scale, and inverse scale.
	 *
	 * @param period the repeating period (must be non-negative if present)
	 * @param scale the scale multiplier (must be non-negative if present)
	 * @param inverseScale the inverse scale divisor
	 * @throws IllegalArgumentException if period or scale is negative
	 */
	protected KernelSeries(OptionalInt period, OptionalInt scale, int inverseScale) {
		if (period.isPresent() && period.getAsInt() < 0) {
			throw new IllegalArgumentException("Period must be positive");
		}

		if (scale.isPresent() && scale.getAsInt() < 0) {
			throw new IllegalArgumentException("Period must be positive");
		}

		this.period = period;
		this.scale = scale;
		this.inverseScale = inverseScale;
	}

	@Override
	public boolean isFinite() { return false; }

	@Override
	public OptionalInt getPeriod() {
		if (maximumPeriod > 0 && period.isPresent() && period.getAsInt() > maximumPeriod) {
			return OptionalInt.of(maximumPeriod);
		}

		return period;
	}

	/**
	 * Returns the scale of this series.
	 *
	 * @return the scale multiplier, or empty if unbounded
	 */
	public OptionalInt getScale() { return scale; }

	/**
	 * Returns a new {@code KernelSeries} with the given scale multiplier applied.
	 *
	 * @param scale the scale factor to apply
	 * @return a new kernel series with the adjusted scale
	 */
	public KernelSeries scale(int scale) {
		if (scale % inverseScale == 0) {
			return new KernelSeries(period,
					getScale().isPresent() ? OptionalInt.of(product(getScale().getAsInt(), scale / inverseScale)) : OptionalInt.empty());
		} else {
			return new KernelSeries(period,
					getScale().isPresent() ? OptionalInt.of(product(getScale().getAsInt(), scale)) : OptionalInt.empty(),
					inverseScale);
		}
	}

	/**
	 * Returns a new {@code KernelSeries} with the given period applied (looped by the given count).
	 *
	 * <p>If this series already has a period, the new period is the product of the existing
	 * period and the given period.
	 *
	 * @param period the loop count to apply
	 * @return a new kernel series with the adjusted period
	 */
	public KernelSeries loop(int period) {
		period = Math.abs(period);

		int inverseScale = this.inverseScale;

		if (period % inverseScale == 0) {
			period = period / inverseScale;
			inverseScale = 1;
		}

		if (this.period.isPresent()) {
			return new KernelSeries(
					OptionalInt.of(product(this.period.getAsInt(), period)),
					getScale(), inverseScale);
		} else {
			return new KernelSeries(
					getScale().isPresent() ? OptionalInt.of(product(getScale().getAsInt(), period)) : OptionalInt.empty(),
					getScale(), inverseScale);
		}
	}

	/**
	 * Creates an infinite, unscaled kernel series.
	 *
	 * @return an infinite kernel series with no period or scale
	 */
	public static KernelSeries infinite() {
		return new KernelSeries(OptionalInt.empty(), OptionalInt.empty());
	}

	/**
	 * Creates an infinite kernel series with the given scale.
	 *
	 * @param scale the scale multiplier
	 * @return an infinite kernel series with the given scale
	 */
	public static KernelSeries infinite(int scale) {
		return new KernelSeries(OptionalInt.empty(), OptionalInt.of(scale));
	}

	/**
	 * Creates a kernel series appropriate for the given constant value.
	 *
	 * <p>Constants have a period of 1. The scale is determined from the magnitude of the value.
	 *
	 * @param value the constant value
	 * @return a kernel series representing a constant
	 */
	public static KernelSeries constant(double value) {
		value = Math.abs(value);

		if (value != 0.0) {
			if (value < 1.0) {
				return new KernelSeries(OptionalInt.of(1), OptionalInt.of((int) Math.ceil(1.0 / value)));
			} else if (value == Math.floor(value)) {
				return new KernelSeries(OptionalInt.of(1), OptionalInt.of(1), (int) value);
			}
		}

		return new KernelSeries(OptionalInt.of(1), OptionalInt.of(1));
	}

	/**
	 * Constructs a {@link KernelSeries} whose period is the least common multiple
	 * of the given periods.
	 */
	public static KernelSeries periodic(List<Integer> periods) {
		int period = periods.stream().distinct().reduce(1, KernelSeries::lcm);
		return new KernelSeries(OptionalInt.of(period), OptionalInt.of(period));
	}

	/**
	 * Computes the greatest common divisor of two integers.
	 *
	 * @param a the first integer
	 * @param b the second integer
	 * @return the GCD of {@code a} and {@code b}
	 */
	private static int gcd(int a, int b) {
		a = Math.abs(a);
		b = Math.abs(b);
		while (b != 0) {
			int temp = b;
			b = a % b;
			a = temp;
		}
		return a;
	}

	/**
	 * Computes the least common multiple of two integers.
	 *
	 * @param a the first integer
	 * @param b the second integer
	 * @return the LCM, capped at {@link Integer#MAX_VALUE}
	 */
	private static int lcm(int a, int b) {
		if (a == 0 || b == 0) return 0;
		long result = Math.abs((long) (a / gcd(a, b)) * b);
		return (int) Math.min(result, Integer.MAX_VALUE);
	}

	/**
	 * Creates a kernel series representing the product of the given list of series.
	 *
	 * <p>The resulting scale is the product of all individual scales, and the inverse scale
	 * is the product of all individual inverse scales.
	 *
	 * @param series the list of kernel series to multiply together
	 * @return a kernel series representing the product
	 */
	public static KernelSeries product(List<KernelSeries> series) {
		int scale = series.stream()
				.map(k -> k.getScale())
				.filter(o -> o.isPresent())
				.mapToInt(o -> o.getAsInt())
				.reduce(1, (a, b) -> a * b);
		int inverseScale = series.stream()
				.map(k -> k.inverseScale)
				.reduce(1, (a, b) -> a * b);

//		if (scale % inverseScale == 0) {
//			scale = scale / inverseScale;
//			return new KernelSeries(OptionalInt.empty(), OptionalInt.of((int) Math.ceil(scale)));
//		} else {
//			return new KernelSeries(OptionalInt.empty(), OptionalInt.of(scale), inverseScale);
//		}

		return new KernelSeries(OptionalInt.empty(), OptionalInt.of(scale), inverseScale);
	}

	/**
	 * Multiplies two long values and returns an int result, capped at {@link Integer#MAX_VALUE}.
	 *
	 * @param a the first factor
	 * @param b the second factor
	 * @return the product, capped at {@code Integer.MAX_VALUE}
	 */
	private static int product(long a, long b) {
		long r = a * b;
		return r > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) r;
	}
}
