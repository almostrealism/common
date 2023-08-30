/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class StandardMathTests implements TestFeatures {
	@Test
	public void multiplyBroadcast() {
		PackedCollection<?> in = new PackedCollection<Pair<?>>(shape(12, 32, 2)).randFill();
		PackedCollection<?> x = new PackedCollection<Pair<?>>(shape(32, 2)).randFill();
		Producer<PackedCollection<?>> o = c(p(in)).traverse(1).multiply(c(p(x)));

		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> result = o.get().evaluate();

			for (int n = 0; n < 12; n++) {
				for (int i = 0; i < 32; i++) {
					for (int j = 0; j < 2; j++) {
						double expected = in.valueAt(n, i, j) * x.valueAt(i, j);
						double actual = result.valueAt(n, i, j);
						System.out.println("StandardMathTests[" + i + "] " + expected + " vs " + actual);
						assertEquals(expected, actual);
					}
				}
			}
		});
	}
}
