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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Vector;

/**
 * An interface for objects that can be positioned in 3D space using a {@link Vector}.
 * This interface provides a single method for setting the location using a Vector
 * object, which provides more precision than the float-based {@link Positioned} interface.
 *
 * @author Michael Murray
 * @see Positioned
 * @see Vector
 */
public interface Locatable {
	/**
	 * Sets the location of this object in 3D space.
	 *
	 * @param location a {@link Vector} representing the new position
	 */
	void setLocation(Vector location);
}
