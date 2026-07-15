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

package org.almostrealism.ml.audio.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.DiffusionNoiseScheduler;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Random;

/**
 * Tests that {@link DiffusionNoiseScheduler}'s device-computed cosine schedule matches
 * a host-computed reference for every timestep.
 */
public class DiffusionNoiseSchedulerTests extends TestSuiteBase {

	/**
	 * Tests the schedule against the reference cosine-schedule formulas: alphas_cumprod
	 * from the clipped squared cosine, the per-step alphas ratios (alpha at step zero
	 * equal to the first cumulative alpha), and the two square-root schedules observed
	 * through {@code addNoise} with unit and zero inputs.
	 */
	@Test(timeout = 120000)
	public void cosineScheduleReference() {
		int steps = 32;
		DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(steps, new Random(1));

		double s = 0.008;
		double[] expected = new double[steps];

		for (int t = 0; t < steps; t++) {
			double progress = (t + 1.0) / steps;
			double f = Math.cos((progress + s) / (1 + s) * Math.PI / 2);
			expected[t] = Math.max(0.0001, Math.min(0.9999, f * f));
		}

		PackedCollection one = new PackedCollection(shape(1)).fill(1.0);
		PackedCollection zero = new PackedCollection(shape(1)).fill(0.0);

		for (int t = 0; t < steps; t++) {
			assertEquals(expected[t], scheduler.getAlphaCumprod(t));

			double expectedAlpha = t == 0 ? expected[0] : expected[t] / expected[t - 1];
			assertEquals(expectedAlpha, scheduler.getAlpha(t));

			assertEquals(Math.sqrt(expected[t]),
					scheduler.addNoise(one, zero, t).get().evaluate().toDouble(0));
			assertEquals(Math.sqrt(1.0 - expected[t]),
					scheduler.addNoise(zero, one, t).get().evaluate().toDouble(0));
		}
	}
}
