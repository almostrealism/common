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

package io.almostrealism.expression.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Quotient;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@link Quotient#getDenominator()} supports multi-operand quotients
 * by returning the product of all denominator terms.
 */
public class QuotientMultiOperandTest extends TestSuiteBase {

	/** Verifies that a two-operand quotient returns the second operand as the denominator. */
	@Test(timeout = 5000)
	public void twoOperandDenominator() {
		Expression<?> q = Quotient.of(new IntegerConstant(100), new IntegerConstant(5));
		if (q instanceof Quotient) {
			Expression<?> denom = ((Quotient<?>) q).getDenominator();
			Assert.assertEquals(5L, denom.longValue().orElse(-1));
		} else {
			// Quotient.of may simplify to a constant (100/5 = 20); verify the evaluated value
			Assert.assertEquals(20L, q.longValue().orElse(-1));
		}
	}

	/** Verifies that a two-operand quotient returns the first operand as the numerator. */
	@Test(timeout = 5000)
	public void twoOperandNumerator() {
		Expression<?> q = Quotient.of(new IntegerConstant(100), new IntegerConstant(5));
		if (q instanceof Quotient) {
			Expression<?> numer = ((Quotient<?>) q).getNumerator();
			Assert.assertEquals(100L, numer.longValue().orElse(-1));
		} else {
			Assert.assertEquals(20L, q.longValue().orElse(-1));
		}
	}

	/** Verifies that a two-operand quotient evaluates correctly. */
	@Test(timeout = 5000)
	public void evaluateTwoOperand() {
		Expression<?> q = Quotient.of(new IntegerConstant(100), new IntegerConstant(5));
		Assert.assertEquals(20L, q.longValue().orElse(-1));
	}

	/**
	 * Verifies that a three-operand quotient {@code 120 / 3 / 4} returns the product
	 * of all denominator terms ({@code 3 * 4 = 12}) from {@link Quotient#getDenominator()}.
	 */
	@Test(timeout = 5000)
	public void threeOperandDenominator() {
		Expression<?> q = Quotient.of(new IntegerConstant(120), new IntegerConstant(3), new IntegerConstant(4));
		if (q instanceof Quotient) {
			Expression<?> denom = ((Quotient<?>) q).getDenominator();
			Assert.assertEquals("Denominator should be product of all denominator terms",
					12L, denom.longValue().orElse(-1));
		} else {
			Assert.assertEquals("120 / 3 / 4 should evaluate to 10",
					10L, q.longValue().orElse(-1));
		}
	}

	/** Verifies that a three-operand quotient evaluates correctly via {@link Expression#evaluate}. */
	@Test(timeout = 5000)
	public void threeOperandEvaluate() {
		Expression<?> q = Quotient.of(new IntegerConstant(120), new IntegerConstant(3), new IntegerConstant(4));
		Assert.assertEquals("120 / 3 / 4 should be 10", 10L, q.longValue().orElse(-1));
	}

	/**
	 * Verifies that a four-operand quotient {@code 240 / 2 / 3 / 4} evaluates
	 * to the correct integer result.
	 */
	@Test(timeout = 5000)
	public void fourOperandEvaluate() {
		Expression<?> q = Quotient.of(
				new IntegerConstant(240), new IntegerConstant(2),
				new IntegerConstant(3), new IntegerConstant(4));
		Assert.assertEquals("240 / 2 / 3 / 4 should be 10", 10L, q.longValue().orElse(-1));
	}
}
