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

package org.almostrealism.audio.sources.test;

import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the shared {@link SamplingFeatures#polyBlep(CollectionProducer, io.almostrealism.relation.Producer)}
 * anti-aliasing correction, which was previously duplicated privately in
 * {@code SawtoothWaveCell} and {@code SquareWaveCell}.
 *
 * <p>These tests pin the behavior of the consolidated implementation against the
 * mathematical definition of the PolyBLEP correction independently of the
 * implementation, so a regression in the shared method is caught directly rather
 * than only through the waveform cells that consume it.</p>
 */
public class PolyBlepTest extends TestSuiteBase implements SamplingFeatures {

	/** Absolute tolerance for floating point comparisons of correction values. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * Reference implementation of the PolyBLEP correction, computed with plain Java
	 * arithmetic at the top of the call stack so it can serve as an independent oracle.
	 *
	 * @param t  phase position within the cycle (0 to 1)
	 * @param dt phase increment per sample
	 * @return the expected correction value
	 */
	private double expectedPolyBlep(double t, double dt) {
		double correction = 0.0;
		if (t < dt) {
			double x = t / dt - 1.0;
			correction += -(x * x);
		}
		if (t > 1.0 - dt) {
			double x = (t - 1.0) / dt + 1.0;
			correction += x * x;
		}
		return correction;
	}

	/**
	 * Evaluates the consolidated {@link SamplingFeatures#polyBlep} for a single
	 * phase position and phase increment.
	 *
	 * @param t  phase position within the cycle (0 to 1)
	 * @param dt phase increment per sample
	 * @return the evaluated correction value
	 */
	private double evaluatePolyBlep(double t, double dt) {
		CollectionProducer blep = polyBlep(c(t), c(dt));
		return blep.get().evaluate().toDouble(0);
	}

	/**
	 * Verifies the correction matches its mathematical definition across the falling
	 * region ({@code t < dt}), the flat middle region, and the rising region
	 * ({@code t > 1 - dt}), including the region boundaries.
	 */
	@Test(timeout = 120000)
	public void polyBlepMatchesDefinition() {
		double dt = 0.05;
		double[] positions = {0.0, 0.01, 0.025, 0.04, 0.05, 0.3, 0.7, 0.95, 0.96, 0.975, 0.99, 1.0};

		for (double t : positions) {
			double expected = expectedPolyBlep(t, dt);
			double actual = evaluatePolyBlep(t, dt);
			log("polyBlep(t=" + t + ", dt=" + dt + ")=" + actual + " (expected " + expected + ")");
			Assert.assertEquals("polyBlep at t=" + t, expected, actual, TOLERANCE);
		}
	}

	/**
	 * Verifies the discontinuity is smoothed continuously: at the region boundaries
	 * {@code t == dt} and {@code t == 1 - dt} the correction is zero, so no step is
	 * introduced where the polynomial regions meet the flat middle.
	 */
	@Test(timeout = 60000)
	public void polyBlepIsZeroAtRegionBoundaries() {
		double dt = 0.05;
		Assert.assertEquals("correction at lower boundary t=dt", 0.0, evaluatePolyBlep(dt, dt), TOLERANCE);
		Assert.assertEquals("correction at upper boundary t=1-dt", 0.0, evaluatePolyBlep(1.0 - dt, dt), TOLERANCE);
		Assert.assertEquals("correction in flat middle", 0.0, evaluatePolyBlep(0.5, dt), TOLERANCE);
	}

	/**
	 * Verifies the {@code dt} parameter threads through the correction by evaluating
	 * the same phase position against two different phase increments.
	 */
	@Test(timeout = 60000)
	public void polyBlepThreadsPhaseIncrement() {
		Assert.assertEquals(expectedPolyBlep(0.05, 0.1), evaluatePolyBlep(0.05, 0.1), TOLERANCE);
		Assert.assertEquals(expectedPolyBlep(0.05, 0.2), evaluatePolyBlep(0.05, 0.2), TOLERANCE);
		Assert.assertEquals(expectedPolyBlep(0.95, 0.1), evaluatePolyBlep(0.95, 0.1), TOLERANCE);
	}
}
