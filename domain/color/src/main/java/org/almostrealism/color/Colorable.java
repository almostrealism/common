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

package org.almostrealism.color;

/**
 * Implemented by objects that have a settable RGB color.
 *
 * @author Michael Murray
 */
public interface Colorable {
	/**
	 * Sets the color of this object using the specified red, green, and blue channel values.
	 *
	 * @param r the red channel value in the range [0.0, 1.0]
	 * @param g the green channel value in the range [0.0, 1.0]
	 * @param b the blue channel value in the range [0.0, 1.0]
	 */
	void setColor(double r, double g, double b);
}
