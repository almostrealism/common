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
 * An interface for objects that have a position in 3D space.
 * Provides methods to get and set the position using float coordinates.
 *
 * <p>This interface is typically implemented by geometry objects,
 * cameras, lights, and other scene elements that need to be
 * positioned in space.</p>
 *
 * @author Michael Murray
 * @see Oriented
 * @see Scaled
 * @see BasicGeometry
 */
public interface Positioned {
	/**
	 * Sets the position of this object in 3D space.
	 *
	 * @param x the x-coordinate of the position
	 * @param y the y-coordinate of the position
	 * @param z the z-coordinate of the position
	 */
	void setPosition(float x, float y, float z);

	/**
	 * Returns the current position of this object.
	 *
	 * @return a float array containing {x, y, z} coordinates
	 */
	float[] getPosition();
}
