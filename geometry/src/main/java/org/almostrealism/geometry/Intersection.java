/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Vector;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Stores data for the intersection between a ray and a surface.
 * This class holds the intersection point and the parametric distance along the ray
 * where the intersection occurred.
 *
 * <p>The intersection follows the ray equation: {@code p = o + t * d} where:</p>
 * <ul>
 *   <li>{@code p} is the intersection point</li>
 *   <li>{@code o} is the ray origin</li>
 *   <li>{@code t} is the parametric distance (stored in this class)</li>
 *   <li>{@code d} is the ray direction</li>
 * </ul>
 *
 * @author Michael Murray
 * @see ShadableIntersection
 * @see Ray
 */
public class Intersection implements DimensionAware {
	/**
	 * A very small value (0.00000001) used as an epsilon for comparisons
	 * to account for floating-point computational errors.
	 */
	public static final double e = 0.00000001;

	private Producer<Vector> point;
	private Producer<PackedCollection<?>> distance;

	/**
	 * Constructs a new {@link Intersection} with the specified intersection point and distance.
	 *
	 * @param point a producer for the 3D intersection point
	 * @param distance a producer for the parametric distance along the ray
	 */
	public Intersection(Producer<Vector> point,
						Producer<PackedCollection<?>> distance) {
		this.point = point;
		this.distance = distance;
	}

	/**
	 * Returns the intersection point in 3D space.
	 *
	 * @return a producer for the intersection point
	 */
	public Producer<Vector> getPoint() { return point; }

	/**
	 * Returns the parametric distance from the ray origin to the intersection point.
	 * This is the value 't' in the ray equation p = o + t * d.
	 *
	 * @return a producer for the distance value
	 */
	public Producer<PackedCollection<?>> getDistance() { return distance; }

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		if (distance instanceof DimensionAware) {
			((DimensionAware) distance).setDimensions(width, height, ssw, ssh);
		}
	}

	/**
	 * @return  A String representation of this Intersection object.
	 */
	@Override
	public String toString() {
		return "[" + getPoint() + "]";
	}
}
