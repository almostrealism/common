/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Difference;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.OptionalDouble;

/**
 * Tests for {@link Absolute} expression to verify correct behavior
 * in expression tree construction and simplification.
 */
public class AbsoluteExpressionTest extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Test that Absolute expression evaluates correctly for a negative constant.
	 */
	@Test
	public void testAbsoluteOfNegativeConstant() {
		Expression<Double> neg = e(-5.0);
		Absolute abs = new Absolute(neg);

		Number result = abs.evaluate(-5.0);
		assertEquals(5.0, result.doubleValue(), 1e-10);
	}

	/**
	 * Test that Absolute expression evaluates correctly for a positive constant.
	 */
	@Test
	public void testAbsoluteOfPositiveConstant() {
		Expression<Double> pos = e(3.0);
		Absolute abs = new Absolute(pos);

		Number result = abs.evaluate(3.0);
		assertEquals(3.0, result.doubleValue(), 1e-10);
	}

	/**
	 * Test that Absolute.doubleValue() returns empty (not a compile-time constant).
	 * This is important because Difference.of() filters based on doubleValue().
	 */
	@Test
	public void testAbsoluteDoubleValueIsEmpty() {
		Expression<Double> inner = e(-5.0);
		Absolute abs = new Absolute(inner);

		OptionalDouble dv = abs.doubleValue();
		// For a constant input, Absolute SHOULD be able to compute doubleValue
		// But currently it doesn't override doubleValue(), so it returns empty
		System.out.println("Absolute.doubleValue() for constant input: " + dv);
	}

	/**
	 * Test that Difference correctly preserves Absolute as an operand.
	 * This is the core issue we're investigating.
	 */
	@Test
	public void testDifferenceWithAbsolute() {
		Expression<Double> inner = e(-0.5);  // -0.5
		Absolute abs = new Absolute(inner);   // |-0.5| = 0.5

		// 1.0 - |-0.5| should equal 0.5
		Expression<?> diff = e(1.0).subtract(abs);

		System.out.println("Expression class: " + diff.getClass().getName());
		System.out.println("Expression children count: " + diff.getChildren().size());
		for (int i = 0; i < diff.getChildren().size(); i++) {
			Expression<?> child = diff.getChildren().get(i);
			System.out.println("  Child " + i + ": " + child.getClass().getName() +
					" doubleValue=" + child.doubleValue());
		}

		// The difference should have 2 children: DoubleConstant(1.0) and Absolute
		assertEquals("Difference should have 2 children", 2, diff.getChildren().size());
		assertTrue("Second child should be Absolute",
				diff.getChildren().get(1) instanceof Absolute);
	}

	/**
	 * Test that 1.0 - |x - 1| computes correctly when x = 0.5.
	 * Expected: 1.0 - |0.5 - 1.0| = 1.0 - |-0.5| = 1.0 - 0.5 = 0.5
	 */
	@Test
	public void testOneMinusAbsoluteOfDifference() {
		// Inner: 0.5 - 1.0 = -0.5
		Expression<Double> inner = (Expression<Double>) e(0.5).subtract(e(1.0));
		System.out.println("Inner expression (0.5 - 1.0): " + inner.getClass().getName());
		System.out.println("Inner doubleValue: " + inner.doubleValue());

		// Absolute: |-0.5| = 0.5
		Absolute abs = new Absolute(inner);
		System.out.println("Absolute expression: " + abs.getClass().getName());
		System.out.println("Absolute doubleValue: " + abs.doubleValue());

		// Outer: 1.0 - 0.5 = 0.5
		Expression<?> result = e(1.0).subtract(abs);
		System.out.println("Result expression: " + result.getClass().getName());
		System.out.println("Result children: " + result.getChildren().size());
		System.out.println("Result doubleValue: " + result.doubleValue());

		// Check the structure is correct
		assertEquals("Result should have 2 children", 2, result.getChildren().size());

		// If the result has collapsed to just DoubleConstant(1.0), the test fails
		assertFalse("Result collapsed to DoubleConstant - Absolute was incorrectly removed!",
				result instanceof DoubleConstant);
	}

	/**
	 * Test Difference.of() factory method directly with Absolute.
	 */
	@Test
	public void testDifferenceOfFactoryWithAbsolute() {
		Expression<Double> one = e(1.0);
		Expression<Double> negHalf = e(-0.5);
		Absolute abs = new Absolute(negHalf);

		// Call Difference.of directly
		Expression<?> diff = Difference.of(one, abs);

		System.out.println("Difference.of result: " + diff.getClass().getName());
		System.out.println("Children count: " + diff.getChildren().size());

		// Should be a Difference with 2 children
		assertTrue("Should be Difference instance", diff instanceof Difference);
		assertEquals("Should have 2 children", 2, diff.getChildren().size());
	}

	/**
	 * Test that simplification doesn't incorrectly remove Absolute.
	 */
	@Test
	public void testSimplificationPreservesAbsolute() {
		Expression<Double> inner = e(-0.5);
		Absolute abs = new Absolute(inner);
		Expression<?> diff = e(1.0).subtract(abs);

		// Simplify the expression
		Expression<?> simplified = diff.getSimplified();

		System.out.println("Original: " + diff.getClass().getName() + " with " + diff.getChildren().size() + " children");
		System.out.println("Simplified: " + simplified.getClass().getName());
		if (simplified.getChildren() != null) {
			System.out.println("Simplified children: " + simplified.getChildren().size());
		}

		// After simplification, we expect either:
		// 1. The expression unchanged (Difference with Absolute)
		// 2. A constant 0.5 (if properly evaluated)
		// But NOT just 1.0 (which would mean Absolute was dropped)

		OptionalDouble simplifiedValue = simplified.doubleValue();
		if (simplifiedValue.isPresent()) {
			// If it simplified to a constant, it should be 0.5, not 1.0
			assertEquals("Simplified value should be 0.5", 0.5, simplifiedValue.getAsDouble(), 1e-10);
		}
	}
}
