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

package org.almostrealism.color;

import org.almostrealism.geometry.BasicGeometry;

/**
 * Extends {@link BasicGeometry} with a single RGB color property for use in
 * colored geometric objects that do not require full surface shading.
 *
 * @author Michael Murray
 */
public class ColoredGeometry extends BasicGeometry {
	/** The color assigned to this geometry. */
	private RGB color;

	/**
	 * Returns the color of this geometry.
	 *
	 * @return the {@link RGB} color of this geometry
	 */
	public RGB getColor() {
		return color;
	}

	/**
	 * Sets the color of this geometry.
	 *
	 * @param color the {@link RGB} color to assign
	 */
	public void setColor(RGB color) {
		this.color = color;
	}
}
