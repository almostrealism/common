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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.bool.LessThan;
import io.almostrealism.relation.Producer;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class AcceleratedConditionalStatementTests implements TestFeatures {

	@Test
	public void randomLessThanKernel() {
		PackedCollection<?> x = rand(shape(100, 2)).get().evaluate();
		PackedCollection<?> y = rand(shape(100, 2)).get().evaluate();

		PackedCollection<?> less = new PackedCollection<>(shape(100, 2), 1);
		lessThan().get().into(less).evaluate(x.traverse(1), y.traverse(1));

		Assert.assertEquals(100, less.getShape().length(0));
		Assert.assertEquals(2, less.getShape().length(1));

		IntStream.range(0, 100).forEach(i -> {
			double a = x.valueAt(i, 0);
			double b = y.valueAt(i, 0);
			double s = less.valueAt(i, 0);
			System.out.println("lessThan = " + s);

			if (a < b) {
				assertEquals(a, s);
			} else {
				assertEquals(b, s);
			}
		});
	}

	protected CollectionProducer<PackedCollection<?>> lessThan() {
		Producer<PackedCollection<?>> one = v(shape(-1, 1), 0);
		Producer<PackedCollection<?>> two = v(shape(-1, 1), 1);
		return lessThan(one, two, one, two, false);
	}

	@Test
	public void withPassThrough() {
		IntStream.range(0, 5)
				.mapToObj(i -> pack(Math.random()))
				.forEach(a -> {
					CollectionProducer lt = lessThan();
					check(lt, a, pack(Math.random()));
				});
	}

	@Test
	public void dotProduct() {
		Evaluable<PackedCollection<?>> lt = lessThan(
					oDotd(ray(i -> Math.random())),
					oDotd(v(Ray.shape(), 0)))
				.get();

		PackedCollection<?> r = lt.evaluate(ray(i -> Math.random()).evaluate());
		r.print();

		Assert.assertNotEquals(0.0, r.toDouble());
	}

	@Test
	public void crossProduct() {
		CollectionProducer<PackedCollection<?>> lt1 = lessThan(
				oDotd(ray(i -> Math.random())),
				oDotd(v(Ray.shape(), 0)));
		CollectionProducer<PackedCollection<?>> lt2 =
				lessThan(length(crossProduct(vector(i -> Math.random()), v(Vector.shape(), 1))),
														lt1, c(1), c(2), false);

		double v = lt2.get().evaluate(
				ray(i -> Math.random()).evaluate(),
				vector(i -> Math.random()).evaluate()).toDouble();
		log(v);
		assertTrue(v == 1.0 || v == 2.0);
	}

	private void check(CollectionProducer<PackedCollection<?>> lt,
					   PackedCollection<?> a, PackedCollection<?> b) {
		PackedCollection<?> s = lt.evaluate(a, b);
		s.print();

		if (a.toDouble() < b.toDouble()) {
			assertEquals(a, s);
		} else {
			assertEquals(b, s);
		}
	}

	@Test
	public void compactNested() {
		IntStream.range(1, 10).forEach(i -> {
			double a = i * Math.random();
			double b = i * Math.random();
			double c = i * Math.random();
			double d = i * Math.random();

			Producer<Scalar> pa = scalar(a);
			Producer<Scalar> pb = scalar(b);
			Producer<Scalar> pc = scalar(c);
			Producer<Scalar> pd = scalar(d);

			LessThan lt1 = new LessThanScalar(pa, pb, pa, pb, false);
			LessThan lt2 = new LessThanScalar(pb, pc, lt1, scalar(-a), false);
			LessThan lt3 = new LessThanScalar(pc, pd, lt2, scalar(-b), false);

			LessThan top = lt3;

			Scalar s = (Scalar) top.get().evaluate();
			System.out.println(s.getValue());

			if (c < d) {
				if (b < c) {
					if (a < b) {
						assertEquals(a, s.getValue());
					} else {
						assertEquals(b, s.getValue());
					}
				} else {
					assertEquals(-a, s.getValue());
				}
			} else {
				assertEquals(-b, s.getValue());
			}
		});
	}
}
