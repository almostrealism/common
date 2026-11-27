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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests that a single-element operand on the left of a binary operation takes the
 * shape of the operand on the right, so the result can be combined with other
 * producers of that shape.
 */
public class ScalarOperandShapeTests extends TestSuiteBase {

	/** Rows of the two-dimensional operand. */
	private static final int ROWS = 3;

	/** Columns of the two-dimensional operand. */
	private static final int COLUMNS = 41;

	/**
	 * Builds a {@code [ROWS, COLUMNS]} collection whose element at {@code (r, c)} is
	 * {@code r * COLUMNS + c + 1}.
	 *
	 * @return the collection
	 */
	private PackedCollection matrix() {
		return integers(1, ROWS * COLUMNS + 1).reshape(shape(ROWS, COLUMNS)).evaluate();
	}

	/**
	 * Asserts that two shapes have the same dimensions, whatever their traversal axes.
	 *
	 * @param expected the expected shape
	 * @param actual   the actual shape
	 */
	private static void assertShape(TraversalPolicy expected, TraversalPolicy actual) {
		if (!expected.equalsIgnoreAxis(actual)) {
			throw new AssertionError(actual + " != " + expected);
		}
	}

	/** {@code c(1.0).subtract(x)} keeps the shape of a two-dimensional {@code x}. */
	@Test(timeout = 60000)
	public void scalarMinusMatrixKeepsTheMatrixShape() {
		PackedCollection x = matrix();
		CollectionProducer result = c(1.0).subtract(cp(x));
		assertShape(shape(ROWS, COLUMNS), result.getShape());

		PackedCollection out = result.evaluate();
		assertShape(shape(ROWS, COLUMNS), out.getShape());
		for (int r = 0; r < ROWS; r++) {
			for (int col = 0; col < COLUMNS; col++) {
				assertEquals(1.0 - x.valueAt(r, col), out.valueAt(r, col));
			}
		}

		PackedCollection product = cp(x).multiply(c(1.0).subtract(cp(x))).evaluate();
		assertShape(shape(ROWS, COLUMNS), product.getShape());
		assertEquals(x.valueAt(2, 7) * (1.0 - x.valueAt(2, 7)), product.valueAt(2, 7));
	}

	/**
	 * The case that was reported: a selector built with {@code floor(...).repeat(1, n)},
	 * subtracted from one, multiplied by another producer of the same shape.
	 */
	@Test(timeout = 60000)
	public void complementOfRepeatedSelectorMultipliesWithMatrix() {
		PackedCollection values = new PackedCollection(shape(ROWS, 1));
		values.set(0, 0.2, 1.7, 2.9);
		PackedCollection x = matrix();

		CollectionProducer selector = floor(cp(values)).repeat(1, COLUMNS).reshape(ROWS, COLUMNS);
		CollectionProducer complement = c(1.0).subtract(selector);
		assertShape(shape(ROWS, COLUMNS), complement.getShape());

		PackedCollection out = cp(x).multiply(complement).evaluate();
		assertShape(shape(ROWS, COLUMNS), out.getShape());
		double[] floors = {0.0, 1.0, 2.0};
		for (int r = 0; r < ROWS; r++) {
			for (int col = 0; col < COLUMNS; col++) {
				assertEquals(x.valueAt(r, col) * (1.0 - floors[r]), out.valueAt(r, col));
			}
		}
	}

	/** The other binary operations keep the right operand's shape for a scalar on the left. */
	@Test(timeout = 60000)
	public void scalarOnTheLeftOfOtherOperationsKeepsTheMatrixShape() {
		TraversalPolicy expected = shape(ROWS, COLUMNS);
		PackedCollection x = matrix();

		assertShape(expected, c(2.0).add(cp(x)).getShape());
		assertShape(expected, c(2.0).multiply(cp(x)).getShape());
		assertShape(expected, c(2.0).divide(cp(x)).getShape());

		assertShape(expected, c(2.0).add(cp(x)).evaluate().getShape());
		assertShape(expected, cp(x).add(c(2.0)).evaluate().getShape());
		assertShape(expected, c(2.0).multiply(cp(x)).evaluate().getShape());

		PackedCollection quotient = c(2.0).divide(cp(x)).evaluate();
		assertShape(expected, quotient.getShape());
		assertEquals(2.0 / x.valueAt(1, 5), quotient.valueAt(1, 5));
	}

	/**
	 * {@code x.traverse(1).repeat(n)} of a one-dimensional bank combines with a
	 * {@code [channels, n]} producer the way {@code x.repeat(1, n)} does.
	 */
	@Test(timeout = 60000)
	public void traversedRepeatOfABankMultipliesWithMatrix() {
		PackedCollection a = new PackedCollection(shape(1));
		a.set(0, 2.0);
		PackedCollection b = new PackedCollection(shape(2));
		b.set(0, 3.0, 5.0);
		PackedCollection x = matrix();

		CollectionProducer bank = concat(cp(a), cp(b));
		assertShape(shape(ROWS), bank.getShape());

		PackedCollection viaRepeatAxis = bank.repeat(1, COLUMNS).reshape(ROWS, COLUMNS)
				.multiply(cp(x)).evaluate();
		PackedCollection viaTraverse = bank.traverse(1).repeat(COLUMNS).reshape(ROWS, COLUMNS)
				.multiply(cp(x)).evaluate();

		double[] gains = {2.0, 3.0, 5.0};
		for (int r = 0; r < ROWS; r++) {
			for (int col = 0; col < COLUMNS; col++) {
				assertEquals(gains[r] * x.valueAt(r, col), viaRepeatAxis.valueAt(r, col));
				assertEquals(gains[r] * x.valueAt(r, col), viaTraverse.valueAt(r, col));
			}
		}
	}
}
