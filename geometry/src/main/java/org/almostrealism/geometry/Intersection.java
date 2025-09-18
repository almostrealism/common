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
 * An Intersection object stores data for the intersections between a ray and a surface.
 */
public class Intersection implements DimensionAware {
	/** A very small value (0.00000001) that is used in '>=' and '<=' operations to account for computational errors. */
	public static final double e = 0.00000001;

	private Producer<Vector> point;
	private Producer<PackedCollection<?>> distance;

	/**
	 * Constructs a new {@link Intersection} that represents an intersection between the specified
	 * Ray and Surface objects at the specified points along the ray represented by the Ray.
	 */
	public Intersection(Producer<Vector> point,
						Producer<PackedCollection<?>> distance) {
		this.point = point;
		this.distance = distance;
	}
	
	public Producer<Vector> getPoint() { return point; }

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
