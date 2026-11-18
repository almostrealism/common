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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for conditional computations.
 */
public class ConditionalTest extends TestSuiteBase {

	/**
	 * Tests conditional selection with positive comparison.
	 */
	@Test(timeout = 10000)
	public void positive() {
		CollectionProducerBase a = c(2);
		CollectionProducerBase b = c(2);
		CollectionProducerBase c = c(3);
		CollectionProducerBase d = c(5);
		Evaluable<PackedCollection> ev = equals(a, b, c, d).get();

		PackedCollection result = ev.evaluate();
		log(String.valueOf(result.toDouble(0)));
		assertEquals(3, result.toDouble(0));
	}

	/**
	 * Tests conditional selection with negative comparison.
	 */
	@Test(timeout = 10000)
	public void negative() {
		CollectionProducerBase a = c(2);
		CollectionProducerBase b = c(1);
		CollectionProducerBase c = c(3);
		CollectionProducerBase d = c(5);
		Evaluable<PackedCollection> ev = equals(a, b, c, d).get();

		PackedCollection result = ev.evaluate();
		log(String.valueOf(result.toDouble(0)));
		assertEquals(5, result.toDouble(0));
	}

	/**
	 * A single-branch selection returns the position {@code 0} value directly,
	 * with no conditional wrapping, as documented by {@link Conditional#select}.
	 */
	@Test(timeout = 10000)
	public void selectSingleBranch() {
		Expression<?> selected = Conditional.select(new IntegerConstant(0), 1,
				j -> new IntegerConstant(10 + j));
		assertEquals(10.0, selected.doubleValue().getAsDouble());
	}

	/**
	 * A non-positive {@code count} is rejected before the value supplier is invoked,
	 * rather than silently producing the out-of-range position {@code 0} value.
	 */
	@Test(timeout = 10000)
	public void selectRejectsNonPositiveCount() {
		boolean[] supplierCalled = { false };

		try {
			Conditional.select(new IntegerConstant(0), 0, j -> {
				supplierCalled[0] = true;
				return new IntegerConstant(j);
			});
			throw new AssertionError("Expected IllegalArgumentException for count 0");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		assertFalse(supplierCalled[0]);
	}

	/**
	 * Pins the batched broadcast of a per-row scalar condition over vector-valued branches for the
	 * equality-based selection. The condition operand is one value per row (a variable-count
	 * {@code (-1, 1)} input) in the right position, while the branches are a three-element vector
	 * per row that varies by row. Each row's single condition governs all three of that row's
	 * output channels, exercising the comparison-operand index projection on the right operand.
	 */
	@Test(timeout = 10000)
	public void equalsBroadcastsPerRowConditionOverVectorBranches() {
		Producer<PackedCollection> cond = v(shape(-1, 1), 0);
		Producer<PackedCollection> trueBranch = v(shape(-1, 3), 1);
		Producer<PackedCollection> falseBranch = v(shape(-1, 3), 2);

		PackedCollection condition = new PackedCollection(2, 1).traverse(1);
		condition.set(0, 4.0);   // 4 == 4 selects the true branch
		condition.set(1, 7.0);   // 7 == 4 is false, selects the false branch

		PackedCollection trueValues = pack(10, 11, 12, 13, 14, 15).reshape(2, 3).traverse(1);
		PackedCollection falseValues = pack(20, 21, 22, 23, 24, 25).reshape(2, 3).traverse(1);
		PackedCollection result = new PackedCollection(2, 3);

		equals(c(4.0), cond, trueBranch, falseBranch).get().into(result.each())
				.evaluate(condition, trueValues, falseValues);

		assertEquals(10.0, result.toDouble(0));
		assertEquals(11.0, result.toDouble(1));
		assertEquals(12.0, result.toDouble(2));
		assertEquals(23.0, result.toDouble(3));
		assertEquals(24.0, result.toDouble(4));
		assertEquals(25.0, result.toDouble(5));
	}

	/**
	 * Pins the batched broadcast of a per-row scalar condition over vector-valued branches for the
	 * logical-and selection. The left operand is one boolean value per row (a variable-count
	 * {@code (-1, 1)} input) and the branches are a three-element vector per row that varies by
	 * row, so each row's condition governs all three of that row's output channels.
	 */
	@Test(timeout = 10000)
	public void andBroadcastsPerRowConditionOverVectorBranches() {
		Producer<PackedCollection> a = v(shape(-1, 1), 0);
		Producer<PackedCollection> trueBranch = v(shape(-1, 3), 1);
		Producer<PackedCollection> falseBranch = v(shape(-1, 3), 2);

		PackedCollection aVals = new PackedCollection(2, 1).traverse(1);
		aVals.set(0, 1.0);   // 1 AND 1 selects the true branch
		aVals.set(1, 0.0);   // 0 AND 1 is false, selects the false branch

		PackedCollection trueValues = pack(10, 11, 12, 13, 14, 15).reshape(2, 3).traverse(1);
		PackedCollection falseValues = pack(20, 21, 22, 23, 24, 25).reshape(2, 3).traverse(1);
		PackedCollection result = new PackedCollection(2, 3);

		and(a, c(1.0), trueBranch, falseBranch).get().into(result.each())
				.evaluate(aVals, trueValues, falseValues);

		assertEquals(10.0, result.toDouble(0));
		assertEquals(11.0, result.toDouble(1));
		assertEquals(12.0, result.toDouble(2));
		assertEquals(23.0, result.toDouble(3));
		assertEquals(24.0, result.toDouble(4));
		assertEquals(25.0, result.toDouble(5));
	}
}
