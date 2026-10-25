/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.texture.test;

import org.almostrealism.texture.Noise;
import org.almostrealism.texture.Turbulence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Turbulence}, which sums octaves of {@link Noise} at doubling
 * frequencies. A constant-valued {@link Noise} substitute makes the octave sum
 * exactly predictable.
 */
public class TurbulenceTest extends TestSuiteBase {
	/** Numerical tolerance for floating-point comparisons. */
	private static final double TOL = 1e-9;

	/**
	 * A {@link Noise} that ignores position and always returns a fixed value, so
	 * the geometric octave sum can be checked in closed form.
	 */
	private static class ConstantNoise extends Noise {
		/** The constant intensity this noise always returns. */
		private final double value;

		/**
		 * Constructs a constant noise.
		 *
		 * @param value the fixed intensity to return
		 */
		ConstantNoise(double value) {
			super(8);
			this.value = value;
		}

		@Override
		public double getIntensity(double u, double v, double w) {
			return value;
		}
	}

	/**
	 * Verifies that summing a constant noise of 1.0 over {@code itr} octaves yields
	 * {@code sum(1/2^i)} for {@code i in [0, itr)} — a partial geometric series.
	 */
	@Test(timeout = 30000)
	public void octaveSumMatchesGeometricSeries() {
		Turbulence turbulence = new Turbulence(new ConstantNoise(1.0), 3);
		// 1/1 + 1/2 + 1/4 = 1.75
		Assert.assertEquals(1.75, turbulence.getIntensity(0.3, 0.4, 0.5), TOL);
	}

	/**
	 * Verifies a single octave reproduces the base noise value exactly.
	 */
	@Test(timeout = 30000)
	public void singleOctaveEqualsBaseNoise() {
		Turbulence turbulence = new Turbulence(new ConstantNoise(0.7), 1);
		Assert.assertEquals(0.7, turbulence.getIntensity(0.1, 0.2, 0.3), TOL);
	}

	/**
	 * Verifies that zero octaves accumulate nothing, returning 0.0.
	 */
	@Test(timeout = 30000)
	public void zeroOctavesReturnsZero() {
		Turbulence turbulence = new Turbulence(new ConstantNoise(1.0), 0);
		Assert.assertEquals(0.0, turbulence.getIntensity(0.5, 0.5, 0.5), TOL);
	}

	/**
	 * Verifies the default constructor (real {@link Noise}, 8 octaves) yields zero
	 * at the origin, where every octave samples a lattice point, and finite values
	 * elsewhere.
	 */
	@Test(timeout = 30000)
	public void defaultTurbulenceIsZeroAtOriginAndFiniteElsewhere() {
		Turbulence turbulence = new Turbulence();
		Assert.assertEquals(0.0, turbulence.getIntensity(0.0, 0.0, 0.0), TOL);

		double value = turbulence.getIntensity(0.137, 0.211, 0.059);
		Assert.assertFalse(Double.isNaN(value));
		Assert.assertFalse(Double.isInfinite(value));
	}
}
