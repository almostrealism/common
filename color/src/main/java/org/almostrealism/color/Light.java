/*
 * Copyright 2017 Michael Murray
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

import org.almostrealism.algebra.Vector;
import io.almostrealism.relation.Producer;
import org.almostrealism.texture.Texture;

/**
 * Represents a light source used for rendering in the Almost Realism graphics pipeline.
 *
 * <p>A {@code Light} provides lighting information including intensity, color, and
 * position-dependent color calculations. Light implementations are used by shaders
 * to compute surface illumination during ray tracing and other rendering operations.</p>
 *
 * <h2>Light Properties</h2>
 * <ul>
 *   <li><b>Intensity</b>: A scalar multiplier for light brightness (typically 0.0 to 1.0)</li>
 *   <li><b>Color</b>: The base {@link RGB} color of the light</li>
 * </ul>
 *
 * <h2>Available Implementations</h2>
 * <ul>
 *   <li>{@link PointLight}: Light emanating from a point in space with distance attenuation</li>
 *   <li>{@link AmbientLight}: Uniform light applied equally to all surfaces</li>
 *   <li>{@link DirectionalAmbientLight}: Directional light coming from a specific direction</li>
 *   <li>{@link SurfaceLight}: Light emitted by a surface geometry</li>
 * </ul>
 *
 * <h2>Usage in Shading</h2>
 * <p>Lights are typically used within a {@link ShaderContext} or {@link LightingContext}
 * to provide illumination data to {@link Shader} implementations:</p>
 * <pre>{@code
 * LightingContext ctx = new LightingContext();
 * ctx.setLight(new PointLight(position, 1.0, new RGB(1.0, 1.0, 1.0)));
 * ctx.setLightDirection(directionProducer);
 * Producer<RGB> shadedColor = shader.shade(ctx, normalField);
 * }</pre>
 *
 * @see PointLight
 * @see AmbientLight
 * @see DirectionalAmbientLight
 * @see LightingContext
 * @see Shader
 * @author Michael Murray
 */
public interface Light {
	/** Default value indicating whether this light type casts shadows. */
	boolean castShadows = true;

	/**
	 * Sets the intensity of this light.
	 *
	 * <p>Intensity is a scalar multiplier applied to the light's color.
	 * A value of 1.0 represents full intensity.</p>
	 *
	 * @param intensity the intensity value (typically 0.0 to 1.0, but can exceed 1.0 for HDR)
	 */
	void setIntensity(double intensity);

	/**
	 * Sets the base color of this light.
	 *
	 * @param color the {@link RGB} color of the light
	 */
	void setColor(RGB color);

	/**
	 * Returns the intensity of this light.
	 *
	 * @return the intensity as a double value
	 */
	double getIntensity();

	/**
	 * Returns the base color of this light.
	 *
	 * @return the {@link RGB} color of the light
	 */
	RGB getColor();

	/**
	 * Returns the effective color of this light at a given point in space.
	 *
	 * <p>This method accounts for any position-dependent effects such as:
	 * <ul>
	 *   <li>Distance attenuation (for point lights)</li>
	 *   <li>Directional variations</li>
	 *   <li>Spotlight falloff</li>
	 * </ul>
	 *
	 * <p>The returned color typically includes the intensity multiplier already applied.</p>
	 *
	 * @param point a {@link Producer} yielding the 3D point at which to evaluate the light
	 * @return a {@link Producer} yielding the attenuated/modified {@link RGB} color at that point
	 */
	Producer<RGB> getColorAt(Producer<Vector> point);
}
