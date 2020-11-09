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

package org.almostrealism.geometry.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.algebra.computations.RayCopy;
import org.almostrealism.algebra.computations.RayPointAt;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.Provider;
import org.junit.Assert;
import org.junit.Test;

public class RayTest implements HardwareFeatures {
	@Test
	public void pointAtTest1() {
		Provider<Ray> r = new Provider<>(new Ray(new Vector(0.0, 0.0, 0.0),
															new Vector(0.0, 1.0, 0.5)));
		Provider<Scalar> s = new Provider<>(new Scalar(10));
		RayPointAt p = new RayPointAt(r, s);
		Assert.assertTrue(compileProducer(p).evaluate(new Object[0]).equals(new Vector(0.0, 10.0, 5.0)));
		Assert.assertTrue(compileProducer(p).evaluate(new Object[0]).equals(new Vector(0.0, 10.0, 5.0)));
	}

	@Test
	public void pointAtTest2() {
		Provider<Ray> r = new Provider<>(new Ray(new Vector(0.0, 0.0, 1.0),
															new Vector(0.0, 0.5, -1.0)));
		RayPointAt at = new RayPointAt(r, new Provider<>(new Scalar(-20)));
		Assert.assertTrue(compileProducer(at).evaluate(new Object[0]).equals(new Vector(0.0, -10.0, 21.0)));
	}

	@Test
	public void copyTest() {
		RayCopy c = new RayCopy(new Provider<>(new Ray(new Vector(1.0, 2.0, 3.0),
															new Vector(5.0, 4.0, 3.0))));
		Assert.assertTrue(c.evaluate(new Object[0]).equals(new Ray(new Vector(1.0, 2.0, 3.0),
															new Vector(5.0, 4.0, 3.0))));
	}
}
