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
import org.junit.Assert;
import org.junit.Test;

public class RayTest implements TestFeatures {
	@Test
	public void pointAtTest1() {
		CollectionProducer p = pointAt(ray(0.0, 0.0, 0.0, 0.0, 1.0, 0.5), c(10));
		assertEquals(new Vector(0.0, 10.0, 5.0), p.get().evaluate());
		assertEquals(new Vector(0.0, 10.0, 5.0), p.get().evaluate());
	}

	@Test
	public void pointAtTest2() {
		CollectionProducer at = pointAt(ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0), c(-20));
		assertEquals(new Vector(0.0, -10.0, 21.0), at.get().evaluate());
	}

	@Test
	public void dynamicPointAt() {
		Producer<PackedCollection> d = (Producer) func(shape(1), new AdaptEvaluable<>(c(-20).get())::evaluate);
		CollectionProducer at = pointAt(ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0), d);
		assertEquals(new Vector(0.0, -10.0, 21.0), at.get().evaluate());
	}

	@Test
	public void directions() {
		Producer<PackedCollection> directions = direction(v(shape(-1, 6), 0));

		PackedCollection rays = new PackedCollection(shape(3, 6).traverse(1));
		rays.set(0, new Ray(new Vector(1, 2, 3), new Vector(4, 5, 6)));
		rays.set(1, new Ray(new Vector(7, 8, 9), new Vector(10, 11, 12)));
		rays.set(2, new Ray(new Vector(13, 14, 15), new Vector(16, 17, 18)));

		PackedCollection d = new PackedCollection(shape(3, 3).traverse(1));

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
		Producer<Ray> comp = (Producer) value(new Ray(new Vector(1.0, 2.0, 3.0),
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

		assertEquals(1.0, r.getOrigin().toDouble(0));
		assertEquals(2.0, r.getOrigin().toDouble(1));
		assertEquals(3.0, r.getOrigin().toDouble(2));
		assertEquals(4.0, r.getDirection().toDouble(0));
		assertEquals(5.0, r.getDirection().toDouble(1));
		assertEquals(6.0, r.getDirection().toDouble(2));
	}

	@Test
	public void batchMultiply() {
		Producer<Ray> rays = v(shape(-1, 6), 0);

		// origin * direction element-wise
		Producer<?> product = multiply(origin(rays), direction(rays));

		PackedCollection rayData = new PackedCollection(shape(3, 6).traverse(1));
		rayData.set(0, new Ray(new Vector(1, 2, 3), new Vector(2, 3, 4)));      // (1,2,3) * (2,3,4) = (2,6,12)
		rayData.set(1, new Ray(new Vector(1, 1, 1), new Vector(1, 1, 1)));      // (1,1,1) * (1,1,1) = (1,1,1)
		rayData.set(2, new Ray(new Vector(0, 0, 0), new Vector(5, 5, 5)));      // (0,0,0) * (5,5,5) = (0,0,0)

		PackedCollection result = new PackedCollection(shape(3, 3).traverse(1));
		product.into(result.each()).evaluate(rayData);

		result.print();

		assertEquals(new Vector(2, 6, 12), result.get(0));
		assertEquals(new Vector(1, 1, 1), result.get(1));
		assertEquals(new Vector(0, 0, 0), result.get(2));
	}

	@Test
	public void batchDotProduct() {
		Producer<Ray> rays = v(shape(-1, 6), 0);

		// Dot product via multiply().sum()
		Producer<?> dotProd = multiply(origin(rays), direction(rays)).sum();

		PackedCollection rayData = new PackedCollection(shape(3, 6).traverse(1));
		rayData.set(0, new Ray(new Vector(0, 0, 3), new Vector(0, 0, -1)));     // 0*0 + 0*0 + 3*(-1) = -3
		rayData.set(1, new Ray(new Vector(1, 0, 0), new Vector(1, 0, 0)));      // 1*1 + 0*0 + 0*0 = 1
		rayData.set(2, new Ray(new Vector(1, 2, 3), new Vector(2, 3, 4)));      // 1*2 + 2*3 + 3*4 = 2+6+12 = 20

		PackedCollection result = new PackedCollection(shape(3, 1).traverse(1));
		dotProd.into(result.each()).evaluate(rayData);

		result.print();

		assertEquals(-3.0, result.valueAt(0, 0));
		assertEquals(1.0, result.valueAt(1, 0));
		assertEquals(20.0, result.valueAt(2, 0));
	}

	@Test
	public void batchODotD() {
		Producer<Ray> rays = v(shape(-1, 6), 0);
		Producer<?> oDotdProd = oDotd(rays);

		PackedCollection rayData = new PackedCollection(shape(3, 6).traverse(1));
		rayData.set(0, new Ray(new Vector(0, 0, 3), new Vector(0, 0, -1)));     // -3
		rayData.set(1, new Ray(new Vector(1, 0, 0), new Vector(1, 0, 0)));      // 1
		rayData.set(2, new Ray(new Vector(1, 2, 3), new Vector(2, 3, 4)));      // 20

		PackedCollection result = new PackedCollection(shape(3, 1).traverse(1));
		oDotdProd.into(result.each()).evaluate(rayData);

		result.print();

		assertEquals(-3.0, result.valueAt(0, 0));
		assertEquals(1.0, result.valueAt(1, 0));
		assertEquals(20.0, result.valueAt(2, 0));
	}

	@Test
	public void batchDDotD() {
		Producer<Ray> rays = v(shape(-1, 6), 0);
		Producer<?> dDotdProd = dDotd(rays);

		PackedCollection rayData = new PackedCollection(shape(3, 6).traverse(1));
		rayData.set(0, new Ray(new Vector(1, 2, 3), new Vector(0, 0, -1)));     // 0+0+1 = 1
		rayData.set(1, new Ray(new Vector(0, 0, 0), new Vector(2, 2, 2)));      // 4+4+4 = 12
		rayData.set(2, new Ray(new Vector(1, 2, 3), new Vector(3, 4, 5)));      // 9+16+25 = 50

		PackedCollection result = new PackedCollection(shape(3, 1).traverse(1));
		dDotdProd.into(result.each()).evaluate(rayData);

		result.print();

		assertEquals(1.0, result.valueAt(0, 0));
		assertEquals(12.0, result.valueAt(1, 0));
		assertEquals(50.0, result.valueAt(2, 0));
	}

	@Test
	public void batchODotO() {
		Producer<Ray> rays = v(shape(-1, 6), 0);
		Producer<?> oDotoProd = oDoto(rays);

		PackedCollection rayData = new PackedCollection(shape(3, 6).traverse(1));
		rayData.set(0, new Ray(new Vector(0, 0, 3), new Vector(1, 1, 1)));      // 0+0+9 = 9
		rayData.set(1, new Ray(new Vector(1, 0, 0), new Vector(2, 2, 2)));      // 1+0+0 = 1
		rayData.set(2, new Ray(new Vector(1, 2, 3), new Vector(0, 0, 0)));      // 1+4+9 = 14

		PackedCollection result = new PackedCollection(shape(3, 1).traverse(1));
		oDotoProd.into(result.each()).evaluate(rayData);

		result.print();

		assertEquals(9.0, result.valueAt(0, 0));
		assertEquals(1.0, result.valueAt(1, 0));
		assertEquals(14.0, result.valueAt(2, 0));
	}

	@Test
	public void rayDotProductsSingleRay() {
		// Test that dot products work for a single ray in batch mode
		Producer<Ray> ray = v(shape(-1, 6), 0);

		PackedCollection singleRay = new PackedCollection(shape(1, 6).traverse(1));
		singleRay.setMem(0, 0, 0, 3, 0, 0, -1); // origin (0,0,3), direction (0,0,-1)

		// Test oDoto (origin dot origin) = 0^2 + 0^2 + 3^2 = 9
		PackedCollection oDotoResult = new PackedCollection(shape(1, 1).traverse(1));
		oDoto(ray).get().into(oDotoResult.each()).evaluate(singleRay);
		System.out.println("oDoto: " + oDotoResult.valueAt(0, 0) + " (expected 9.0)");
		Assert.assertEquals(9.0, oDotoResult.valueAt(0, 0), 0.01);

		// Test dDotd (direction dot direction) = 0^2 + 0^2 + (-1)^2 = 1
		PackedCollection dDotdResult = new PackedCollection(shape(1, 1).traverse(1));
		dDotd(ray).get().into(dDotdResult.each()).evaluate(singleRay);
		System.out.println("dDotd: " + dDotdResult.valueAt(0, 0) + " (expected 1.0)");
		Assert.assertEquals(1.0, dDotdResult.valueAt(0, 0), 0.01);

		// Test oDotd (origin dot direction) = 0*0 + 0*0 + 3*(-1) = -3
		PackedCollection oDotdResult = new PackedCollection(shape(1, 1).traverse(1));
		oDotd(ray).get().into(oDotdResult.each()).evaluate(singleRay);
		System.out.println("oDotd: " + oDotdResult.valueAt(0, 0) + " (expected -3.0)");
		Assert.assertEquals(-3.0, oDotdResult.valueAt(0, 0), 0.01);
	}
}
