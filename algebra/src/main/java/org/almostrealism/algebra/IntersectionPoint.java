/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.algebra;

/**
 * Represents a ray-surface intersection point with position and parametric distance.
 *
 * <p>
 * {@link IntersectionPoint} stores the 3D position where a ray intersects a surface,
 * along with the parametric value t representing the distance along the ray.
 * This is used in ray tracing and geometric intersection calculations.
 * </p>
 *
 * @deprecated This class is deprecated and will be replaced with ShadableIntersection.
 * @author  Michael Murray
 * @see Vector
 */
@Deprecated
public class IntersectionPoint {
	private Vector intPt = new Vector();
	private double t;

	/**
	 * Returns the 3D position of the intersection point.
	 *
	 * @return the intersection position vector
	 */
	public Vector getIntersectionPoint() {
		return intPt;
	}

	/**
	 * Sets the 3D position of the intersection point.
	 *
	 * @param newPt  the new intersection position
	 */
	public void setIntersectionPoint(Vector newPt) {
		intPt.setTo(newPt);
	}

	/**
	 * Returns the parametric distance t along the ray where intersection occurs.
	 * For a ray defined as origin + t * direction, this is the t value.
	 *
	 * @return the parametric distance
	 */
	public double getT() {
		return t;
	}

	/**
	 * Sets the parametric distance t along the ray where intersection occurs.
	 *
	 * @param t  the parametric distance
	 */
	public void setT(double t) {
		this.t = t;
	}
}
