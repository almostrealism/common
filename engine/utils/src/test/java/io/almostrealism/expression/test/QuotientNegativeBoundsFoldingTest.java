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
}
