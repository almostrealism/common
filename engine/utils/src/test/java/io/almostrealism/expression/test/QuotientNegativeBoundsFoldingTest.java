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

import io.almostrealism.expression.ArithmeticGenerator;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.LongConstant;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.sequence.DefaultIndex;
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
 * so the bounds-folding branch of {@code Quotient.create} must collapse a
 * bounded numerator with truncating division rather than
 * {@code floor(bound / divisor)}. For a negative numerator {@code floor}
 * and truncation disagree, and a floor-based fold would produce a value
 * that the same expression never computes when evaluated directly. The
 * tests also pin the zero-divisor contract: a known integer zero divisor
 * is never folded, so the division-by-zero surfaces at evaluation.</p>
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
	 * An {@link Integer}-typed quotient must evaluate with truncating division and
	 * report its result as an {@link Integer}, so the value keeps the expression's
	 * declared type after the evaluate path was widened to cover every
	 * non-floating-point type.
	 */
	@Test(timeout = 5000)
	public void integerTypedEvaluateTruncatesAndKeepsType() {
		Expression<?> q = Quotient.of(
				new StaticReference<>(Integer.class, "n"), new IntegerConstant(2));
		Assert.assertTrue("an unbounded integer numerator must not fold to a constant",
				q instanceof Quotient);

		Number result = q.evaluate(-7, 2);
		Assert.assertTrue("an integer quotient must evaluate to an Integer",
				result instanceof Integer);
		Assert.assertEquals("integer -7 / 2 truncates toward zero to -3",
				-3, result.intValue());
	}

	/**
	 * A floating-point quotient must not truncate: the {@code isFP()} branch of
	 * {@link Quotient#evaluate} keeps full double division.
	 */
	@Test(timeout = 5000)
	public void floatingPointEvaluateDoesNotTruncate() {
		Expression<?> q = Quotient.of(
				new StaticReference<>(Double.class, "x"), new DoubleConstant(2.0));
		Assert.assertTrue("an unbounded double numerator must not fold to a constant",
				q instanceof Quotient);

		Number result = q.evaluate(-3.0, 2.0);
		Assert.assertEquals("double -3 / 2 must evaluate to -1.5",
				-1.5, result.doubleValue(), 0.0);
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

	/**
	 * A {@link Long}-typed zero divisor must also be left unfolded at construction,
	 * for the same reason as the {@link Integer} case: {@code isFP()} is false, so
	 * the integer-division fold paths apply and a zero divisor would either throw
	 * during simplification or fold the degenerate quotient to a constant.
	 */
	@Test(timeout = 5000)
	public void longZeroDivisorIsNotFoldedAtConstruction() {
		Expression<?> q = Quotient.of(new LongConstant(5L), new LongConstant(0L));
		Assert.assertTrue("a long zero-divisor quotient must remain a Quotient, not fold",
				q instanceof Quotient);
	}

	/**
	 * The unfolded zero-divisor quotient must surface the division-by-zero when it is
	 * finally evaluated. Integer division by zero throws {@link ArithmeticException},
	 * which is the intended place for the degenerate operation to fail rather than
	 * during constant folding.
	 */
	@Test(timeout = 5000)
	public void zeroDivisorThrowsAtEvaluation() {
		Expression<?> q = Quotient.of(new IntegerConstant(5), new IntegerConstant(0));
		Assert.assertTrue("a zero-divisor quotient must remain a Quotient, not fold",
				q instanceof Quotient);

		try {
			q.evaluate(5, 0);
			Assert.fail("integer division by zero must throw at evaluation");
		} catch (ArithmeticException expected) {
			// division by zero surfaces here, as intended
		}
	}

	/**
	 * A zero divisor must not be folded even when the numerator is also zero. The
	 * zero-numerator shortcut in {@code Quotient.create} would otherwise collapse
	 * {@code 0 / 0} to {@code 0}, contradicting the zero-divisor contract: {@code 0 / 0}
	 * is undefined and integer division by zero throws at evaluation, so it must be
	 * left as a {@link Quotient} rather than folded to a constant during simplification.
	 */
	@Test(timeout = 5000)
	public void zeroNumeratorZeroDivisorIsNotFoldedAtConstruction() {
		Expression<?> q = Quotient.of(new IntegerConstant(0), new IntegerConstant(0));
		Assert.assertTrue("0 / 0 must remain a Quotient, not fold to 0",
				q instanceof Quotient);
	}

	/**
	 * The {@link Long}-typed {@code 0 / 0} must likewise be left unfolded at
	 * construction, for the same reason as the {@link Integer} case.
	 */
	@Test(timeout = 5000)
	public void longZeroNumeratorZeroDivisorIsNotFoldedAtConstruction() {
		Expression<?> q = Quotient.of(new LongConstant(0L), new LongConstant(0L));
		Assert.assertTrue("long 0 / 0 must remain a Quotient, not fold to 0",
				q instanceof Quotient);
	}

	/**
	 * The unfolded {@code 0 / 0} quotient must surface the division-by-zero when it is
	 * finally evaluated, exactly as a non-zero numerator over a zero divisor does.
	 */
	@Test(timeout = 5000)
	public void zeroNumeratorZeroDivisorThrowsAtEvaluation() {
		Expression<?> q = Quotient.of(new IntegerConstant(0), new IntegerConstant(0));
		Assert.assertTrue("0 / 0 must remain a Quotient, not fold to 0",
				q instanceof Quotient);

		try {
			q.evaluate(0, 0);
			Assert.fail("integer 0 / 0 must throw at evaluation");
		} catch (ArithmeticException expected) {
			// division by zero surfaces here, as intended
		}
	}

	/**
	 * A zero numerator over a non-zero divisor must still fold to {@code 0}. The
	 * zero-divisor guard added ahead of the zero-numerator shortcut must not disturb
	 * this legitimate simplification: {@code 0 / 5} is exactly {@code 0}.
	 */
	@Test(timeout = 5000)
	public void zeroNumeratorNonZeroDivisorFoldsToZero() {
		Expression<?> q = Quotient.of(new IntegerConstant(0), new IntegerConstant(5));
		Assert.assertFalse("0 / 5 must fold rather than remain a Quotient",
				q instanceof Quotient);
		Assert.assertEquals("0 / 5 folds to 0",
				0L, q.longValue().orElse(Long.MIN_VALUE));
	}

	/**
	 * An {@link ArithmeticGenerator} numerator over a zero divisor must be left
	 * unfolded, not routed through {@link ArithmeticGenerator#divide}. That path
	 * coarsens the generator by evaluating {@code getScale() % divisor}, which throws
	 * {@link ArithmeticException} at construction for a zero divisor. The zero-divisor
	 * guard must run before the {@code ArithmeticGenerator} optimization so the
	 * degenerate division-by-zero surfaces at evaluation, matching the contract for
	 * every other integer zero divisor.
	 */
	@Test(timeout = 5000)
	public void arithmeticGeneratorZeroDivisorIsNotFoldedAtConstruction() {
		Expression<? extends Number> generator =
				ArithmeticGenerator.create(new DefaultIndex("i", 16), 2, 4, 16);
		Assert.assertTrue("scale-2 generator must be an ArithmeticGenerator",
				generator instanceof ArithmeticGenerator);

		Expression<?> q = Quotient.of(generator, new IntegerConstant(0));
		Assert.assertTrue("an ArithmeticGenerator over a zero divisor must remain a Quotient, not throw or fold",
				q instanceof Quotient);
	}
}
