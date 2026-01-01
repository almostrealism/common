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

package org.almostrealism.math.bool.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class AcceleratedConjunctionTests extends TestSuiteBase {
	protected CollectionProducer conjunction(
			Producer<PackedCollection> a, Producer<PackedCollection> b,
			Producer<PackedCollection> c, Producer<PackedCollection> d) {
		CollectionProducer l1 = lessThan(a, b);
		CollectionProducer l2 = lessThan(c, d);
		return and(l1, l2, a, b);
	}

	protected Runnable conjunctionTest(double a, double b, double c, double d) {
		Evaluable<PackedCollection> s = conjunction(
				c(a), v(shape(1), 0),
				c(c), v(shape(1), 1)).get();

		return () -> {
			double t = s.evaluate(pack(b), pack(d)).toDouble();

			if (a < b && c < d) {
				assertEquals(a, t);
			} else {
				assertEquals(b, t);
			}
		};
	}

	@Test(timeout = 10000)
	public void conjunctions() {
		IntStream.range(0, 10).mapToObj(i ->
						conjunctionTest(i * Math.random(), i * Math.random(), i * Math.random(), i * Math.random()))
				.forEach(Runnable::run);
	}

	protected CollectionProducer dotProductConjunction(Producer<Ray> r) {
		return conjunction(oDotd(
						ray(i -> Math.random())), c(1),
				oDotd(v(Ray.shape(), 0)), c(1));
	}

	@Test(timeout = 10000)
	public void dotProductInConjunction() {
		CollectionProducer c = dotProductConjunction(v(Ray.shape(), 0));

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate()).toDouble();
		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test(timeout = 10000)
	public void dotProductInNestedConjunction1() {
		CollectionProducer c = dotProductConjunction((Producer) ray(i -> Math.random()));
		c = conjunction(c, v(shape(1), 0), c(Math.random()), c(Math.random()));

		double v = c.evaluate(scalar(Math.random()).get().evaluate()).toDouble();
		log(v);
		Assert.assertNotEquals(0, v);
	}

	@Test(timeout = 10000)
	public void dotProductInNestedConjunction2() {
		CollectionProducer c = dotProductConjunction((Producer) ray(i -> Math.random()));
		c = conjunction(c, c(Math.random()), v(shape(1), 0), c(Math.random()));

		double v = c.evaluate(c(Math.random()).get().evaluate()).toDouble();
		log(v);
		Assert.assertNotEquals(0, v);
	}

	@Test(timeout = 10000)
	public void dotProductInNestedConjunction3() {
		CollectionProducer c = dotProductConjunction(v(Ray.shape(), 0));
		c = conjunction(c, c(Math.random()), c(Math.random()), c(Math.random()));

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate()).toDouble();
		log(v);
		Assert.assertNotEquals(0, v);
	}

	@Test(timeout = 10000)
	public void dotProductInNestedConjunction4() {
		CollectionProducer c = dotProductConjunction(v(Ray.shape(), 0));
		c = conjunction(c, v(shape(1), 1),
				c(Math.random()), v(shape(1), 2));

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate(),
				c(Math.random()).get().evaluate(),
				c(Math.random()).get().evaluate()).toDouble();
		log(v);
		Assert.assertNotEquals(0, v);
	}
}
