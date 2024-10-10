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

package org.almostrealism.algebra.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

public class ComplexMathTests implements TestFeatures {
	@Test
	public void complexFromPartsBatches1() {
		PackedCollection<?> values = new PackedCollection<>(10, 2, 1024).randFill();

		Producer<Pair<?>> c = cp(values).transpose(2);
		PackedCollection<?> out = c.evaluate();

		Assert.assertEquals(10, out.getShape().length(0));
		Assert.assertEquals(1024, out.getShape().length(1));
		Assert.assertEquals(2, out.getShape().length(2));

		out = out.reshape(10, 1024, 2);

		for (int i = 0; i < 10; i++) {
			for (int j = 0; j < 1024; j++) {
				for (int k = 0; k < 2; k++) {
					double expected = values.valueAt(i, k, j);
					double actual = out.valueAt(i, j, k);
					assertEquals(expected, actual);
				}
			}
		}
	}

	@Test
	public void complexFromPartsBatches2() {
		if (skipKnownIssues) return;

		PackedCollection<?> values = new PackedCollection<>(10, 2, 1024).fill(Math::random);

		Producer<Pair<?>> c = complexFromParts(
				subset(shape(10, 1, 1024), cp(values), 0, 0, 0),
				subset(shape(10, 1, 1024), cp(values), 0, 1, 0));
		PackedCollection<?> out = c.evaluate();

		Assert.assertEquals(10, out.getShape().length(0));
		Assert.assertEquals(1, out.getShape().length(1));
		Assert.assertEquals(1024, out.getShape().length(2));
		Assert.assertEquals(2, out.getShape().length(3));

		out = out.reshape(10, 1024, 2);

		for (int i = 0; i < 10; i++) {
			for (int j = 0; j < 1024; j++) {
				for (int k = 0; k < 2; k++) {
					double expected = values.valueAt(i, k, j);
					double actual = out.valueAt(i, j, k);
					log("ComplexMathTests[" + i + "][" + j + "][" + k + "] " + expected + " vs " + actual);
					assertEquals(expected, actual);
				}
			}
		}
	}

	@Test
	public void complexFromPartsMagnitude() {
		Evaluable<PackedCollection<?>> m =
				complexFromParts(
						v(shape(1024), 0),
						v(shape(1024), 1))
				.magnitude().get();

		PackedCollection<?> real = new PackedCollection<>(1024).fill(Math::random);
		PackedCollection<?> imag = new PackedCollection<>(1024).fill(Math::random);
		PackedCollection<?> out = new PackedCollection<>(1024, 1);

		verboseLog(() -> {
			m.into(out.traverseEach()).evaluate(real.traverseEach(), imag.traverseEach());

			for (int i = 0; i < 1024; i++) {
				double expected = Math.hypot(real.valueAt(i), imag.valueAt(i));
				double actual = out.valueAt(i, 0);
				assertEquals(expected, actual);
			}
		});
	}

	@Test
	public void multiply() {
		PackedCollection<Pair<?>> a = new PackedCollection<Pair<?>>(shape(32, 2)).randFill();
		PackedCollection<Pair<?>> b = new PackedCollection<Pair<?>>(shape(32, 2)).randFill();

		verboseLog(() -> {
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
		int w = 12;
		int h = 32;

		PackedCollection<Pair<?>> in = new PackedCollection<Pair<?>>(shape(w, h, 2)).randFill();
		PackedCollection<Pair<?>> x = new PackedCollection<Pair<?>>(shape(h, 2)).randFill();
		Producer<PackedCollection<Pair<?>>> o = multiplyComplex(traverse(1, p(in)), p(x));

		verboseLog(() -> {
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
