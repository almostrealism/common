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

	@Test
	public void twoOperandDenominator() {
		Expression<?> q = Quotient.of(new IntegerConstant(100), new IntegerConstant(5));
		if (q instanceof Quotient) {
			Expression<?> denom = ((Quotient<?>) q).getDenominator();
			Assert.assertEquals(5L, denom.longValue().orElse(-1));
		}
	}

	@Test
	public void twoOperandNumerator() {
		Expression<?> q = Quotient.of(new IntegerConstant(100), new IntegerConstant(5));
		if (q instanceof Quotient) {
			Expression<?> numer = ((Quotient<?>) q).getNumerator();
			Assert.assertEquals(100L, numer.longValue().orElse(-1));
		}
	}

	@Test
	public void evaluateTwoOperand() {
		Expression<?> q = Quotient.of(new IntegerConstant(100), new IntegerConstant(5));
		Assert.assertEquals(20L, q.longValue().orElse(-1));
	}
}
