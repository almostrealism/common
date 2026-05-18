/*
 * Copyright 2022 Michael Murray
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

/**
 * An interface for objects that have an orientation in 3D space.
 * Provides methods to get and set the orientation using an angle-axis representation.
 *
 * <p>The orientation is specified as a rotation angle around an arbitrary axis
 * defined by the (x, y, z) vector. This is known as the angle-axis representation
 * of rotation.</p>
 *
 * @author Michael Murray
 * @see Positioned
 * @see Scaled
 * @see BasicGeometry
 */
public interface Oriented {
	/**
	 * Sets the orientation of this object as a rotation around an axis.
	 *
	 * @param angle the rotation angle in radians
	 * @param x the x-component of the rotation axis
	 * @param y the y-component of the rotation axis
	 * @param z the z-component of the rotation axis
	 */
	void setOrientation(float angle, float x, float y, float z);

	/**
	 * Returns the current orientation of this object.
	 *
	 * @return a float array containing {angle, x, y, z} where angle is in radians
	 *         and (x, y, z) defines the rotation axis
	 */
	float[] getOrientation();
}
