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

package org.almostrealism.geometry.test;

import io.almostrealism.code.AdaptEvaluable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class RayTest implements TestFeatures {
	@Test
	public void pointAtTest1() {
		CollectionProducer<Vector> p = pointAt(ray(0.0, 0.0, 0.0, 0.0, 1.0, 0.5), c(10));
		assertEquals(new Vector(0.0, 10.0, 5.0), p.get().evaluate());
		assertEquals(new Vector(0.0, 10.0, 5.0), p.get().evaluate());
	}

	@Test
	public void pointAtTest2() {
		CollectionProducer<Vector> at = pointAt(ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0), c(-20));
		assertEquals(new Vector(0.0, -10.0, 21.0), at.get().evaluate());
	}

	@Test
	public void dynamicPointAt() {
		Producer<PackedCollection<?>> d = func(shape(1), new AdaptEvaluable<>(c(-20).get())::evaluate);
		CollectionProducer<Vector> at = pointAt(ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0), (Producer) d);
		assertEquals(new Vector(0.0, -10.0, 21.0), at.get().evaluate());
	}

	@Test
	public void directions() {
		Producer<Vector> directions = direction(v(shape(-1, 6), 0));

		PackedCollection<Ray> rays = new PackedCollection<>(shape(3, 6).traverse(1));
		rays.set(0, new Ray(new Vector(1, 2, 3), new Vector(4, 5, 6)));
		rays.set(1, new Ray(new Vector(7, 8, 9), new Vector(10, 11, 12)));
		rays.set(2, new Ray(new Vector(13, 14, 15), new Vector(16, 17, 18)));

		PackedCollection<?> d = new PackedCollection<>(shape(3, 3).traverse(1));

		directions.into(d.each()).evaluate(rays);
		d.print();

		assertEquals(new Vector(4, 5, 6), d.get(0));
		assertEquals(new Vector(10, 11, 12), d.get(1));
		assertEquals(new Vector(16, 17, 18), d.get(2));
	}

	@Test
	public void dotProductTests() {
		Producer<Ray> r = v(Ray.shape(), 0);

		assertEquals(1 + 4 + 9, oDoto(r).get().evaluate(new Ray(
				new Vector(1, 2, 3),
				new Vector(7, 4, 2))));
		assertEquals(49 + 16 + 4, dDotd(r).get().evaluate(new Ray(
				new Vector(1, 2, 3),
				new Vector(7, 4, 2))));
		assertEquals(7 + 8 + 6, oDotd(r).get().evaluate(new Ray(
				new Vector(1, 2, 3),
				new Vector(7, 4, 2))));
	}

	@Test
	public void staticComputation() {
		Producer<Ray> comp = value(new Ray(new Vector(1.0, 2.0, 3.0),
															new Vector(4.0, 5.0, 6.0)));
		Evaluable<Ray> ev = comp.get();

		Ray r = ev.evaluate();
		System.out.println(r);

		double d[] = r.toArray();
		assertEquals(1.0, d[0]);
		assertEquals(2.0, d[1]);
		assertEquals(3.0, d[2]);
		assertEquals(4.0, d[3]);
		assertEquals(5.0, d[4]);
		assertEquals(6.0, d[5]);

		assertEquals(1.0, r.getOrigin().getX());
		assertEquals(2.0, r.getOrigin().getY());
		assertEquals(3.0, r.getOrigin().getZ());
		assertEquals(4.0, r.getDirection().getX());
		assertEquals(5.0, r.getDirection().getY());
		assertEquals(6.0, r.getDirection().getZ());
	}
}
