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
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.expression.Rectify;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the behavior of the clamping expressions {@link Max}, {@link Min}, and the
 * rectified-linear specialisation {@link Rectify}.
 *
 * <p>Each folds constant operands to the {@link Math#max}/{@link Math#min} result, renders
 * through the language {@code max}/{@code min} call, evaluates with the matching {@link Math}
 * operation, and reports a bound derived from the operand bounds. {@link Max#of} additionally
 * promotes {@code max(x, 0)} to a {@link Rectify}.</p>
 */
public class MinMaxRectifyExpressionTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions to source. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/** {@link Max#of} over two constants folds to the larger; over one operand returns that operand's value. */
	@Test(timeout = 30000)
	public void maxFoldsConstants() {
		Assert.assertEquals(5.0, Max.of(e(3.0), e(5.0)).doubleValue().getAsDouble(), 0.0);
		Assert.assertEquals(4.0, Max.of(e(4.0)).doubleValue().getAsDouble(), 0.0);
	}

	/** {@link Max#of} rejects an empty operand list. */
	@Test(timeout = 30000)
	public void maxRejectsNoOperands() {
		try {
			Max.of();
			Assert.fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// max requires at least one operand
		}
	}

	/** A zero operand promotes {@code max(x, 0)} to a {@link Rectify} in either operand order. */
	@Test(timeout = 30000)
	public void maxOfZeroPromotesToRectify() {
		Expression<Double> x = kernel().toDouble();
		Assert.assertTrue(Max.of(x, e(0.0)) instanceof Rectify);
		Assert.assertTrue(Max.of(e(0.0), x) instanceof Rectify);
	}

	/** A non-constant {@link Max} renders as a {@code max} call, evaluates via {@link Math#max}, and bounds by the larger operand bound. */
	@Test(timeout = 30000)
	public void maxBuildsNodeWithBounds() {
		Expression a = kernel().imod(4);
		Expression b = kernel().imod(6);
		Expression<Double> node = Max.of(a, b);

		Assert.assertTrue(node instanceof Max);
		Assert.assertTrue(node.getExpression(lang).startsWith("max("));
		Assert.assertEquals(Math.max(3.0, 5.0), node.evaluate(3.0, 5.0).doubleValue(), 0.0);
		Assert.assertEquals(5, node.upperBound(null).orElseThrow());
	}

	/** {@link Min#of} over two constants folds to the smaller. */
	@Test(timeout = 30000)
	public void minFoldsConstants() {
		Assert.assertEquals(3.0, Min.of(e(3.0), e(5.0)).doubleValue().getAsDouble(), 0.0);
		Assert.assertEquals(-2.0, Min.of(e(-2.0), e(7.0)).doubleValue().getAsDouble(), 0.0);
	}

	/** A non-constant {@link Min} renders as a {@code min} call, evaluates via {@link Math#min}, and bounds by the smaller operand bound. */
	@Test(timeout = 30000)
	public void minBuildsNodeWithBounds() {
		Expression a = kernel().imod(4);
		Expression b = kernel().imod(6);
		Expression node = Min.of(a, b);

		Assert.assertTrue(node instanceof Min);
		Assert.assertTrue(node.getExpression(lang).startsWith("min("));
		Assert.assertEquals(Math.min(3.0, 5.0), node.evaluate(3.0, 5.0).doubleValue(), 0.0);
		Assert.assertEquals(3, node.upperBound(null).orElseThrow());
	}

	/** {@link Rectify#of} folds a constant to {@code max(x, 0)} and clamps a negative constant to zero. */
	@Test(timeout = 30000)
	public void rectifyFoldsConstants() {
		Assert.assertEquals(5.0, Rectify.of(e(5.0)).doubleValue().getAsDouble(), 0.0);
		Assert.assertEquals(0.0, Rectify.of(e(-2.0)).doubleValue().getAsDouble(), 0.0);
	}

	/** A non-constant {@link Rectify} is a {@link Max} against zero and evaluates as a rectifier does. */
	@Test(timeout = 30000)
	public void rectifyBuildsNode() {
		Expression<Double> node = Rectify.of(kernel().toDouble());
		Assert.assertTrue(node instanceof Rectify);
		Assert.assertTrue(node instanceof Max);
		Assert.assertEquals(3.0, node.evaluate(3.0, 0.0).doubleValue(), 0.0);
		Assert.assertEquals(0.0, node.evaluate(-3.0, 0.0).doubleValue(), 0.0);
	}
}
