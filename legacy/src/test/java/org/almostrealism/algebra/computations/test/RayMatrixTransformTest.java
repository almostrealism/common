/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.algebra.computations.test;

import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.computations.RayMatrixTransform;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

public class RayMatrixTransformTest implements HardwareFeatures, CodeFeatures {
	protected TransformMatrix getMatrix() {
		return new TransformMatrix(new double[][] {
				{0.25, 0.0, 0.0, 0.0},
				{0.0, 0.25, 0.0, 3.4},
				{0.0, 0.0, 0.25, -3.0},
				{0.0, 0.0, 0.0, 1.0}
		});
	}

	protected RayProducer getRay1() {
		return ray(1.0, 2.0, 3.0,4.0, 5.0, 6.0);
	}

	@Test
	public void scaleAndTranslate() {
		RayMatrixTransform transform = new RayMatrixTransform(getMatrix(), getRay1());
		Ray r = transform.get().evaluate();
		System.out.println(r);
		Assert.assertEquals(0.25, r.getOrigin().getX(), Math.pow(10, -7));
		Assert.assertEquals(3.9, r.getOrigin().getY(), Math.pow(10, -7));
		Assert.assertEquals(-2.25, r.getOrigin().getZ(), Math.pow(10, -7));
		Assert.assertEquals(1.0, r.getDirection().getX(), Math.pow(10, -7));
		Assert.assertEquals(1.25, r.getDirection().getY(), Math.pow(10, -7));
		Assert.assertEquals(1.5, r.getDirection().getZ(), Math.pow(10, -7));
	}
}
