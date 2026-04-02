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

package org.almostrealism.color.buffer;

import org.almostrealism.color.RGB;

/**
 * A 2D color accumulation buffer that stores and retrieves {@link RGB} values at
 * UV texture coordinates, supporting separate front and back surfaces.
 *
 * <p>Implementations accumulate color samples at UV positions and provide
 * interpolated retrieval. The buffer is used in physically-based rendering to
 * store computed radiance over the surface of objects.</p>
 *
 * @see ArrayColorBuffer
 * @see SpanningTreeColorBuffer
 * @see TriangularMeshColorBuffer
 * @author Michael Murray
 */
public interface ColorBuffer {
	/**
	 * Adds a color sample at the specified UV coordinates.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to add to the front surface buffer, {@code false} for the back
	 * @param c     the color to accumulate at the given UV position
	 */
	void addColor(double u, double v, boolean front, RGB c);

	/**
	 * Returns the interpolated color at the specified UV coordinates.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to query the front surface buffer, {@code false} for the back
	 * @return the interpolated {@link RGB} at the given UV, or {@code null} if unavailable
	 */
	RGB getColorAt(double u, double v, boolean front);

	/**
	 * Sets the scale factor applied to colors when they are added to the buffer.
	 *
	 * @param m the scale multiplier
	 */
	void setScale(double m);

	/**
	 * Returns the current scale factor applied to incoming color samples.
	 *
	 * @return the scale multiplier
	 */
	double getScale();

	/**
	 * Clears all accumulated color samples from the buffer.
	 */
	void clear();
}