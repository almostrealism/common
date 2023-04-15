/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RayCopy;
import org.almostrealism.geometry.computations.StaticRayComputation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class RayTest implements TestFeatures {
	@Test
	public void pointAtTest1() {
		VectorProducerBase p = pointAt(ray(0.0, 0.0, 0.0, 0.0, 1.0, 0.5), scalar(10));
		Assert.assertEquals(p.get().evaluate(), new Vector(0.0, 10.0, 5.0));
		Assert.assertEquals(p.get().evaluate(), new Vector(0.0, 10.0, 5.0));
	}

	@Test
	public void pointAtTest2() {
		VectorProducerBase at = pointAt(ray(0.0, 0.0, 1.0, 0.0, 0.5, -1.0), scalar(-20));
		Assert.assertEquals(at.get().evaluate(), new Vector(0.0, -10.0, 21.0));
	}

	@Test
	public void copyTest() {
		RayCopy c = new RayCopy(new Provider<>(new Ray(new Vector(1.0, 2.0, 3.0),
															new Vector(5.0, 4.0, 3.0))));
		Assert.assertEquals(c.evaluate(), new Ray(new Vector(1.0, 2.0, 3.0),
										new Vector(5.0, 4.0, 3.0)));
	}

	@Test
	public void staticComputation() {
		StaticRayComputation comp = new StaticRayComputation(
													new Ray(new Vector(1.0, 2.0, 3.0),
															new Vector(4.0, 5.0, 6.0)));
		AcceleratedComputationEvaluable<Ray> ev = (AcceleratedComputationEvaluable<Ray>) comp.get();

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
