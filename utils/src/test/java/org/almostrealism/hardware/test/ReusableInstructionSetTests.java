/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ReusableInstructionSetTests implements TestFeatures {

	@Test
	public void add() {
		int n = 6;

		PackedCollection<?> a = new PackedCollection<>(shape(n))
				.randFill().traverseEach();
		PackedCollection<?> b = new PackedCollection<>(shape(n))
				.randFill().traverseEach();

		Producer<PackedCollection<?>> sum = add(cp(a), cp(b));
		PackedCollection<?> out = sum.get().evaluate();

		for (int i = 0; i < n; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), out.toDouble(i));
		}

		PackedCollection<?> alt = new PackedCollection<>(shape(n))
				.randFill().traverseEach();

		sum = add(cp(alt), cp(b));
		out = sum.get().evaluate();

		for (int i = 0; i < n; i++) {
			assertEquals(alt.toDouble(i) + b.toDouble(i), out.toDouble(i));
		}
	}
}
