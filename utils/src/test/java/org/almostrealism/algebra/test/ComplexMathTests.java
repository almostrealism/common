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
import org.almostrealism.util.TestUtils;
import org.junit.Test;

public class ComplexMathTests implements TestFeatures {
	@Test
	public void multiply() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		PackedCollection<Pair<?>> a = new PackedCollection<Pair<?>>(shape(32, 2)).randFill();
		PackedCollection<Pair<?>> b = new PackedCollection<Pair<?>>(shape(32, 2)).randFill();

		HardwareOperator.verboseLog(() -> {
			PackedCollection<Pair<?>> result = multiplyComplex(traverse(1, p(a)), traverse(1, p(b))).get().evaluate();

			for (int i = 0; i < 32; i++) {
				double expected = multiplyComplexL(
						a.valueAt(i, 0), a.valueAt(i, 1),
						b.valueAt(i, 0), b.valueAt(i, 1));
				double actual = result.valueAt(i, 0);
				System.out.println("ComplexMathTests[" + i + "] " + expected + " vs " + actual);
				assertEquals(expected, actual);

				expected = multiplyComplexR(
						a.valueAt(i, 0), a.valueAt(i, 1),
						b.valueAt(i, 0), b.valueAt(i, 1));
				actual = result.valueAt(i, 1);
				System.out.println("ComplexMathTests[" + i + "] " + expected + " vs " + actual);
				assertEquals(expected, actual);
			}
		});
	}

	@Test
	public void broadcastMultiply() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int w = 12;
		int h = 32;

		PackedCollection<Pair<?>> in = new PackedCollection<Pair<?>>(shape(w, h, 2)).randFill();
		PackedCollection<Pair<?>> x = new PackedCollection<Pair<?>>(shape(h, 2)).randFill();
		Producer<PackedCollection<Pair<?>>> o = multiplyComplex(traverse(1, p(in)), p(x));

		HardwareOperator.verboseLog(() -> {
			PackedCollection<Pair<?>> result = o.get().evaluate();

			for (int n = 0; n < w; n++) {
				for (int i = 0; i < h; i++) {
					double expected = multiplyComplexL(
							in.valueAt(n, i, 0), in.valueAt(n, i, 1),
							x.valueAt(i, 0), x.valueAt(i, 1));
					double actual = result.valueAt(n, i, 0);
					System.out.println("ComplexMathTests[" + i + "] " + expected + " vs " + actual);
					assertEquals(expected, actual);

					expected = multiplyComplexR(
							in.valueAt(n, i, 0), in.valueAt(n, i, 1),
							x.valueAt(i, 0), x.valueAt(i, 1));
					actual = result.valueAt(n, i, 1);
					System.out.println("ComplexMathTests[" + i + "] " + expected + " vs " + actual);
					assertEquals(expected, actual);
				}
			}
		});
	}

	protected double multiplyComplexL(double p, double q, double r, double s) {
		return p * r - q * s;
	}

	protected double multiplyComplexR(double p, double q, double r, double s) {
		return p * s + q * r;
	}
}
