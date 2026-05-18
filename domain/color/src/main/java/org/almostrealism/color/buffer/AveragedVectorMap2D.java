/*
 * Copyright 2020 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.color.buffer;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * A 2D map that accumulates and averages 3D vectors at UV texture coordinates,
 * maintaining separate front and back surface accumulators.
 *
 * <p>Implementations use this interface to store directional data such as surface normals
 * or light directions per texel, then retrieve the average direction at any UV position.</p>
 *
 * @see AveragedVectorMap2D96Bit
 * @author Mike Murray
 */
public interface AveragedVectorMap2D {
	/**
	 * Accumulates a vector sample at the given UV coordinates.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param e     a producer yielding the 3-component vector to accumulate
	 * @param front {@code true} to add to the front surface map, {@code false} for the back
	 */
	void addVector(double u, double v, Producer<PackedCollection> e, boolean front);

	/**
	 * Returns the average accumulated vector at the given UV coordinates.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to query the front surface map, {@code false} for the back
	 * @return a double array of length 3 containing the averaged vector components
	 */
	double[] getVector(double u, double v, boolean front);

	/**
	 * Returns the number of vector samples accumulated at the given UV coordinates.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to query the front surface count, {@code false} for the back
	 * @return the number of samples at the specified texel
	 */
	int getSampleCount(double u, double v, boolean front);
}
