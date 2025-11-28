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

package org.almostrealism.color;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.DiscreteField;

/**
 * Defines the shading operation for computing surface illumination.
 *
 * <p>A {@code Shader} transforms lighting information and surface properties into
 * a final color value. Implementations typically represent different lighting models
 * such as Lambertian diffuse, Phong specular, or more complex physically-based models.</p>
 *
 * <h2>Available Implementations</h2>
 * <ul>
 *   <li>{@link DiffuseShader}: Lambertian diffuse shading (matte surfaces)</li>
 *   <li>{@link HighlightShader}: Phong specular highlights (shiny surfaces)</li>
 *   <li>{@link BlendingShader}: Cartoon/toon shading with discrete color bands</li>
 *   <li>{@link SilhouetteShader}: Edge detection and outline rendering</li>
 *   <li>{@link ShaderSet}: Composite shader that combines multiple shaders</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // Create a basic diffuse shader
 * Shader<ShaderContext> diffuse = new DiffuseShader();
 *
 * // Set up context with light and surface information
 * ShaderContext ctx = new ShaderContext();
 * ctx.setLight(pointLight);
 * ctx.setLightDirection(directionProducer);
 * ctx.setSurface(surface);
 *
 * // Compute the shaded color
 * Producer<PackedCollection> color = diffuse.shade(ctx, normalField);
 * RGB result = color.get().evaluate();
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 * <p>Shader implementations should:</p>
 * <ul>
 *   <li>Return a {@link Producer} for lazy/hardware-accelerated evaluation</li>
 *   <li>Handle both front-facing and back-facing surfaces if appropriate</li>
 *   <li>Return black for surfaces facing away from the light (unless emissive)</li>
 * </ul>
 *
 * @param <C> the type of {@link LightingContext} required by this shader
 * @see DiffuseShader
 * @see HighlightShader
 * @see ShaderSet
 * @see LightingContext
 * @author Michael Murray
 */
public interface Shader<C extends LightingContext> {
	/**
	 * Computes the shaded color for a surface given lighting parameters and surface normals.
	 *
	 * @param parameters the lighting context containing light, direction, and surface information
	 * @param normals a discrete field providing surface normal vectors at intersection points
	 * @return a {@link Producer} that yields the computed {@link RGB} color
	 */
	Producer<PackedCollection> shade(C parameters, DiscreteField normals);
}
