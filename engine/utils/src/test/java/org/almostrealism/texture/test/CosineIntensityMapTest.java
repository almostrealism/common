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

import org.almostrealism.texture.CosineIntensityMap;
import org.almostrealism.texture.IntensityMap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link CosineIntensityMap}, verifying the cosine-modulation formula,
 * the output range, and the base-map accessor contract.
 */
public class CosineIntensityMapTest extends TestSuiteBase {
	/** Numerical tolerance for floating-point comparisons. */
	private static final double TOL = 1e-9;

	/**
	 * A deterministic base {@link IntensityMap} that returns a fixed constant,
	 * so the cosine formula can be checked against a hand-computed value.
	 *
	 * @param z the constant intensity to return
	 * @return an intensity map that always yields {@code z}
	 */
	private IntensityMap constantMap(double z) {
		return (u, v, w) -> z;
	}

	/**
	 * Verifies the default constructor applies {@code alpha=3.5, beta=2.5, tau=2.0}
	 * by comparing {@link CosineIntensityMap#getIntensity(double, double, double)}
	 * against the documented formula.
	 */
	@Test(timeout = 30000)
	public void defaultParametersMatchFormula() {
		CosineIntensityMap map = new CosineIntensityMap(constantMap(0.25));

		double u = 0.1;
		double v = 0.2;
		double w = 0.3;

		double z = 0.25;
		double t = 1 + Math.cos(Math.min(3.5 * v + 2.5 * z, 2.0 * Math.PI));
		double expected = t / 2.0;

		Assert.assertEquals(expected, map.getIntensity(u, v, w), TOL);
	}

	/**
	 * Verifies the explicit-parameter constructor is honored in the intensity
	 * computation by evaluating with custom alpha/beta/tau values.
	 */
	@Test(timeout = 30000)
	public void customParametersMatchFormula() {
		double alpha = 1.0;
		double beta = 0.5;
		double tau = 4.0;
		CosineIntensityMap map = new CosineIntensityMap(alpha, beta, tau, constantMap(0.6));

		double v = 0.7;
		double z = 0.6;
		double t = 1 + Math.cos(Math.min(alpha * v + beta * z, 2.0 * Math.PI));
		double expected = t / 2.0;

		Assert.assertEquals(expected, map.getIntensity(0.3, v, 0.9), TOL);
	}

	/**
	 * Verifies the result is always within [0, 1] because it is {@code (1 + cos(x)) / 2}.
	 */
	@Test(timeout = 30000)
	public void intensityIsBounded() {
		CosineIntensityMap map = new CosineIntensityMap(constantMap(0.4));

		for (int i = 0; i <= 20; i++) {
			double v = i / 20.0;
			double value = map.getIntensity(0.0, v, 0.0);
			Assert.assertTrue("value=" + value, value >= 0.0 - TOL && value <= 1.0 + TOL);
		}
	}

	/**
	 * Verifies the cosine argument is clamped at {@code 2*PI}: once the argument
	 * reaches that bound the intensity equals {@code (1 + cos(2*PI)) / 2 = 1.0}.
	 */
	@Test(timeout = 30000)
	public void argumentIsClampedAtTwoPi() {
		// alpha * v + beta * z is well above 2*PI, so cos is evaluated at exactly 2*PI
		CosineIntensityMap map = new CosineIntensityMap(100.0, 100.0, 1.0, constantMap(1.0));
		Assert.assertEquals(1.0, map.getIntensity(0.0, 1.0, 0.0), TOL);
	}

	/**
	 * Verifies {@link CosineIntensityMap#setIntensityMap(IntensityMap)} replaces the
	 * base map and {@link CosineIntensityMap#getIntensityMap()} returns it.
	 */
	@Test(timeout = 30000)
	public void baseMapAccessorRoundTrips() {
		CosineIntensityMap map = new CosineIntensityMap();
		Assert.assertNull(map.getIntensityMap());

		IntensityMap base = constantMap(0.5);
		map.setIntensityMap(base);
		Assert.assertSame(base, map.getIntensityMap());

		// The replacement base map must now drive the computation.
		double v = 0.1;
		double z = 0.5;
		double expected = (1 + Math.cos(Math.min(3.5 * v + 2.5 * z, 2.0 * Math.PI))) / 2.0;
		Assert.assertEquals(expected, map.getIntensity(0.0, v, 0.0), TOL);
	}
}
