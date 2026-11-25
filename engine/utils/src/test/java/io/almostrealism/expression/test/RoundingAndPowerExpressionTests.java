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
import io.almostrealism.expression.Atan2;
import io.almostrealism.expression.Ceiling;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Quotient;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Pins the host-side behavior of the rounding expressions ({@link Floor}, {@link Ceiling}),
 * the two-argument arctangent ({@link Atan2}), and the power expression ({@link Exponent}).
 *
 * <p>Every node evaluates on the host with the same {@link Math} operation its generated code
 * performs, so these tests assert that the factory reductions (constant folding, the
 * integer-identity of {@code floor}/{@code ceil}, and the algebraic strength reduction of
 * {@code pow}) and the evaluation paths agree with an independent Java computation.</p>
 */
public class RoundingAndPowerExpressionTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions to source. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/** A non-constant double operand, so the factories build nodes rather than fold. */
	private Expression<Double> operand() {
		return kernel().toDouble();
	}

	/** {@link Floor#of} folds a constant to the floored literal and rounds toward negative infinity. */
	@Test(timeout = 30000)
	public void floorFoldsConstants() {
		Assert.assertEquals(3.0, Floor.of(e(3.7)).doubleValue().getAsDouble(), 0.0);
		Assert.assertEquals(-4.0, Floor.of(e(-3.2)).doubleValue().getAsDouble(), 0.0);
	}

	/** {@link Ceiling#of} folds a constant to the ceiled literal and rounds toward positive infinity. */
	@Test(timeout = 30000)
	public void ceilingFoldsConstants() {
		Assert.assertEquals(4.0, Ceiling.of(e(3.2)).doubleValue().getAsDouble(), 0.0);
		Assert.assertEquals(-3.0, Ceiling.of(e(-3.7)).doubleValue().getAsDouble(), 0.0);
	}

	/**
	 * An integer operand needs no rounding, so {@code floor}/{@code ceil} return the operand
	 * unchanged rather than emitting a floating-point library call.
	 */
	@Test(timeout = 30000)
	public void roundingOfIntegerOperandIsIdentity() {
		Expression<Integer> index = kernel();
		Assert.assertFalse(index.isFP());
		Assert.assertSame(index, Floor.of(index));
		Assert.assertSame(index, Ceiling.of(index));
	}

	/** A floating-point operand produces a {@code floor}/{@code ceil} call with no constant value. */
	@Test(timeout = 30000)
	public void roundingOfFloatingPointOperandBuildsCall() {
		Expression<Double> floor = Floor.of(operand());
		Assert.assertTrue(floor instanceof Floor);
		Assert.assertTrue(floor.getExpression(lang).startsWith("floor("));
		Assert.assertFalse(floor.doubleValue().isPresent());
		Assert.assertEquals(3.0, floor.evaluate(3.7).doubleValue(), 0.0);

		Expression<Double> ceil = Ceiling.of(operand());
		Assert.assertTrue(ceil instanceof Ceiling);
		Assert.assertTrue(ceil.getExpression(lang).startsWith("ceil("));
		Assert.assertFalse(ceil.doubleValue().isPresent());
		Assert.assertEquals(4.0, ceil.evaluate(3.2).doubleValue(), 0.0);
	}

	/** {@code recreate} rebuilds a rounding node around its one new operand, and rejects any other arity. */
	@Test(timeout = 30000)
	public void roundingRecreateRebuildsAndFolds() {
		Expression<?> built = Floor.of(operand());
		Floor floor = (Floor) built;
		Assert.assertEquals(2.0, floor.recreate(List.of(e(2.7))).doubleValue().getAsDouble(), 0.0);

		try {
			floor.recreate(List.of(e(1.0), e(2.0)));
			Assert.fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// floor takes exactly one operand
		}
	}

	/** Two constant operands fold {@code atan2} to the {@link Math#atan2} literal. */
	@Test(timeout = 30000)
	public void atan2FoldsConstants() {
		Expression<Double> folded = Atan2.of(e(1.0), e(1.0));
		Assert.assertTrue(folded instanceof DoubleConstant);
		Assert.assertEquals(Math.atan2(1.0, 1.0), folded.doubleValue().getAsDouble(), 1e-12);
	}

	/**
	 * A non-constant {@code atan2} renders both arguments, evaluates via {@link Math#atan2}, and
	 * reports conservative bounds of {@code [-4, 4]} (the integer bracket of {@code [-pi, pi]}).
	 */
	@Test(timeout = 30000)
	public void atan2BuildsNodeWithBounds() {
		Atan2 node = (Atan2) Atan2.of(operand(), e(2.0));
		Assert.assertEquals(2.0, node.getX().doubleValue().getAsDouble(), 0.0);
		Assert.assertFalse(node.getY().doubleValue().isPresent());
		Assert.assertTrue(node.getExpression(lang).startsWith("atan2("));
		Assert.assertEquals(Math.atan2(3.0, 4.0), node.evaluate(3.0, 4.0).doubleValue(), 1e-12);
		Assert.assertEquals(30, node.getComputeCost());

		Assert.assertEquals(4, node.upperBound(null).orElseThrow());
		Assert.assertEquals(-4, node.lowerBound(null).orElseThrow());
		Assert.assertTrue(node.isPossiblyNegative());

		try {
			node.recreate(List.of(e(1.0)));
			Assert.fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// atan2 takes exactly two operands
		}
	}

	/** {@link Exponent#create} folds the identities of the exponent: {@code x^0 == 1} and {@code x^1 == x}. */
	@Test(timeout = 30000)
	public void exponentFoldsExponentIdentities() {
		Expression<Double> base = operand();
		Assert.assertEquals(1.0, Exponent.create(base, e(0.0)).doubleValue().getAsDouble(), 0.0);
		Assert.assertSame(base, Exponent.create(base, e(1.0)));
	}

	/** Both constant operands fold {@code pow} to the literal, and a constant base of 0 or 1 folds too. */
	@Test(timeout = 30000)
	public void exponentFoldsConstantOperands() {
		Assert.assertEquals(8.0, Exponent.create(e(2.0), e(3.0)).doubleValue().getAsDouble(), 1e-12);
		Assert.assertEquals(0.0, Exponent.create(e(0.0), operand()).doubleValue().getAsDouble(), 0.0);
		Assert.assertEquals(1.0, Exponent.create(e(1.0), operand()).doubleValue().getAsDouble(), 0.0);
	}

	/** {@code x^-1} is strength-reduced to a reciprocal rather than a {@code pow} call. */
	@Test(timeout = 30000)
	public void exponentReducesNegativeOnePowerToReciprocal() {
		Expression<Double> reduced = Exponent.create(operand(), e(-1.0));
		Assert.assertFalse(reduced instanceof Exponent);
		Assert.assertTrue(reduced instanceof Quotient);
	}

	/** A non-reducible power renders through the language {@code pow}, evaluates via {@link Math#pow}, and costs 20. */
	@Test(timeout = 30000)
	public void exponentBuildsNodeForNonConstantOperands() {
		Exponent node = (Exponent) Exponent.create(operand(), operand());
		Assert.assertTrue(node.getExpression(lang).startsWith("pow("));
		Assert.assertEquals(Math.pow(2.0, 3.0), node.evaluate(2.0, 3.0).doubleValue(), 1e-12);
		Assert.assertEquals(20, node.getComputeCost());

		try {
			node.recreate(List.of(e(1.0)));
			Assert.fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// pow takes exactly two operands
		}
	}
}
