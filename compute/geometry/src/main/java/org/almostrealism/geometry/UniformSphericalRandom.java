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
 * <p>The implementation uses spherical coordinates with a random azimuth and a
 * polar coordinate chosen so that {@code cos(theta)} is uniform on {@code [-1, 1]}.
 * Sampling the polar angle itself uniformly would concentrate points near the poles
 * and break the uniform surface distribution; drawing {@code cos(theta)} uniformly
 * instead yields an even distribution over the sphere's surface.</p>
 *
 * <p>This class is implemented as a singleton accessible via {@link #getInstance()}.</p>
 *
 * @author Michael Murray
 * @see Vector
 */
public class UniformSphericalRandom implements Evaluable<Vector>, VectorFeatures {
	/** The shared singleton instance of this class. */
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

		// Sample the polar coordinate so that cos(theta) is uniform on [-1, 1];
		// sampling the polar angle itself uniformly would concentrate points near
		// the poles and break the uniform surface distribution this class promises.
		double cosTheta = 2 * Math.random() - 1;
		double sinTheta = Math.sqrt(1 - cosTheta * cosTheta);
		double phi = 2 * Math.PI * Math.random();

		r[0] = sinTheta * Math.cos(phi);
		r[1] = sinTheta * Math.sin(phi);
		r[2] = cosTheta;

		return new Vector(r);
	}

	/**
	 * Returns the singleton instance of this class.
	 *
	 * @return the shared UniformSphericalRandom instance
	 */
	public static UniformSphericalRandom getInstance() { return local; }
}
