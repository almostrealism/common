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

package org.almostrealism.heredity.test;

import org.almostrealism.heredity.Breeders;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests the documented contract of {@link Breeders#perturbation(double, double, double)}:
 * the magnitude's absolute value is used (its sign does not matter) and the result is
 * bounded between {@code s1} and {@code s2}.
 */
public class BreedersPerturbationTest extends TestSuiteBase {

	/**
	 * The three worked examples in the {@link Breeders#perturbation} javadoc, which
	 * exercise the positive-magnitude path and must continue to hold.
	 */
	@Test(timeout = 120000)
	public void documentedExamples() {
		assertEquals(0.3, Breeders.perturbation(0.0, 1.0, 0.3));
		assertEquals(1.0, Breeders.perturbation(0.0, 1.0, 2.0));
		assertEquals(0.7, Breeders.perturbation(1.0, 0.0, 0.3));
	}

	/**
	 * The {@code magnitude} parameter is documented as "absolute value is used", so a
	 * negative magnitude must produce the same result as its positive counterpart. The
	 * result must also remain bounded between {@code s1} and {@code s2}.
	 */
	@Test(timeout = 120000)
	public void negativeMagnitudeUsesAbsoluteValue() {
		// s2 > s1: moving from 0.0 toward 1.0 by |-0.3| must land at 0.3, inside [0, 1].
		assertEquals(0.3, Breeders.perturbation(0.0, 1.0, -0.3));

		// s1 > s2: moving from 1.0 toward 0.0 by |-0.3| must land at 0.7, inside [0, 1].
		assertEquals(0.7, Breeders.perturbation(1.0, 0.0, -0.3));
	}

	/**
	 * A negative magnitude whose absolute value overshoots the span must still clamp to
	 * the far bound, exactly as the positive case does, rather than escaping the range.
	 */
	@Test(timeout = 120000)
	public void negativeMagnitudeClampsWithinBounds() {
		// |-2.0| overshoots the span, so the result clamps to the far bound 1.0.
		assertEquals(1.0, Breeders.perturbation(0.0, 1.0, -2.0));

		// Mirror case: clamps to the far bound 0.0 rather than escaping below it.
		assertEquals(0.0, Breeders.perturbation(1.0, 0.0, -2.0));
	}

	/**
	 * When {@code s1 == s2} no perturbation is possible and the starting value is returned
	 * regardless of magnitude.
	 */
	@Test(timeout = 120000)
	public void equalBoundsReturnStart() {
		assertEquals(2.0, Breeders.perturbation(2.0, 2.0, 0.5));
		assertEquals(2.0, Breeders.perturbation(2.0, 2.0, -0.5));
	}
}
