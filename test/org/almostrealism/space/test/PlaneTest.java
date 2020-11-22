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

import org.almostrealism.algebra.computations.RayMatrixTransform;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.DynamicAcceleratedMultiProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.space.Plane;
import org.almostrealism.space.ShadableIntersection;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.Evaluable;
import org.almostrealism.util.Provider;
import org.junit.Assert;
import org.junit.Test;

public class PlaneTest implements HardwareFeatures, CodeFeatures {
	protected ShadableIntersection test1() {
		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, -10, 0.0));

		return (ShadableIntersection) p.intersectAt((Evaluable<Ray>) ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0).get());
	}

	@Test
	public void intersectionTest1() {
		ShadableIntersection intersection = test1();
		double distance = ((Evaluable<Scalar>) intersection.getDistance().get()).evaluate().getValue();
		System.out.println("distance = " + distance);
		Assert.assertEquals(-20.0, distance, Math.pow(10, -10));

		Assert.assertTrue(intersection.get(0).evaluate().equals(
								ray(0.0, -10.0, 21.0, 0.0, 1.0, 0.0).get()));
	}

	@Test
	public void intersectionTest1Compact() {
		ShadableIntersection intersection = test1();

		Evaluable<Scalar> p = (Evaluable<Scalar>) intersection.getDistance();
		p.compact();

		System.out.println(((DynamicAcceleratedMultiProducer) p).getFunctionDefinition());

		double distance = p.evaluate().getValue();
		System.out.println("distance = " + distance);
		Assert.assertEquals(-20.0, distance, Math.pow(10, -10));

		Assert.assertTrue(intersection.get(0).evaluate().equals(
				new Ray(new Vector(0.0, -10.0, 21.0),
						new Vector(0.0, 1.0, 0.0))));
	}

	@Test
	public void intersectionTest2() {
		Provider<Ray> r = new Provider<>(new Ray(new Vector(0.0, 1.0, 1.0),
														new Vector(0.0, 0.0, -1.0)));

		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, 0, 0.0));

		ShadableIntersection intersection = (ShadableIntersection) p.intersectAt(r);
		Assert.assertEquals(-1.0, ((Evaluable<Scalar>) intersection.getDistance()).evaluate().getValue(), Math.pow(10, -10));
	}

	@Test
	public void transformTest() {
		Provider<Ray> r = new Provider<>(new Ray(new Vector(0.0, 0.0, 1.0),
															new Vector(0.0, 0.5, -1.0)));
		ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0);

		Plane p = new Plane(Plane.XZ);
		p.setLocation(new Vector(0.0, -10, 0.0));

		RayMatrixTransform t = new RayMatrixTransform(p.getTransform(true),
					ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0));
		Assert.assertTrue(compileProducer(t).evaluate().equals(new Ray(new Vector(0.0, -10.0, 1.0),
																new Vector(0.0, 0.5, -1.0))));

		t = new RayMatrixTransform(p.getTransform(true).getInverse(),
					ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0));
		Assert.assertTrue(compileProducer(t).evaluate().equals(new Ray(new Vector(0.0, 10.0, 1.0),
																	new Vector(0.0, 0.5, -1.0))));

		Vector v = compileProducer(t).evaluate().pointAt(new Provider<>(new Scalar(-20))).evaluate(new Object[0]);
		Assert.assertTrue(v.equals(new Vector(0.0, 0.0, 21.0)));
	}
}
