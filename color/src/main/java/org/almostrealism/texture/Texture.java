/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.texture;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;

/**
 * Defines a texture mapping that provides color values at 3D points.
 *
 * <p>A {@code Texture} maps 3D coordinates to color values, enabling surfaces
 * to have spatially-varying color properties. Textures are a fundamental component
 * of realistic rendering, providing surface details that would be impractical to
 * model geometrically.</p>
 *
 * <h2>Available Implementations</h2>
 * <ul>
 *   <li>{@link ImageTexture}: Maps 2D image data onto surfaces with various projections</li>
 *   <li>{@link StripeTexture}: Procedural striped pattern generation</li>
 * </ul>
 *
 * <h2>Projection Types (for ImageTexture)</h2>
 * <ul>
 *   <li>Spherical: Wraps image around a sphere using latitude/longitude</li>
 *   <li>Planar XY: Projects image onto the XY plane</li>
 *   <li>Planar XZ: Projects image onto the XZ plane</li>
 *   <li>Planar YZ: Projects image onto the YZ plane</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create an image texture with spherical projection
 * Texture earth = new ImageTexture(
 *     ImageTexture.SPHERICAL_PROJECTION,
 *     new URL("http://example.com/earth.jpg")
 * );
 *
 * // Get color at a point on a sphere surface
 * Vector surfacePoint = new Vector(0.5, 0.7, 0.2);
 * RGB color = earth.operate(surfacePoint);
 * }</pre>
 *
 * @see ImageTexture
 * @see RGB
 * @author Michael Murray
 */
public interface Texture {
	/**
	 * Returns an evaluable that provides color at a point using additional parameters.
	 *
	 * @param args additional arguments for texture evaluation (implementation-specific)
	 * @return an {@link Evaluable} that yields the {@link RGB} color
	 * @deprecated Use {@link #operate(Vector)} instead for simpler texture lookup
	 */
	@Deprecated
	Evaluable<PackedCollection> getColorAt(Object[] args);

	/**
	 * Returns the color at the specified 3D point.
	 *
	 * <p>The interpretation of the point depends on the projection type used.
	 * For spherical projection, the point is normalized to a unit sphere.
	 * For planar projections, only the relevant coordinate pair is used.</p>
	 *
	 * @param t the 3D point at which to sample the texture
	 * @return the {@link RGB} color at that point
	 */
	RGB operate(Vector t);
}
