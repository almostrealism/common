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

package org.almostrealism.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that every tolerance-based comparison helper in {@link TestFeatures} rejects a non-finite
 * actual value rather than silently passing it.
 *
 * <p>Each helper expresses its test as {@code Math.abs(expected - actual)} against a threshold, and
 * that difference is {@code NaN} whenever either operand is {@code NaN} — a comparison that is
 * always false, so the threshold test alone reports a {@code NaN} result as correct. These tests
 * pin the tightened behaviour: a {@code NaN} actual fails against a finite expected, a finite actual
 * fails against a {@code NaN} expected, two {@code NaN} values are equal, and finite values still
 * pass or fail by tolerance exactly as before. Infinities are checked under the same exact-match
 * rule.</p>
 */
public class ComparisonHelperNonFiniteTest extends TestSuiteBase {
	/**
	 * Asserts that running {@code assertion} throws an {@link AssertionError}, reporting
	 * {@code context} if it does not.
	 *
	 * @param context description of the case, reported when the assertion unexpectedly passes
	 * @param assertion the comparison-helper call expected to fail
	 */
	private static void assertFails(String context, Runnable assertion) {
		try {
			assertion.run();
		} catch (AssertionError expected) {
			return;
		}

		Assert.fail("Expected an AssertionError but none was thrown: " + context);
	}

	/** {@link TestFeatures#assertSimilar(String, double, double, double, double)} — the terminal overload. */
	@Test(timeout = 30000)
	public void assertSimilarMagnitudeRejectsNonFinite() {
		assertFails("NaN actual", () -> assertSimilar("m", 1.0, Double.NaN, 0.001, 10.0));
		assertFails("NaN expected", () -> assertSimilar("m", Double.NaN, 1.0, 0.001, 10.0));
		assertFails("infinity actual", () -> assertSimilar("m", 1.0, Double.POSITIVE_INFINITY, 0.001, 10.0));
		assertFails("mismatched infinities",
				() -> assertSimilar("m", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.001, 10.0));

		assertSimilar("m", Double.NaN, Double.NaN, 0.001, 10.0);
		assertSimilar("m", Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.001, 10.0);
		assertSimilar("m", 1.0, 1.0005, 0.001, 10.0);
		assertFails("finite outside tolerance", () -> assertSimilar("m", 1.0, 2.0, 0.001, 10.0));
	}

	/** {@link TestFeatures#assertSimilar(String, double, double, double)}. */
	@Test(timeout = 30000)
	public void assertSimilarRelativeWithMessageRejectsNonFinite() {
		assertFails("NaN actual", () -> assertSimilar("m", 1.0, Double.NaN, 0.001));
		assertFails("NaN expected", () -> assertSimilar("m", Double.NaN, 1.0, 0.001));

		assertSimilar("m", Double.NaN, Double.NaN, 0.001);
		assertSimilar("m", 1.0, 1.0005, 0.001);
		assertFails("finite outside tolerance", () -> assertSimilar("m", 1.0, 2.0, 0.001));
	}

	/** {@link TestFeatures#assertSimilar(String, double, double)}. */
	@Test(timeout = 30000)
	public void assertSimilarDefaultWithMessageRejectsNonFinite() {
		assertFails("NaN actual", () -> assertSimilar("m", 1.0, Double.NaN));
		assertFails("NaN expected", () -> assertSimilar("m", Double.NaN, 1.0));

		assertSimilar("m", Double.NaN, Double.NaN);
		assertSimilar("m", 1.0, 1.0005);
		assertFails("finite outside tolerance", () -> assertSimilar("m", 1.0, 2.0));
	}

	/** {@link TestFeatures#assertSimilar(double, double, double)}. */
	@Test(timeout = 30000)
	public void assertSimilarRelativeRejectsNonFinite() {
		assertFails("NaN actual", () -> assertSimilar(1.0, Double.NaN, 0.001));
		assertFails("NaN expected", () -> assertSimilar(Double.NaN, 1.0, 0.001));

		assertSimilar(Double.NaN, Double.NaN, 0.001);
		assertSimilar(1.0, 1.0005, 0.001);
		assertFails("finite outside tolerance", () -> assertSimilar(1.0, 2.0, 0.001));
	}

	/** {@link TestFeatures#assertSimilar(double, double)}. */
	@Test(timeout = 30000)
	public void assertSimilarDefaultRejectsNonFinite() {
		assertFails("NaN actual", () -> assertSimilar(1.0, Double.NaN));
		assertFails("NaN expected", () -> assertSimilar(Double.NaN, 1.0));

		assertSimilar(Double.NaN, Double.NaN);
		assertSimilar(1.0, 1.0005);
		assertFails("finite outside tolerance", () -> assertSimilar(1.0, 2.0));
	}

	/** {@link TestFeatures#assertEquals(double, double, double)} (delta). */
	@Test(timeout = 30000)
	public void assertEqualsDeltaRejectsNonFinite() {
		assertFails("NaN actual", () -> assertEquals(1.0, Double.NaN, 0.1));
		assertFails("NaN expected", () -> assertEquals(Double.NaN, 1.0, 0.1));
		assertFails("infinity actual", () -> assertEquals(1.0, Double.POSITIVE_INFINITY, 0.1));
		assertFails("mismatched infinities",
				() -> assertEquals(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.1));

		assertEquals(Double.NaN, Double.NaN, 0.1);
		assertEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.1);
		assertEquals(1.0, 1.05, 0.1);
		assertFails("finite outside delta", () -> assertEquals(1.0, 2.0, 0.1));
	}

	/** {@link TestFeatures#assertEquals(String, double, double, double)} (delta with message). */
	@Test(timeout = 30000)
	public void assertEqualsDeltaWithMessageRejectsNonFinite() {
		assertFails("NaN actual", () -> assertEquals("m", 1.0, Double.NaN, 0.1));
		assertFails("NaN expected", () -> assertEquals("m", Double.NaN, 1.0, 0.1));

		assertEquals("m", Double.NaN, Double.NaN, 0.1);
		assertEquals("m", 1.0, 1.05, 0.1);
		assertFails("finite outside delta", () -> assertEquals("m", 1.0, 2.0, 0.1));
	}

	/** {@link TestFeatures#assertEquals(double, double)} (hardware-epsilon tolerance). */
	@Test(timeout = 30000)
	public void assertEqualsEpsilonRejectsNonFinite() {
		assertFails("NaN actual", () -> assertEquals(1.0, Double.NaN));
		assertFails("NaN expected", () -> assertEquals(Double.NaN, 1.0));
		assertFails("infinity actual", () -> assertEquals(1.0, Double.POSITIVE_INFINITY));
		assertFails("mismatched infinities",
				() -> assertEquals(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));

		assertEquals(Double.NaN, Double.NaN);
		assertEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
		assertEquals(1.0, 1.0);
		assertFails("finite outside tolerance", () -> assertEquals(1.0, 2.0));
	}

	/** {@link TestFeatures#assertEquals(String, double, double)} (hardware-epsilon tolerance with message). */
	@Test(timeout = 30000)
	public void assertEqualsEpsilonWithMessageRejectsNonFinite() {
		assertFails("NaN actual", () -> assertEquals("m", 1.0, Double.NaN));
		assertFails("NaN expected", () -> assertEquals("m", Double.NaN, 1.0));

		assertEquals("m", Double.NaN, Double.NaN);
		assertEquals("m", 1.0, 1.0);
		assertFails("finite outside tolerance", () -> assertEquals("m", 1.0, 2.0));
	}

	/**
	 * {@link TestFeatures#assertNotEquals(double, double)} judges non-finite values by exact
	 * equality: identical non-finite values count as equal and so fail the not-equal assertion,
	 * while a non-finite value against a different value counts as unequal and passes.
	 */
	@Test(timeout = 30000)
	public void assertNotEqualsJudgesNonFiniteByExactEquality() {
		assertFails("NaN vs NaN are equal", () -> assertNotEquals(Double.NaN, Double.NaN));
		assertFails("matching infinities are equal",
				() -> assertNotEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));

		assertNotEquals(1.0, Double.NaN);
		assertNotEquals(Double.NaN, 1.0);
		assertNotEquals(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
		assertNotEquals(1.0, 2.0);
	}
}
