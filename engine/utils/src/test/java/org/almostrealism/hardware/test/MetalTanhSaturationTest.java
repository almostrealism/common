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

package org.almostrealism.hardware.test;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Verifies that a Metal {@code tanh} kernel saturates to &plusmn;1 for large-magnitude
 * arguments instead of overflowing to NaN.
 *
 * <p>Metal kernels are compiled with fast math, and the fast hyperbolic tangent evaluates
 * through an exponential that overflows single precision once the argument exceeds roughly
 * 44 in magnitude, producing NaN where the true value is &plusmn;1. A saturating activation
 * such as dynamic tanh normalization ({@code tanh(x * alpha)}) reaches those magnitudes
 * routinely, and one NaN then spreads through every downstream operation. The compiled
 * Metal source selects the precise {@code tanh} so that every argument yields a finite
 * result, matching the native backends; this test pins that behavior.</p>
 */
public class MetalTanhSaturationTest extends TestSuiteBase {

	/**
	 * Evaluates {@code tanh} on Metal over arguments spanning well past the overflow
	 * threshold and checks every result against {@link Math#tanh(double)}. Skips when
	 * no Metal backend is available.
	 */
	@Test(timeout = 60000)
	public void largeMagnitudeArgumentsSaturateInsteadOfOverflowing() {
		if (SemaphoreChainBatchingTest.metalContext() == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		PackedCollection input = PackedCollection.of(
				-1000.0, -227.0, -100.0, -68.7, -50.0, -46.2, -44.0, -30.0, -10.0, -1.0,
				0.0, 1.0, 10.0, 30.0, 44.0, 46.2, 50.0, 68.7, 100.0, 227.0, 1000.0);

		// The kernel compiles on first evaluation, so the requirement must cover both steps
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(ComputeRequirement.MTL));
		PackedCollection output;

		try {
			Evaluable<PackedCollection> kernel = tanh(traverseEach(p(input))).get();
			output = kernel.evaluate();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}

		Assert.assertEquals(input.getMemLength(), output.getMemLength());

		for (int i = 0; i < input.getMemLength(); i++) {
			double argument = input.toDouble(i);
			double actual = output.toDouble(i);
			Assert.assertTrue("tanh(" + argument + ") must be finite on Metal, was " + actual,
					Double.isFinite(actual));
			Assert.assertEquals("tanh(" + argument + ")", Math.tanh(argument), actual, 1e-5);
		}
	}
}
