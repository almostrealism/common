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
}
