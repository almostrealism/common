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
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ChoiceTest implements TestFeatures {
	@Test
	public void oneOrTwo() {
		PackedCollection bank = new PackedCollection(shape(2, 2));
		bank.setMem(0, 1.0, 1.0);
		bank.setMem(2, 2.0, 1.0);

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
}
