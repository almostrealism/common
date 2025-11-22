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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintains the state needed for lighting calculations during rendering.
 *
 * <p>A {@code LightingContext} holds references to the current light source being
 * processed, its direction, and other lights in the scene. It is typically passed
 * to shaders to provide the information needed for illumination calculations.</p>
 *
 * <h2>Usage Pattern</h2>
 * <p>The context is usually created once per render pass and updated as different
 * lights are processed:</p>
 * <pre>{@code
 * LightingContext ctx = new LightingContext();
 *
 * for (Light light : sceneLights) {
 *     ctx.setLight(light);
 *     ctx.setLightDirection(computeDirection(light, surfacePoint));
 *     ctx.setOtherLights(getOtherLights(light, sceneLights));
 *
 *     Producer<RGB> contribution = shader.shade(shaderContext);
 *     // Accumulate contribution...
 * }
 * }</pre>
 *
 * <h2>Light Direction Convention</h2>
 * <p>The light direction should be a unit vector pointing <em>toward</em> the light
 * source from the surface point being shaded. This is the convention expected by
 * standard shading models like Lambertian diffuse and Phong specular.</p>
 *
 * @see Light
 * @see ShaderContext
 * @see Shader
 * @author Michael Murray
 */
public class LightingContext {
	/** Direction vector pointing toward the light source. */
	private Producer<Vector> lightDirection;
	/** The primary light being processed. */
	private Light light;
	/** Other lights in the scene (for multi-light rendering). */
	private Iterable<Light> otherLights;
	
	/**
	 * Sets the direction toward the light to the specified {@link Vector} {@link Producer}.
	 * 
	 * @param l  Vector object to use.
	 */
	public void setLightDirection(Producer<Vector> l) { this.lightDirection = l; }
	
	/**
	 * @return  A {@link Vector} {@link Producer} representing the direction toward the light (this can be expected to be unit length).
	 */
	public Producer<Vector> getLightDirection() { return this.lightDirection; }
	
	/**
	 * Sets the Light to the specified Light object.
	 * 
	 * @param l  Light object to use.
	 */
	public void setLight(Light l) { this.light = l; }
	
	/** @return  A Light object representing the light. */
	public Light getLight() { return this.light; }
	
	/**
	 * Sets the other Lights to those stored in the specified array.
	 * 
	 * @param l  Array of Light objects to use.
	 */
	public void setOtherLights(Iterable<Light> l) { this.otherLights = l; }
	
	/** @return  An array of Light objects representing the other lights in the scene. */
	public Iterable<Light> getOtherLights() { return this.otherLights; }
	
	/**
	 * TOOO  This should cache all lights, so that a new array is not created every time.
	 */
	public List<Light> getAllLights() {
		List<Light> li = new ArrayList<>();
		li.add(light);
		for (Light l : otherLights) li.add(l);
		return li;
	}
}
