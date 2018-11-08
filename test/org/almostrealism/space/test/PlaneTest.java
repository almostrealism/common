/*
 * Copyright 2018 Michael Murray
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

import org.almostrealism.algebra.RayMatrixTransform;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayPointAt;
import org.almostrealism.space.Plane;
import org.almostrealism.space.ShadableIntersection;
import org.almostrealism.util.StaticProducer;
import org.junit.Assert;
import org.junit.Test;

public class PlaneTest {
	@Test
	public void intersectionTest() {
		StaticProducer<Ray> r = new StaticProducer<>(new Ray(new Vector(0.0, 0.0, 1.0),
															new Vector(0.0, 0.5, -1.0)));

		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, -10, 0.0));

		ShadableIntersection intersection = (ShadableIntersection) p.intersectAt(r);
		Assert.assertTrue(((Scalar) intersection.getDistance().evaluate(new Object[0])).getValue() == -20);

		Assert.assertTrue(intersection.get(0).evaluate(new Object[0]).equals(
								new Ray(new Vector(0.0, -10.0, 21.0),
										new Vector(0.0, 1.0, 0.0))));
	}

	@Test
	public void transformTest() {
		StaticProducer<Ray> r = new StaticProducer<>(new Ray(new Vector(0.0, 0.0, 1.0),
															new Vector(0.0, 0.5, -1.0)));


		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, -10, 0.0));

		RayMatrixTransform t = new RayMatrixTransform(p.getTransform(true), r);
		Assert.assertTrue(t.evaluate(new Object[0]).equals(new Ray(new Vector(0.0, -10.0, 1.0),
																new Vector(0.0, 0.5, -1.0))));

		t = new RayMatrixTransform(p.getTransform(true).getInverse(), r);
		Assert.assertTrue(t.evaluate(new Object[0]).equals(new Ray(new Vector(0.0, 10.0, 1.0),
																	new Vector(0.0, 0.5, -1.0))));

		Vector v = t.evaluate(new Object[0]).pointAt(new StaticProducer<>(new Scalar(-20))).evaluate(new Object[0]);
		Assert.assertTrue(v.equals(new Vector(0.0, 0.0, 21.0)));
	}
}