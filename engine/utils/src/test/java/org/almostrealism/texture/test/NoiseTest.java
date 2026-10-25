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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Noise}, the Perlin-style gradient noise function.
 *
 * <p>The gradient table is seeded with {@link Math#random()}, so point values are
 * not reproducible. These tests therefore assert properties that hold for every
 * seed: the value at integer lattice points is exactly zero (the gradients are
 * dotted with a zero offset), the smoothing kernel {@code omega} has known
 * closed-form values, and arbitrary samples are finite.</p>
 */
public class NoiseTest extends TestSuiteBase {
	/** Numerical tolerance for floating-point comparisons. */
	private static final double TOL = 1e-9;

	/**
	 * A {@link Noise} subclass that exposes the protected kernel helpers so their
	 * closed-form behavior can be asserted directly.
	 */
	private static class ProbeNoise extends Noise {
		/** Constructs a probe with a small lattice. */
		ProbeNoise() {
			super(32);
		}

		/**
		 * Exposes the protected smoothing kernel.
		 *
		 * @param t the kernel input
		 * @return the kernel weight
		 */
		double omegaKernel(double t) {
			return omega(t);
		}

		/**
		 * Exposes the protected permutation hash.
		 *
		 * @param t the input coordinate
		 * @return the permuted gradient index
		 */
		int permute(int t) {
			return phi(t);
		}
	}

	/**
	 * Verifies that at a point whose scaled coordinates are integers, the noise
	 * value is exactly zero: only the enclosing corner contributes and its gradient
	 * is dotted with a zero offset. Holds regardless of the random gradient table.
	 */
	@Test(timeout = 30000)
	public void valueAtLatticePointIsZero() {
		Noise noise = new Noise();

		// scaleU/V/W are 10, so u=0.1 maps to the integer lattice coordinate 1.0.
		Assert.assertEquals(0.0, noise.getIntensity(0.0, 0.0, 0.0), TOL);
		Assert.assertEquals(0.0, noise.getIntensity(0.1, 0.2, 0.3), TOL);
		Assert.assertEquals(0.0, noise.getIntensity(0.5, 0.5, 0.5), TOL);
	}

	/**
	 * Verifies noise samples at non-lattice points are finite real numbers.
	 */
	@Test(timeout = 30000)
	public void arbitrarySamplesAreFinite() {
		Noise noise = new Noise();

		for (int i = 1; i < 10; i++) {
			double u = i / 13.0;
			double value = noise.getIntensity(u, u * 0.5, u * 0.25);
			Assert.assertFalse("NaN at i=" + i, Double.isNaN(value));
			Assert.assertFalse("Inf at i=" + i, Double.isInfinite(value));
		}
	}

	/**
	 * Verifies the cubic smoothing kernel {@code omega(t) = 2t^3 - 3t^2 + 1} for
	 * {@code |t| < 1} and {@code 0} otherwise, including its symmetry in {@code t}.
	 */
	@Test(timeout = 30000)
	public void smoothingKernelHasClosedForm() {
		ProbeNoise noise = new ProbeNoise();

		Assert.assertEquals(1.0, noise.omegaKernel(0.0), TOL);
		Assert.assertEquals(0.5, noise.omegaKernel(0.5), TOL);
		// Symmetric: |t| is taken, so -0.5 matches +0.5.
		Assert.assertEquals(0.5, noise.omegaKernel(-0.5), TOL);
		// At and beyond the unit boundary the kernel is zero.
		Assert.assertEquals(0.0, noise.omegaKernel(1.0), TOL);
		Assert.assertEquals(0.0, noise.omegaKernel(2.5), TOL);
	}

	/**
	 * Verifies the permutation hash maps every input into the valid gradient-index
	 * range {@code [0, n)} for a table of size {@code n}.
	 */
	@Test(timeout = 30000)
	public void permutationStaysInRange() {
		ProbeNoise noise = new ProbeNoise();

		for (int t = 0; t < 100; t++) {
			int idx = noise.permute(t);
			Assert.assertTrue("idx=" + idx, idx >= 0 && idx < 32);
		}
	}
}
