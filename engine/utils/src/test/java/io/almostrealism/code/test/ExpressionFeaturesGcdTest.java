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

package io.almostrealism.code.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.sequence.ArithmeticIndexSequence;
import io.almostrealism.sequence.KernelSeries;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Pins the behavior of the shared {@link ExpressionFeatures#gcd(long, long)}
 * helper and confirms the former private copies now delegate to it.
 *
 * <p>The greatest common divisor was implemented three times before
 * consolidation: {@code KernelSeries.gcd(int, int)} (used by
 * {@link KernelSeries#periodic}), {@code Quotient.gcd(long, long)}, and the
 * inline Euclidean loop inside {@link ArithmeticIndexSequence#commonFactor()}.
 * These tests lock in the arithmetic every call site relied on, including the
 * zero and negative-operand edges, so the single shared implementation cannot
 * silently change behavior for any of them.</p>
 */
public class ExpressionFeaturesGcdTest extends TestSuiteBase {

	/** The Euclidean GCD of two positive operands, in either argument order. */
	@Test(timeout = 5000)
	public void positiveOperands() {
		Assert.assertEquals(4, ExpressionFeatures.gcd(12, 8));
		Assert.assertEquals(4, ExpressionFeatures.gcd(8, 12));
		Assert.assertEquals(6, ExpressionFeatures.gcd(54, 24));
		Assert.assertEquals(1, ExpressionFeatures.gcd(17, 5));
	}

	/**
	 * Both operands are taken as non-negative, so a negative operand produces
	 * the same result as its absolute value.
	 */
	@Test(timeout = 5000)
	public void negativeOperandsUseAbsoluteValue() {
		Assert.assertEquals(4, ExpressionFeatures.gcd(-12, 8));
		Assert.assertEquals(4, ExpressionFeatures.gcd(12, -8));
		Assert.assertEquals(4, ExpressionFeatures.gcd(-12, -8));
	}

	/**
	 * {@code gcd(a, 0)} and {@code gcd(0, b)} return the non-zero operand, and
	 * {@code gcd(0, 0)} is zero — the contract {@code commonFactor()} relies on
	 * for the all-zero sequence.
	 */
	@Test(timeout = 5000)
	public void zeroOperands() {
		Assert.assertEquals(7, ExpressionFeatures.gcd(7, 0));
		Assert.assertEquals(7, ExpressionFeatures.gcd(0, 7));
		Assert.assertEquals(0, ExpressionFeatures.gcd(0, 0));
	}

	/**
	 * Operating in the {@code long} domain means {@link Integer#MIN_VALUE} does
	 * not overflow when its magnitude is taken (unlike {@code Math.abs} on an
	 * {@code int}), so the GCD with a positive operand is still correct.
	 */
	@Test(timeout = 5000)
	public void integerMinValueDoesNotOverflow() {
		Assert.assertEquals(4, ExpressionFeatures.gcd(Integer.MIN_VALUE, 12));
		Assert.assertEquals(1L << 31, ExpressionFeatures.gcd((long) Integer.MIN_VALUE, 0));
	}

	/**
	 * {@link ArithmeticIndexSequence#commonFactor()} is the GCD of the offset
	 * and the scale; it must agree with the shared helper.
	 */
	@Test(timeout = 5000)
	public void commonFactorDelegatesToGcd() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(12, 8, 1, 100, 100);
		Assert.assertEquals(ExpressionFeatures.gcd(12, 8), seq.commonFactor());
		Assert.assertEquals(4, seq.commonFactor());

		ArithmeticIndexSequence coprime = new ArithmeticIndexSequence(9, 4, 1, 100, 100);
		Assert.assertEquals(1, coprime.commonFactor());

		ArithmeticIndexSequence zeroScale = new ArithmeticIndexSequence(6, 0, 1, 100, 100);
		Assert.assertEquals(6, zeroScale.commonFactor());
	}

	/**
	 * {@link KernelSeries#periodic} reduces periods to their least common
	 * multiple, which is derived from the shared GCD; a shared factor between
	 * two periods must collapse the product accordingly.
	 */
	@Test(timeout = 5000)
	public void periodicUsesGcdDerivedLcm() {
		Assert.assertEquals(12, KernelSeries.periodic(Arrays.asList(4, 6)).getPeriod().getAsInt());
		Assert.assertEquals(15, KernelSeries.periodic(Arrays.asList(3, 5)).getPeriod().getAsInt());
		Assert.assertEquals(12, KernelSeries.periodic(Arrays.asList(2, 3, 4)).getPeriod().getAsInt());
	}
}
