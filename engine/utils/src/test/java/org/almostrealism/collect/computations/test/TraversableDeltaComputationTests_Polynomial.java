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

package org.almostrealism.collect.computations.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.GradientTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Tests for traversable delta computations - polynomial and power operations.
 * This is a companion file to {@link TraversableDeltaComputationTests}.
 */
public class TraversableDeltaComputationTests_Polynomial extends TestSuiteBase implements GradientTestFeatures {

	/**
	 * Tests polynomial x + 1 gradient.
	 */
	@Test(timeout = 60000)
	public void polynomial0() {
		CollectionProducer c = x().add(1);

		Evaluable<PackedCollection> dy = c.delta(x()).get();
		PackedCollection out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.print();

		for (int i = 0; i < 5; i++) {
			assertEquals(1.0, out.toDouble(i));
		}
	}

	/**
	 * Tests polynomial x^2 + 3x + 1 gradient.
	 */
	@Test(timeout = 60000)
	public void polynomial1() {
		CollectionProducer c = x().sq().add(x().mul(3)).add(1);

		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.print();

		for (int i = 0; i < 5; i++) {
			assertEquals(1.0 + 3 * (i + 1) + (i + 1) * (i + 1), out.toDouble(i));
		}

		Evaluable<PackedCollection> dy = c.delta(x()).get();
		out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.print();

		for (int i = 0; i < 5; i++) {
			assertEquals(2 * (i + 1) + 3, out.toDouble(i));
		}
	}

	/**
	 * Tests polynomial 2D gradient with weight vector.
	 */
	@Test(timeout = 60000)
	public void polynomial2() {
		int dim = 3;
		int count = 2;

		PackedCollection v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection w = pack(4, -3, 2);
		CollectionProducer x = x(-1, dim);

		CollectionProducer c = x.mul(p(w));

		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate(v);
		log(Arrays.toString(out.toArray(0, count * dim)));

		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate(v);
		double[] d = dout.toArray(0, count * dim * dim);
		log(Arrays.toString(d));

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w.toDouble(j), d[i * dim * dim + j * dim + k]);
					} else {
						assertEquals(0.0, d[i * dim * dim + j * dim + k]);
					}
				}
			}
		}
	}

	/**
	 * Tests polynomial with constant term gradient.
	 */
	@Test(timeout = 60000)
	public void polynomial3() {
		int dim = 3;
		int count = 2;

		PackedCollection v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection w = pack(4, -3, 2);
		CollectionProducer x = x(-1, dim);

		CollectionProducer c = x.mul(p(w)).add(c(1).repeat(3).consolidate());

		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate(v);
		double[] l = out.toArray(0, count * dim);
		log(Arrays.toString(l));
		assertEquals(1.0, l[0]);
		assertEquals(-2.0, l[1]);

		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate(v);
		double[] d = dout.toArray(0, count * dim * dim);
		log(Arrays.toString(d));

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w.toDouble(j), d[i * dim * dim + j * dim + k]);
					} else {
						assertEquals(0.0, d[i * dim * dim + j * dim + k]);
					}
				}
			}
		}
	}

	/**
	 * Tests x^2 + w*x + 1 gradient.
	 */
	@Test(timeout = 60000)
	public void polynomial4() {
		int dim = 3;

		PackedCollection v = pack(IntStream.range(0, 4 * dim).boxed()
										.mapToDouble(Double::valueOf).toArray())
										.reshape(4, dim).traverse();
		PackedCollection w = pack(4, -3, 2);
		CollectionProducer x = x(-1, dim);

		CollectionProducer c = x.sq().add(x.mul(p(w))).add(c(1).repeat(3).consolidate());

		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate(v);
		out.print();


		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate(v);
		dout.print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(2 * v.valueAt(i, j) + w.valueAt(j), dout.valueAt(i, j, k));
					} else {
						assertEquals(0.0, dout.valueAt(i, j, k));
					}
				}
			}
		}
	}

	/**
	 * Tests x^2 - w*x + 1 gradient.
	 */
	@Test(timeout = 60000)
	public void polynomial5() {
		int dim = 3;

		PackedCollection v = pack(IntStream.range(0, 4 * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(4, dim).traverse();
		PackedCollection w = pack(4, -3, 2);
		CollectionProducer x = x(-1, dim);

		CollectionProducer c = x.sq().add(x.minus().mul(p(w))).add(c(1).repeat(3).consolidate());

		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate(v);
		log(Arrays.toString(out.toArray(0, 4 * dim)));

		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate(v);
		dout.print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(2 * v.valueAt(i, j) - w.valueAt(j), dout.valueAt(i, j, k));
					} else {
						assertEquals(0.0, dout.valueAt(i, j, k));
					}
				}
			}
		}
	}

	/**
	 * Tests x^2 - w*x + 1 gradient with scalar input.
	 */
	@Test(timeout = 60000)
	public void polynomial6() {
		int dim = 3;

		PackedCollection v = pack(IntStream.range(0, dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(dim).traverse();
		PackedCollection w = pack(4, -3, 2);
		CollectionProducer x = cp(v);

		CollectionProducer c = x.sq().add(x.minus().mul(p(w))).add(c(1).repeat(3).consolidate());
		log(String.valueOf(c.describe()));

		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate();
		out.print();

		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate();
		dout.print();

		for (int j = 0; j < dim; j++) {
			for (int k = 0; k < dim; k++) {
				if (j == k) {
					assertEquals(2 * v.valueAt(j) - w.valueAt(j), dout.valueAt(j, k));
				} else {
					assertEquals(0.0, dout.valueAt(j, k));
				}
			}
		}
	}

	/**
	 * Tests fixed power gradient x^3 + w^x + 1.
	 */
	@Test(timeout = 60000)
	public void powerFixed() {
		int dim = 3;

		PackedCollection v = pack(IntStream.range(0, dim).boxed()
				.mapToDouble(d -> 1 + d / 2.0).toArray())
				.reshape(dim);
		PackedCollection w = pack(4, 1, 2);
		CollectionProducer x = cp(v);

		CollectionProducer c = x.pow(3).add(cp(w).pow(x)).add(c(1).repeat(3).consolidate());

		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate();
		dout.print();

		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				if (i == j) {
					assertEquals(3 * Math.pow(v.valueAt(i), 2) +
									Math.pow(w.valueAt(i), v.valueAt(i)) * Math.log(w.valueAt(i)),
							dout.valueAt(i, j));
				} else {
					assertEquals(0.0, dout.valueAt(i, j));
				}
			}
		}
	}

	/**
	 * Tests power function gradient with generator.
	 */
	@Test(timeout = 60000)
	public void power1() {
		int dim = 3;

		IntFunction<PackedCollection> inputGenerator = count ->
				pack(IntStream.range(0, count * dim).boxed()
						.mapToDouble(d -> 1 + d / 2.0).toArray())
						.reshape(count, dim).traverse();

		Factor<PackedCollection> f = x -> {
			CollectionProducer c = c(x).pow(3);
			return c.delta(x);
		};

		runTest("power1", dim, inputGenerator, f, (in, out) -> {
			out.traverse(2).print();

			for (int n = 0; n < in.getCount(); n++) {
				for (int i = 0; i < dim; i++) {
					for (int j = 0; j < dim; j++) {
						if (i == j) {
							assertEquals(3 * Math.pow(in.valueAt(n, i), 2), out.valueAt(n, i, j));
						} else {
							assertEquals(0.0, out.valueAt(n, i, j));
						}
					}
				}
			}
		}, true, false);
	}

	/**
	 * Tests power function with and without delta.
	 */
	@Test(timeout = 60000)
	public void power2() {
		int dim = 3;

		IntFunction<PackedCollection> inputGenerator = count ->
				pack(IntStream.range(0, count * dim).boxed()
						.mapToDouble(d -> 1 + d / 2.0).toArray())
						.reshape(count, dim).traverse();

		PackedCollection w = pack(4, 1, 2);

		Factor<PackedCollection> f = x -> {
			CollectionProducer c =
					c(x).pow(3).add(cp(w).pow(x)).add(c(1).repeat(3).consolidate());
			return c;
		};

		runTest("power2", dim, inputGenerator, f, (in, out) -> {
			out.print();

			for (int n = 0; n < in.getCount(); n++) {
				for (int i = 0; i < dim; i++) {
					assertEquals(Math.pow(in.valueAt(n, i), 3) +
									Math.pow(w.valueAt(i), in.valueAt(n, i)) + 1,
							out.toDouble(n * dim + i));
				}
			}
		});
	}
}
