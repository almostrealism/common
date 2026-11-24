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
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Epsilon;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MaximumValue;
import io.almostrealism.expression.MinimumValue;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the precision-dependent constant references {@link Epsilon}, {@link MaximumValue}, and
 * {@link MinimumValue}.
 *
 * <p>Each renders the literal for a property of the code-generation precision, reports the
 * bound that follows from its sign, evaluates to the {@link Precision#FP64} property on the
 * host, and refuses assignment since it names no storage.</p>
 */
public class SpecialValueExpressionTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations fixed at {@link Precision#FP64} used to render expressions. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/** Epsilon renders the precision epsilon, is bounded to {@code [0, 1]}, and evaluates to zero. */
	@Test(timeout = 30000)
	public void epsilonRendersBoundsAndEvaluates() {
		Epsilon epsilon = new Epsilon();
		Assert.assertEquals(Precision.FP64.stringForDouble(Precision.FP64.epsilon(true)),
				epsilon.getExpression(lang));
		Assert.assertEquals(1, epsilon.upperBound(null).orElseThrow());
		Assert.assertEquals(0, epsilon.lowerBound(null).orElseThrow());
		Assert.assertEquals(0.0, epsilon.evaluate().doubleValue(), 0.0);
	}

	/** Assigning to epsilon is refused, since it names no writable storage. */
	@Test(timeout = 30000)
	public void epsilonRefusesAssignment() {
		try {
			new Epsilon().assign(e(1.0));
			Assert.fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// epsilon is not an assignment target
		}
	}

	/** The maximum value is a positive literal bounded below by zero and evaluates to the FP64 maximum. */
	@Test(timeout = 30000)
	public void maximumValueRendersBoundsAndEvaluates() {
		MaximumValue max = new MaximumValue();
		Assert.assertEquals(Precision.FP64.stringForDouble(-Precision.FP64.minValue()),
				max.getExpression(lang));
		Assert.assertEquals(0, max.lowerBound(null).orElseThrow());
		Assert.assertEquals(-Precision.FP64.minValue(), max.evaluate().doubleValue(), 0.0);
	}

	/** The minimum value is a negative literal bounded above by zero and evaluates to the FP64 minimum. */
	@Test(timeout = 30000)
	public void minimumValueRendersBoundsAndEvaluates() {
		MinimumValue min = new MinimumValue();
		Assert.assertEquals(Precision.FP64.stringForDouble(Precision.FP64.minValue()),
				min.getExpression(lang));
		Assert.assertEquals(0, min.upperBound(null).orElseThrow());
		Assert.assertEquals(Precision.FP64.minValue(), min.evaluate().doubleValue(), 0.0);
	}

	/** The maximum and minimum values are symmetric about zero and opposite in sign. */
	@Test(timeout = 30000)
	public void maximumAndMinimumAreOpposite() {
		Expression<Double> max = new MaximumValue();
		Expression<Double> min = new MinimumValue();
		Assert.assertEquals(max.evaluate().doubleValue(), -min.evaluate().doubleValue(), 0.0);
		Assert.assertTrue(max.evaluate().doubleValue() > 0.0);
		Assert.assertTrue(min.evaluate().doubleValue() < 0.0);
	}

	/** Assigning to the maximum or minimum value is refused, since neither names writable storage. */
	@Test(timeout = 30000)
	public void specialValuesRefuseAssignment() {
		try {
			new MaximumValue().assign(e(1.0));
			Assert.fail("expected UnsupportedOperationException from MaximumValue");
		} catch (UnsupportedOperationException expected) {
			// not an assignment target
		}

		try {
			new MinimumValue().assign(e(1.0));
			Assert.fail("expected UnsupportedOperationException from MinimumValue");
		} catch (UnsupportedOperationException expected) {
			// not an assignment target
		}
	}
}
