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

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class CollectionMathTests implements TestFeatures {
	@Test
	public void broadcastProduct() {
		PackedCollection<?> a = new PackedCollection<>(shape(10));
		a.fill(pos -> Math.random());

		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> result = cp(a).multiply(c(2.0)).get().evaluate();
			System.out.println(result.getShape().toStringDetail());
			Assert.assertEquals(1, result.getShape().getTraversalAxis());

			for (int i = 0; i < 10; i++) {
				assertEquals(a.valueAt(i) * 2.0, result.valueAt(i));
			}
		});
	}

	@Test
	public void traverseProduct() {
		PackedCollection<?> a = new PackedCollection<>(shape(10)).randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(10)).randFill();

		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> result = cp(a).multiply(cp(b.traverse(1))).get().evaluate();
			System.out.println(result.getShape().toStringDetail());
			Assert.assertEquals(1, result.getShape().getTraversalAxis());

			for (int i = 0; i < 10; i++) {
				assertEquals(a.valueAt(i) * b.valueAt(i), result.valueAt(i));
			}
		});
	}

	@Test
	public void repeatProduct() {
		PackedCollection<?> a = new PackedCollection<>(shape(2, 5)).randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(2)).randFill();

		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> result = cp(a).multiply(cp(b)).get().evaluate();
			System.out.println(result.getShape().toStringDetail());

			for (int i = 0; i < 2; i++) {
				for (int j = 0; j < 5; j++) {
					assertEquals(a.valueAt(i, j) * b.valueAt(i), result.valueAt(i, j));
				}
			}
		});
	}

	@Test
	public void sum() {
		int size = 768;

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());


		kernelTest(() -> c(p(x)).sum(),
				output -> {
					double expected = 0;

					for (int j = 0; j < size; j++) {
						expected += x.valueAt(j);
					}

					assertEquals(expected, output.valueAt(0));
				}, false, false, true);
	}

	@Test
	public void squares() {
		int size = 768;

		PackedCollection<?> o = new PackedCollection<>(shape(size));
		o.fill(pos -> Math.random());

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());

		PackedCollection<?> weight = new PackedCollection<>(shape(size));
		weight.fill(pos -> Math.random());


		kernelTest(() -> {
					CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(p(x)), c(2.0));
					return ss;
				},
				output -> {
					for (int j = 0; j < size; j++) {
						Assert.assertEquals(output.valueAt(j), x.valueAt(j) * x.valueAt(j), 1e-5);
					}
				}, false, false, true);
	}

	@Test
	public void sumOfSquares() {
		int size = 768;

		PackedCollection<?> o = new PackedCollection<>(shape(size));
		o.fill(pos -> Math.random());

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());

		PackedCollection<?> weight = new PackedCollection<>(shape(size));
		weight.fill(pos -> Math.random());


		kernelTest(() -> {
					CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(p(x)), c(2.0)).traverse(0).sum();
					ss = ss.divide(c(size)).add(c(1e-5));
					ss = c(1.0).divide(ss.pow(c(0.5)));
					return ss;
				},
				output -> {
					double ss = 0.0f;
					for (int j = 0; j < size; j++) {
						ss += x.valueAt(j) * x.valueAt(j);
					}
					ss /= size;
					ss += 1e-5f;
					ss = 1.0f / (float) Math.sqrt(ss);

					Assert.assertEquals(ss, output.valueAt(0), 1e-5);
				}, false, false, true);
	}

	@Test
	public void sumOfSquaresProduct() {
		int size = 768;

		PackedCollection<?> o = new PackedCollection<>(shape(size));
		o.fill(pos -> Math.random());

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());

		PackedCollection<?> weight = new PackedCollection<>(shape(size));
		weight.fill(pos -> Math.random());


		kernelTest(() -> {
					CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(p(x)), c(2.0)).traverse(0).sum();
					ss = ss.divide(c(size)).add(c(1e-5));
					ss = c(1.0).divide(ss.pow(c(0.5)));
					return multiply(traverseEach(p(weight)), traverseEach(p(x))).multiply(ss);
				},
				output -> {
					double ss = 0.0f;
					for (int j = 0; j < size; j++) {
						ss += x.valueAt(j) * x.valueAt(j);
					}
					ss /= size;
					ss += 1e-5f;
					ss = 1.0f / (float) Math.sqrt(ss);
					// normalize and scale
					for (int j = 0; j < size; j++) {
						Assert.assertEquals(weight.valueAt(j) * (ss * x.valueAt(j)), output.valueAt(j), 1e-5);
					}
				}, false, false, true);
	}

	@Test
	public void addInPlace() {
		int size = 10;

		PackedCollection<?> aOrig = new PackedCollection<>(shape(size));
		aOrig.fill(pos -> Math.random());

		PackedCollection<?> a = new PackedCollection<>(shape(size));
		a.fill(pos -> aOrig.valueAt(pos));

		PackedCollection<?> b = new PackedCollection<>(shape(size));
		b.fill(pos -> Math.random());

		Runnable op = (Runnable) a(traverseEach(p(a)), add(traverseEach(p(a)), traverseEach(p(b)))).optimize().get();
		op.run();

		for (int i = 0; i < size; i++) {
			Assert.assertEquals(aOrig.valueAt(i) + b.valueAt(i), a.valueAt(i), 1e-5);
		}
	}
}
