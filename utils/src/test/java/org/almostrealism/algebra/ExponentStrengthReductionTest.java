/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for algebraic strength reduction in {@link Exponent}.
 *
 * <p>Verifies that {@code pow()} calls with small integer exponents are replaced
 * with equivalent multiply/divide operations to avoid the expensive {@code pow()}
 * library call in generated code. These tests ensure the following reductions:</p>
 * <ul>
 *   <li>{@code pow(x, 2.0)} &rarr; {@code x * x}</li>
 *   <li>{@code pow(x, 3.0)} &rarr; {@code x * x * x}</li>
 *   <li>{@code pow(x, -1.0)} &rarr; {@code 1.0 / x}</li>
 *   <li>{@code pow(x, -2.0)} &rarr; {@code 1.0 / (x * x)}</li>
 *   <li>{@code pow(x, -3.0)} &rarr; {@code 1.0 / (x * x * x)}</li>
 * </ul>
 *
 * @see Exponent
 */
public class ExponentStrengthReductionTest extends TestSuiteBase {

	private static final LanguageOperationsStub LANG = new LanguageOperationsStub();

	/**
	 * Verifies that {@code pow(x, 2.0)} is reduced to a multiplication
	 * and the generated code contains no {@code pow()} call.
	 */
	@Test(timeout = 10000)
	public void powSquareReducedToProduct() {
		Expression<Double> base = new StaticReference<>(Double.class, "x");
		Expression<Double> result = Exponent.of(base, new DoubleConstant(2.0));

		Assert.assertFalse("pow(x, 2.0) should not produce an Exponent node",
				result instanceof Exponent);

		String code = result.getExpression(LANG);
		Assert.assertFalse("Generated code should not contain pow() for x^2",
				code.contains("pow("));
		Assert.assertTrue("Generated code should use multiplication for x^2",
				code.contains("*"));
	}

	/**
	 * Verifies that {@code pow(x, 3.0)} is reduced to multiplications
	 * and the generated code contains no {@code pow()} call.
	 */
	@Test(timeout = 10000)
	public void powCubeReducedToProduct() {
		Expression<Double> base = new StaticReference<>(Double.class, "x");
		Expression<Double> result = Exponent.of(base, new DoubleConstant(3.0));

		Assert.assertFalse("pow(x, 3.0) should not produce an Exponent node",
				result instanceof Exponent);

		String code = result.getExpression(LANG);
		Assert.assertFalse("Generated code should not contain pow() for x^3",
				code.contains("pow("));
	}

	/**
	 * Verifies that {@code pow(x, -1.0)} is reduced to a division
	 * and the generated code contains no {@code pow()} call.
	 */
	@Test(timeout = 10000)
	public void powNegOneReducedToQuotient() {
		Expression<Double> base = new StaticReference<>(Double.class, "x");
		Expression<Double> result = Exponent.of(base, new DoubleConstant(-1.0));

		Assert.assertFalse("pow(x, -1.0) should not produce an Exponent node",
				result instanceof Exponent);

		String code = result.getExpression(LANG);
		Assert.assertFalse("Generated code should not contain pow() for x^-1",
				code.contains("pow("));
		Assert.assertTrue("Generated code should use division for x^-1",
				code.contains("/"));
	}

	/**
	 * Verifies that {@code pow(x, -2.0)} is reduced to division and multiplication
	 * and the generated code contains no {@code pow()} call.
	 */
	@Test(timeout = 10000)
	public void powNegTwoReducedToQuotientProduct() {
		Expression<Double> base = new StaticReference<>(Double.class, "x");
		Expression<Double> result = Exponent.of(base, new DoubleConstant(-2.0));

		Assert.assertFalse("pow(x, -2.0) should not produce an Exponent node",
				result instanceof Exponent);

		String code = result.getExpression(LANG);
		Assert.assertFalse("Generated code should not contain pow() for x^-2",
				code.contains("pow("));
	}

	/**
	 * Verifies that {@code pow(x, -3.0)} is reduced to division and multiplication
	 * and the generated code contains no {@code pow()} call.
	 */
	@Test(timeout = 10000)
	public void powNegThreeReducedToQuotientProduct() {
		Expression<Double> base = new StaticReference<>(Double.class, "x");
		Expression<Double> result = Exponent.of(base, new DoubleConstant(-3.0));

		Assert.assertFalse("pow(x, -3.0) should not produce an Exponent node",
				result instanceof Exponent);

		String code = result.getExpression(LANG);
		Assert.assertFalse("Generated code should not contain pow() for x^-3",
				code.contains("pow("));
	}

	/**
	 * Verifies that non-reducible exponents (e.g., 4.0, 0.5, 1.5) still
	 * produce {@code pow()} calls since they cannot be efficiently replaced
	 * with a small number of multiply/divide operations.
	 */
	@Test(timeout = 10000)
	public void nonReducibleExponentPreservesPow() {
		Expression<Double> base = new StaticReference<>(Double.class, "x");

		Expression<Double> pow4 = Exponent.of(base, new DoubleConstant(4.0));
		String code4 = pow4.getExpression(LANG);
		Assert.assertTrue("pow(x, 4.0) should still use pow()",
				code4.contains("pow("));

		Expression<Double> powHalf = Exponent.of(base, new DoubleConstant(0.5));
		String codeHalf = powHalf.getExpression(LANG);
		Assert.assertTrue("pow(x, 0.5) should still use pow()",
				codeHalf.contains("pow("));
	}

	/**
	 * Verifies that the existing constant-folding optimizations still work:
	 * {@code pow(x, 0.0)} returns 1.0 and {@code pow(x, 1.0)} returns x unchanged.
	 */
	@Test(timeout = 10000)
	public void existingConstantFoldingPreserved() {
		Expression<Double> base = new StaticReference<>(Double.class, "x");

		Expression<Double> pow0 = Exponent.of(base, new DoubleConstant(0.0));
		Assert.assertTrue("pow(x, 0.0) should return a constant 1.0",
				pow0 instanceof DoubleConstant);
		Assert.assertEquals("pow(x, 0.0) should equal 1.0",
				1.0, pow0.doubleValue().getAsDouble(), 0.0);

		Expression<Double> pow1 = Exponent.of(base, new DoubleConstant(1.0));
		Assert.assertEquals("pow(x, 1.0) should return x unchanged",
				"x", pow1.getExpression(LANG));
	}

	/**
	 * Verifies that the strength reduction produces numerically correct results
	 * by evaluating the reduced expressions with concrete values.
	 */
	@Test(timeout = 10000)
	public void strengthReductionNumericallyCorrect() {
		double[] testValues = { 0.5, 1.0, 2.0, 3.0, 0.1, 7.5 };
		double[] exponents = { 2.0, 3.0, -1.0, -2.0, -3.0 };

		for (double baseVal : testValues) {
			for (double expVal : exponents) {
				Expression<Double> base = new DoubleConstant(baseVal);
				Expression<Double> result = Exponent.of(base, new DoubleConstant(expVal));
				double expected = Math.pow(baseVal, expVal);
				double actual = result.doubleValue().getAsDouble();
				Assert.assertEquals(
						"pow(" + baseVal + ", " + expVal + ") should be " + expected,
						expected, actual, 1e-10);
			}
		}
	}

	/**
	 * Verifies that nested {@code pow()} calls are both reduced. This mimics the
	 * AudioScene envelope pattern: {@code pow((- pow(genome, 3.0)) + 1.0, -1.0)}.
	 */
	@Test(timeout = 10000)
	public void nestedPowCallsBothReduced() {
		Expression<Double> genome = new StaticReference<>(Double.class, "genome_val");

		// Inner: pow(genome_val, 3.0) → genome_val * genome_val * genome_val
		Expression<Double> innerPow = Exponent.of(genome, new DoubleConstant(3.0));
		// Outer: pow(expr, -1.0) → 1.0 / expr
		Expression<Double> negated = (Expression<Double>) innerPow.multiply(-1.0);
		Expression<Double> plusOne = (Expression<Double>) negated.add(new DoubleConstant(1.0));
		Expression<Double> outerPow = Exponent.of(plusOne, new DoubleConstant(-1.0));

		String code = outerPow.getExpression(LANG);
		Assert.assertFalse(
				"Nested pow() pattern should not contain any pow() calls, got: " + code,
				code.contains("pow("));
	}
}
