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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.Choice;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for {@link Choice} computation.
 */
public class ChoiceTest extends TestSuiteBase {

	/**
	 * Tests the Choice computation with valid inputs.
	 */
	@Test(timeout = 10000)
	public void oneOrTwo() {
		PackedCollection bank = pack(1.0, 1.0, 2.0, 1.0).reshape(shape(2, 2)).traverse(1);

		bank.print();

		verboseLog(() -> {
			Choice choice = new Choice(shape(2), 2, c(0.7), cp(bank));
			Evaluable<PackedCollection> ev = choice.get();
			PackedCollection result = ev.evaluate();
			result.print();

			assertEquals(2.0, result.toDouble(0));
			assertEquals(1.0, result.toDouble(1));
		});
	}

	/**
	 * Evaluates a {@link Choice} through the optimizing {@code evaluate()}, which rebuilds the
	 * computation from its optimized children via {@link Choice#generate}. With three options
	 * and a decision of 0.7 the third option is selected, so the result depends on the rebuilt
	 * computation keeping both the choice count and the order of the decision and choices.
	 */
	@Test(timeout = 10000)
	public void oneOfThreeOptimized() {
		try (PackedCollection bank = pack(1.0, 1.0, 2.0, 1.0, 3.0, 4.0).reshape(shape(3, 2)).traverse(1);
			 PackedCollection result = new Choice(shape(2), 3, c(0.35).multiply(2.0), cp(bank)).evaluate()) {
			assertEquals(3.0, result.toDouble(0));
			assertEquals(4.0, result.toDouble(1));
		}
	}
}
