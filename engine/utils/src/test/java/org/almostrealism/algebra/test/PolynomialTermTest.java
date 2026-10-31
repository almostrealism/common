/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.algebra.test;

import org.almostrealism.algebra.PolynomialTerm;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the partial-derivative behaviour of {@link PolynomialTerm}.
 *
 * <p>These tests pin down a defect in the derivative construction: when a term
 * does not depend on the variable being differentiated (its exponent for that
 * variable is {@code 0}), the true partial derivative is the constant {@code 0}.
 * {@link PolynomialTerm#calculateDx()} (and the {@code y}/{@code z} equivalents)
 * computed the derivative exponent as {@code exponent - 1} unconditionally, so a
 * zero exponent became {@code -1}. The resulting term is {@code 0 * v^(-1)},
 * which {@link PolynomialTerm#evaluate(double, double, double)} renders as
 * {@code 0 * (1 / v)}; at {@code v == 0} that is {@code 0 * Infinity == NaN}
 * rather than the correct {@code 0.0}.</p>
 */
public class PolynomialTermTest extends TestSuiteBase {

	/**
	 * The partial derivative of a constant term with respect to {@code x} is
	 * {@code 0} everywhere, including at {@code x == 0}. Before the fix this
	 * evaluated to {@code NaN} at {@code x == 0}.
	 */
	@Test(timeout = 10000)
	public void derivativeOfConstantIsZeroAtOrigin() {
		PolynomialTerm constant = new PolynomialTerm(5.0, 0, 0, 0);

		Assert.assertEquals(0.0, constant.getDx().evaluate(0, 1, 1), 1e-9);
		Assert.assertEquals(0.0, constant.getDx().evaluate(2, 1, 1), 1e-9);
	}

	/**
	 * Differentiating a term with respect to a variable it does not contain
	 * yields {@code 0} everywhere, including where that variable is {@code 0}.
	 * {@code 3y^2} differentiated by {@code x} is {@code 0}; before the fix this
	 * was {@code NaN} at {@code x == 0}.
	 */
	@Test(timeout = 10000)
	public void derivativeWithRespectToAbsentVariableIsZero() {
		PolynomialTerm term = new PolynomialTerm(3.0, 0, 2, 0);

		Assert.assertEquals(0.0, term.getDx().evaluate(0, 1, 1), 1e-9);

		PolynomialTerm product = new PolynomialTerm(7.0, 1, 1, 0);
		Assert.assertEquals(0.0, product.getDz().evaluate(1, 1, 0), 1e-9);
	}

	/**
	 * The derivative of a term that does not depend on the differentiation
	 * variable must not carry a negative exponent for that variable: the result
	 * is a genuine constant-zero term, not a term with a {@code v^(-1)} pole.
	 */
	@Test(timeout = 10000)
	public void derivativeWithRespectToAbsentVariableHasNonNegativeExponent() {
		PolynomialTerm constant = new PolynomialTerm(5.0, 0, 0, 0);

		Assert.assertTrue("derivative x-exponent must be non-negative",
				constant.getDx().getExpOfX() >= 0);

		PolynomialTerm term = new PolynomialTerm(3.0, 2, 0, 1);
		Assert.assertTrue("derivative y-exponent must be non-negative",
				term.getDy().getExpOfY() >= 0);
	}

	/**
	 * Genuine (non-zero) partial derivatives are unchanged by the fix: the power
	 * rule still reduces the exponent and multiplies in the old exponent.
	 */
	@Test(timeout = 10000)
	public void genuineDerivativesFollowThePowerRule() {
		// d/dx(5 x^2 y^3 z) = 10 x y^3 z
		PolynomialTerm term = new PolynomialTerm(5.0, 2, 3, 1);
		Assert.assertEquals(10.0 * 2.0 * 8.0 * 3.0,
				term.getDx().evaluate(2, 2, 3), 1e-9);

		// d/dy(3 y^2) = 6 y, evaluated at y = 2 is 12
		PolynomialTerm quadratic = new PolynomialTerm(3.0, 0, 2, 0);
		Assert.assertEquals(12.0, quadratic.getDy().evaluate(1, 2, 1), 1e-9);
	}

	/**
	 * A non-zero exponent follows the power rule even when it is negative: only a
	 * zero exponent (an absent variable) is collapsed to a constant-zero term. So
	 * {@code d/dx(x^-2) = -2 x^-3}, which at {@code x == 2} is {@code -2 / 8 = -0.25},
	 * and the derivative exponent remains {@code -3} rather than being clamped to
	 * {@code 0}.
	 */
	@Test(timeout = 10000)
	public void derivativeOfNegativeExponentFollowsThePowerRule() {
		PolynomialTerm inverseSquare = new PolynomialTerm(1.0, -2, 0, 0);

		Assert.assertEquals(-3, inverseSquare.getDx().getExpOfX());
		Assert.assertEquals(-2.0 / 8.0, inverseSquare.getDx().evaluate(2, 1, 1), 1e-9);
	}
}
