/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.geometry;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;

public class UniformSphericalRandom implements Evaluable<Vector>, VectorFeatures {
	private static UniformSphericalRandom local = new UniformSphericalRandom();

	@Override
	public Vector evaluate(Object[] args) {
		double r[] = new double[3];

		double y = 2 * Math.PI * Math.random();
		double z = 2 * Math.PI * Math.random();

		r[0] = Math.sin(y) * Math.cos(z);
		r[1] = Math.sin(y) * Math.sin(z);
		r[2] = Math.cos(y);

		return new Vector(r);
	}

	public static UniformSphericalRandom getInstance() { return local; }
}
