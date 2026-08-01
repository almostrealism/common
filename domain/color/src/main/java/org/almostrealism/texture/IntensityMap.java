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

package org.almostrealism.texture;

/**
 * Provides a scalar intensity value for a 3D position expressed as UV texture coordinates
 * plus an optional depth (W) coordinate.
 *
 * <p>Implementations model procedural textures such as {@link Noise}, {@link Turbulence},
 * and {@link CosineIntensityMap} that map a spatial position to a single floating-point
 * intensity in the range [0, 1] (or beyond, depending on the implementation).</p>
 *
 * @see Noise
 * @see Turbulence
 * @see CosineIntensityMap
 * @author Michael Murray
 */
public interface IntensityMap {
	/**
	 * Returns the intensity at the specified 3D texture coordinate.
	 *
	 * @param u the horizontal texture coordinate
	 * @param v the vertical texture coordinate
	 * @param w the depth texture coordinate
	 * @return the intensity value at {@code (u, v, w)}
	 */
	double getIntensity(double u, double v, double w);
}
