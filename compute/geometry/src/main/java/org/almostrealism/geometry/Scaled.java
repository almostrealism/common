/*
 * Copyright 2016 Michael Murray
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
 * An interface for objects that can be scaled in 3D space.
 * Provides methods to get and set scale factors for each axis independently.
 *
 * <p>Scale values of 1.0 represent the original size. Values greater than 1.0
 * increase the size, while values less than 1.0 decrease the size. Negative
 * values can be used for mirroring along an axis.</p>
 *
 * @author Michael Murray
 * @see Positioned
 * @see Oriented
 * @see BasicGeometry
 */
public interface Scaled {
	/**
	 * Sets the scale factors for this object along each axis.
	 *
	 * @param x the scale factor along the x-axis
	 * @param y the scale factor along the y-axis
	 * @param z the scale factor along the z-axis
	 */
	void setScale(float x, float y, float z);

	/**
	 * Returns the current scale factors of this object.
	 *
	 * @return a float array containing {scaleX, scaleY, scaleZ}
	 */
	float[] getScale();
}
