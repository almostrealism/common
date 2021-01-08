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

package org.almostrealism.algebra.test;

import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.computations.RayMatrixTransform;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.TranslationMatrix;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

public class RayMatrixTransformTest implements HardwareFeatures, CodeFeatures {
	protected AcceleratedComputationEvaluable<Ray> transform() {
		TransformMatrix m = new TranslationMatrix(vector(0.0, -10.0, 0.0)).evaluate();

		Ray r = new Ray(new Vector(1.0, 2.0, 3.0), new Vector(4.0, 5.0, 6.0));
		return (AcceleratedComputationEvaluable) compileProducer(new RayMatrixTransform(m.getInverse(), v(r)));
	}

	@Test
	public void apply() {
		Ray r = transform().evaluate();
		assertions(r);
	}

	@Test
	public void applyCompact() {
		AcceleratedComputationEvaluable<Ray> t = transform();
		// t.compact();

		System.out.println(t.getFunctionDefinition());

		Ray r = t.evaluate();
		assertions(r);
	}

	protected void assertions(Ray r) {
		Assert.assertEquals(1.0, r.getOrigin().getX(), Math.pow(10, -10));
		Assert.assertEquals(12.0, r.getOrigin().getY(), Math.pow(10, -10));
		Assert.assertEquals(3.0, r.getOrigin().getZ(), Math.pow(10, -10));
		Assert.assertEquals(4.0, r.getDirection().getX(), Math.pow(10, -10));
		Assert.assertEquals(5.0, r.getDirection().getY(), Math.pow(10, -10));
		Assert.assertEquals(6.0, r.getDirection().getZ(), Math.pow(10, -10));
	}
}
