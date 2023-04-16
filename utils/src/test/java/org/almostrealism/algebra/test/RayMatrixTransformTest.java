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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.TranslationMatrix;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class RayMatrixTransformTest implements HardwareFeatures, TestFeatures {
	protected Evaluable<Ray> transform() {
		TransformMatrix m = new TranslationMatrix(vector(0.0, -10.0, 0.0)).evaluate();

		Ray r = new Ray(new Vector(1.0, 2.0, 3.0), new Vector(4.0, 5.0, 6.0));
		return transform(m.getInverse(), v(r)).get();
	}

	@Test
	public void apply() {
		Ray r = transform().evaluate();
		assertions(r);
	}

	@Test
	public void applyCompact() {
		Evaluable<Ray> t = transform();
		Ray r = t.evaluate();
		assertions(r);
	}

	protected void assertions(Ray r) {
		assertEquals(1.0, r.getOrigin().getX());
		assertEquals(12.0, r.getOrigin().getY());
		assertEquals(3.0, r.getOrigin().getZ());
		assertEquals(4.0, r.getDirection().getX());
		assertEquals(5.0, r.getDirection().getY());
		assertEquals(6.0, r.getDirection().getZ());
	}
}
