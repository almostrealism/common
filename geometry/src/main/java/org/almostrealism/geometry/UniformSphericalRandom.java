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

/**
 * Generates uniformly distributed random unit vectors on the surface of a sphere.
 * This is useful for Monte Carlo methods in rendering, such as ambient occlusion,
 * global illumination, and random sampling for anti-aliasing.
 *
 * <p>The implementation uses spherical coordinates with random azimuth and polar
 * angles to achieve uniform distribution over the sphere's surface.</p>
 *
 * <p>This class is implemented as a singleton accessible via {@link #getInstance()}.</p>
 *
 * @author Michael Murray
 * @see Vector
 */
public class UniformSphericalRandom implements Evaluable<Vector>, VectorFeatures {
	private static final UniformSphericalRandom local = new UniformSphericalRandom();

	/**
	 * Generates a random unit vector uniformly distributed on the unit sphere.
	 *
	 * @param args not used, may be null
	 * @return a new random unit vector
	 */
	@Override
	public Vector evaluate(Object[] args) {
		double[] r = new double[3];

		double y = 2 * Math.PI * Math.random();
		double z = 2 * Math.PI * Math.random();

		r[0] = Math.sin(y) * Math.cos(z);
		r[1] = Math.sin(y) * Math.sin(z);
		r[2] = Math.cos(y);

		return new Vector(r);
	}

	/**
	 * Returns the singleton instance of this class.
	 *
	 * @return the shared UniformSphericalRandom instance
	 */
	public static UniformSphericalRandom getInstance() { return local; }
}
