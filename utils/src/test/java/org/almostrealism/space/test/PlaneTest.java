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

package org.almostrealism.space.test;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.space.Plane;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

public class PlaneTest extends TestSuiteBase {
	protected ShadableIntersection test1() {
		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, -10, 0.0));

		return (ShadableIntersection) p.intersectAt(ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0));
	}

	// TODO @Test(timeout = 10000)
	public void intersectionTest1() {
		ShadableIntersection intersection = test1();
		double distance = intersection.getDistance().evaluate().toDouble();
		System.out.println("distance = " + distance);
		Assert.assertEquals(-20.0, distance, Math.pow(10, -10));

		Assert.assertEquals(intersection.get(0).get().evaluate(), ray(0.0, -10.0, 21.0, 0.0, 1.0, 0.0).get().evaluate());
	}

	// TODO  @Test(timeout = 10000)
	public void intersectionTest1Compact() {
		ShadableIntersection intersection = test1();

		Producer<PackedCollection> p = intersection.getDistance();

		double distance = p.get().evaluate().toDouble();
		System.out.println("distance = " + distance);
		Assert.assertEquals(-20.0, distance, Math.pow(10, -10));

		Assert.assertEquals(intersection.get(0).get().evaluate(), new Ray(new Vector(0.0, -10.0, 21.0),
				new Vector(0.0, 1.0, 0.0)));
	}

	@Test(timeout = 10000)
	public void intersectionTest2() {
		Producer<Ray> r = (Producer) ray(0.0, 1.0, 1.0, 0.0, 0.1, 1.0);

		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, 0, 0.0));

		ShadableIntersection intersection = (ShadableIntersection) p.intersectAt(r);
		Assert.assertTrue(intersection.getDistance().get().evaluate().toDouble() < 0);
	}

	// TODO @Test(timeout = 10000)
	public void transformTest() {
		Provider<Ray> r = new Provider<>(new Ray(new Vector(0.0, 0.0, 1.0),
				new Vector(0.0, 0.5, -1.0)));
		ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0);

		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, -10, 0.0));

		Producer<Ray> t = (Producer) transform(p.getTransform(true),
				ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0));
		Assert.assertEquals(new Ray(new Vector(0.0, -10.0, 1.0),
				new Vector(0.0, 0.5, -1.0)), t.get().evaluate());

		t = (Producer) transform(p.getTransform(true).getInverse(),
				ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0));
		Assert.assertEquals(new Ray(new Vector(0.0, 10.0, 1.0),
				new Vector(0.0, 0.5, -1.0)), t.get().evaluate());

		Vector v = new Vector(t.get().evaluate().pointAt(c(-20)).get().evaluate(), 0);
		Assert.assertEquals(new Vector(0.0, 0.0, 21.0), v);
	}
}
