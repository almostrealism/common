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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for the sum-numerator folds in {@link Quotient#create(Expression[])} that
 * distribute a truncating integer quotient across the terms of a sum.
 *
 * <p>Distributing {@code (a + b) / d} as {@code a / d + b / d} is only valid when no term is
 * negative. An exactly-divisible term (for example {@code 4 * i}, which divides to the integer
 * {@code i}) absorbs the remainder of a negative sibling term in the wrong direction, because
 * truncation rounds that sibling's quotient toward zero while the combined value rounds the other
 * way. Before the guard was added, {@code (4 * i + (-i)) / 4} folded to {@code i + (-i) / 4} and
 * evaluated to {@code 1} at {@code i == 1}, whereas the true value {@code trunc(3 / 4)} is
 * {@code 0}. These tests pin the evaluated value against plain truncating integer arithmetic so a
 * reintroduced distribution is caught regardless of the simplified structure.</p>
 */
public class QuotientNegativeTermDistributionTest extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * A positive exactly-divisible term summed with a negative non-divisible term
	 * ({@code (4 * i + (-i)) / 4}) must evaluate as {@code trunc((4 * i - i) / 4)} at every index,
	 * not as the unsound per-term distribution {@code i + trunc((-i) / 4)}.
	 */
	@Test(timeout = 30000)
	public void divisibleProductPlusNegativeTermIsNotDistributed() {
		Expression<?> numerator = Sum.of(kernel().multiply(4), Minus.of(kernel()));
		Expression<?> quotient = Quotient.of(numerator, e(4));

		for (int i = 0; i < 64; i++) {
			long expected = (4L * i - i) / 4;
			Assert.assertEquals("at " + i, expected,
					quotient.value(new IndexValues().put(kernel(), i)).longValue());
		}
	}

	/**
	 * A positive exactly-divisible term summed with a negative constant ({@code (2 * i + (-5)) / 2})
	 * must evaluate as {@code trunc((2 * i - 5) / 2)} at every index. The negative constant total
	 * is the only remainder source, but truncating its quotient in isolation over-counts, so the
	 * sum must be left unfolded.
	 */
	@Test(timeout = 30000)
	public void divisibleProductPlusNegativeConstantIsNotDistributed() {
		Expression<?> numerator = Sum.of(kernel().multiply(2), e(-5));
		Expression<?> quotient = Quotient.of(numerator, e(2));

		for (int i = 0; i < 64; i++) {
			long expected = (2L * i - 5) / 2;
			Assert.assertEquals("at " + i, expected,
					quotient.value(new IndexValues().put(kernel(), i)).longValue());
		}
	}
}
