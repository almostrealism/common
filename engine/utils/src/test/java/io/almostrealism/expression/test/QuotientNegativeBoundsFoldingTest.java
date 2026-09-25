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
import io.almostrealism.expression.LongConstant;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.StaticReference;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Demonstrates that the bounded-numerator constant folding in
 * {@link Quotient} must agree with the truncating integer division
 * semantics used by {@link Quotient#evaluate} and
 * {@link Quotient#computeValue}.
 *
 * <p>Integer division in this framework truncates toward zero (the
 * javadoc of {@code withoutBoundedRemainder} states {@code -4 / 4 is -1}),
 * but the bounds-folding branch of {@code Quotient.create} collapses a
 * bounded numerator to {@code floor(bound / divisor)}. For a negative
 * numerator {@code floor} and truncation disagree, so a folded quotient
 * can produce a value that the same expression would never compute when
 * evaluated directly.</p>
 */
public class QuotientNegativeBoundsFoldingTest extends TestSuiteBase {

	/**
	 * A negative numerator whose lower and upper bounds fold to a single
	 * constant must fold to the truncating quotient, not the floor. For
	 * {@code -3 / 2} truncation gives {@code -1}; {@code Math.floor(-1.5)}
	 * gives {@code -2}.
	 */
	@Test(timeout = 5000)
	public void negativeConstantFoldsToTruncatingQuotient() {
		Expression<?> q = Quotient.of(new IntegerConstant(-3), new IntegerConstant(2));
		Assert.assertEquals("-3 / 2 truncates toward zero to -1",
				-1L, q.longValue().orElse(Long.MIN_VALUE));
	}

	/**
	 * The folded constant must match the truncating integer division that
	 * {@link Quotient#evaluate} and {@link Quotient#computeValue} perform
	 * ({@code value / divisor} in Java), so that constant folding never changes
	 * the value of an expression. Checks a spread of negative numerators.
	 */
	@Test(timeout = 5000)
	public void foldedValueMatchesTruncatingDivision() {
		for (int numerator = -1; numerator >= -8; numerator--) {
			long expected = numerator / 2L;
			Expression<?> folded = Quotient.of(new IntegerConstant(numerator), new IntegerConstant(2));
			Assert.assertEquals("folding must not change the value of " + numerator + " / 2",
					expected, folded.longValue().orElse(Long.MIN_VALUE));
		}
	}

	/**
	 * A {@link Long}-typed quotient must fold to the truncating integer quotient,
	 * exactly as an {@link Integer}-typed one does. {@code isFP()} is false for a
	 * long quotient, so the bounds fold applies; {@code -3 / 2} must collapse to
	 * {@code -1}, never {@code -2}.
	 */
	@Test(timeout = 5000)
	public void longTypedFoldsToTruncatingQuotient() {
		Expression<?> q = Quotient.of(new LongConstant(-3L), new LongConstant(2L));
		Assert.assertEquals("long -3 / 2 truncates toward zero to -1",
				-1L, q.longValue().orElse(Long.MIN_VALUE));
	}

	/**
	 * {@link Quotient#evaluate} must truncate toward zero for every non-floating-point
	 * type, not only {@link Integer}. A {@link Long}-typed quotient of an unbounded
	 * numerator (which cannot fold) must still evaluate {@code -3 / 2} to {@code -1.0},
	 * matching the constant fold and {@code computeValue}; the pre-fix code took the
	 * floating-point branch for long quotients and produced {@code -1.5}.
	 */
	@Test(timeout = 5000)
	public void longTypedEvaluateTruncatesTowardZero() {
		Expression<?> q = Quotient.of(
				new StaticReference<>(Long.class, "n"), new LongConstant(2L));
		Assert.assertTrue("an unbounded long numerator must not fold to a constant",
				q instanceof Quotient);

		Number result = q.evaluate(-3L, 2L);
		Assert.assertEquals("long -3 / 2 must evaluate to -1 (truncating), not -1.5",
				-1.0, result.doubleValue(), 0.0);

		Number positive = q.evaluate(7L, 2L);
		Assert.assertEquals("long 7 / 2 must evaluate to 3 (truncating)",
				3.0, positive.doubleValue(), 0.0);
	}

	/**
	 * A quotient with a constant zero divisor must not fold at construction. The
	 * bounded-numerator fold divides by the divisor, so a zero divisor would throw
	 * an {@link ArithmeticException} while the expression is being simplified. The
	 * degenerate division-by-zero must instead be left intact so it surfaces at
	 * evaluation, where integer division by zero throws in the normal way.
	 */
	@Test(timeout = 5000)
	public void zeroDivisorIsNotFoldedAtConstruction() {
		Expression<?> q = Quotient.of(new IntegerConstant(5), new IntegerConstant(0));
		Assert.assertTrue("a zero-divisor quotient must remain a Quotient, not fold",
				q instanceof Quotient);
	}
}
