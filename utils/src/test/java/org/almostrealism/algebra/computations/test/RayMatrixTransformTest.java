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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.computations.RayExpressionComputation;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayProducer;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class RayMatrixTransformTest implements TestFeatures {
	protected TransformMatrix getMatrix() {
		return new TransformMatrix(new double[][] {
				{0.25, 0.0, 0.0, 0.0},
				{0.0, 0.25, 0.0, 3.4},
				{0.0, 0.0, 0.25, -3.0},
				{0.0, 0.0, 0.0, 1.0}
		});
	}

	protected ExpressionComputation<Ray> getRay1() {
		return ray(1.0, 2.0, 3.0,4.0, 5.0, 6.0);
	}

	@Test
	public void scaleAndTranslate() {
		RayExpressionComputation transform = transform(getMatrix(), getRay1());
		Evaluable<Ray> ace = transform.get();
		Ray r = ace.evaluate();
		System.out.println(r);

		assertEquals(0.25, r.getOrigin().getX());
		assertEquals(3.9, r.getOrigin().getY());
		assertEquals(-2.25, r.getOrigin().getZ());
		assertEquals(1.0, r.getDirection().getX());
		assertEquals(1.25, r.getDirection().getY());
		assertEquals(1.5, r.getDirection().getZ());
	}
}
