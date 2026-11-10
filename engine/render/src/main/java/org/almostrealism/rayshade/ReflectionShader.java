/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.rayshade;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.ShaderSet;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.ReflectedRay;
import org.almostrealism.raytrace.LightingEngineAggregator;
import org.almostrealism.texture.Texture;

import java.util.Arrays;
import java.util.List;

/**
 * {@link ReflectionShader} provides realistic reflection rendering for surfaces using
 * Schlick's approximation to the Fresnel equations.
 *
 * <p>The Fresnel effect describes how reflectivity varies with viewing angle - surfaces
 * become more reflective at grazing angles. Schlick's approximation provides an efficient
 * calculation:</p>
 * <pre>
 * R(theta) = R0 + (1 - R0) * (1 - cos(theta))^5
 * </pre>
 * <p>where R0 is the base reflectivity at normal incidence and theta is the angle between
 * the view direction and surface normal.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li><b>Fresnel reflection:</b> Physically-based view-dependent reflectivity</li>
 *   <li><b>Recursive tracing:</b> Supports multiple reflection bounces (up to {@link #maxReflections})</li>
 *   <li><b>Reflection blur:</b> Optional blurred reflections for rough surfaces</li>
 *   <li><b>Environment mapping:</b> Can use textures as environment maps</li>
 *   <li><b>Editable properties:</b> Configurable through the {@link Editable} interface</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@link #setReflectivity(double)} - Base reflectivity at normal viewing angle (0.0-1.0)</li>
 *   <li>{@link #setReflectiveColor(Producer)} - Tint color applied to reflections</li>
 *   <li>{@link #setBlur(double)} - Blur factor for diffuse/rough reflections</li>
 *   <li>{@link #setEnvironmentMap(Texture)} - Fallback texture when rays miss all surfaces</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ReflectionShader mirror = new ReflectionShader();
 * mirror.setReflectivity(0.95);           // 95% reflective at normal
 * mirror.setReflectiveColor(white());     // No tint
 * mirror.setBlur(0.0);                    // Perfect mirror
 *
 * sphere.addShader(mirror);
 * }</pre>
 *
 * <h2>Recursion Control</h2>
 * <p>Recursive reflections are limited by {@link #maxReflections} (default 4) to prevent
 * infinite loops in hall-of-mirrors scenarios. When the limit is reached, the base
 * reflective color is returned without further tracing.</p>
 *
 * @see org.almostrealism.color.Shader
 * @see RefractionShader
 * @see org.almostrealism.raytrace.LightingEngineAggregator
 * @author Michael Murray
 */
public class ReflectionShader extends ShaderSet<ShaderContext> implements
		Shader<ShaderContext>, RGBFeatures, RayFeatures {

	/**
	 * Maximum number of recursive reflection bounces allowed.
	 * <p>Limits recursion to prevent infinite loops (e.g., parallel mirrors).
	 * Default is 4. Higher values produce more accurate results but are slower.</p>
	 */
	public static int maxReflections = 4;

  /** The base reflectivity at normal incidence (0.0 to 1.0) and the blur factor for diffuse reflections. */
  private double reflectivity, blur;
  /** The tint color multiplied with reflected ray colors. */
  private Producer<PackedCollection> reflectiveColor;
  /** Optional environment map texture used when reflected rays miss all scene surfaces. */
  private Texture eMap;

	/**
	 * Constructs a new ReflectionShader object with a reflectivity of 0.0
	 * and white as a reflective color.
	 */
	public ReflectionShader() {
		this.setReflectivity(0.0);
		this.setBlur(0.0);
		this.setReflectiveColor(white());
	}
	
	/**
	 * Constructs a new ReflectionShader object with the specified reflectivity
	 * and reflective color.
	 */
	public ReflectionShader(double reflectivity, Producer<PackedCollection> reflectiveColor) {
		this.setReflectivity(reflectivity);
		this.setReflectiveColor(reflectiveColor);
		this.setBlur(0.0);
	}
	
	/** Method specified by the Shader interface. */
	@Override
	public Producer<PackedCollection> shade(ShaderContext p, DiscreteField normals) {
		Producer point = origin(p.getIntersection().get(0));

		if (p.getReflectionCount() > ReflectionShader.maxReflections) {
			return multiply(reflectiveColor, p.getSurface().getValueAt(point));
		}
		
		p.addReflection();
		
		List<ShadableSurface> allSurfaces = p.getAllSurfaces();
		List<Light> allLights = p.getAllLights();

		Producer<PackedCollection> r = getReflectiveColor();
		if (size() > 0) {
			r = multiply(r, ReflectionShader.super.shade(p, normals));
		}

		final Producer<PackedCollection> fr = r;

		Producer<PackedCollection> n = direction(normals.iterator().next());
		Producer<PackedCollection> viewer = p.getIntersection().getNormalAt(point);

		Producer<PackedCollection> tc = null;

		// TODO Should surface color be factored in to reflection?
		if (p.getSurface() == null || p.getSurface().getShadeFront()) {
			tc = reflection(point, viewer, n, Arrays.asList(p.getOtherSurfaces()), allLights, p, fr);
		}

		if (p.getSurface() == null || p.getSurface().getShadeBack()) {
			Producer<PackedCollection> back = reflection(point, viewer, minus(n), allSurfaces, allLights, p, fr);
			tc = tc == null ? back : add(tc, back);
		}

		Producer<PackedCollection> lightColor = p.getLight().getColorAt(point);
		Producer<PackedCollection> ftc = tc;
		return GeneratedColorProducer.fromProducer(this, multiply(ftc, lightColor));
	}
	
	/**
	 * Returns the colour reflected toward the viewer from one side of the surface: the colour
	 * seen along the {@link ReflectedRay}, weighted by Schlick's approximation of the Fresnel
	 * reflectance {@code R0 + (1 - R0)(1 - cos(theta))^5} and by the reflective colour.
	 *
	 * <p>The colour seen along the reflected ray is the closest hit among the given surfaces,
	 * selected on the device by {@link LightingEngineAggregator#getClosestColor()}, so the
	 * whole reflection compiles into the kernel of the surface being shaded.</p>
	 *
	 * @param point    the intersection point
	 * @param viewer   the unit vector from the intersection point toward the viewer
	 * @param normal   the surface normal on the side being shaded
	 * @param surfaces the surfaces the reflected ray may hit
	 * @param lights   the lights illuminating those surfaces
	 * @param context  the shader context, carrying the reflection depth
	 * @param tint     the reflective colour, including any nested shaders
	 * @return a producer of the reflected colour
	 */
	protected Producer<PackedCollection> reflection(Producer<PackedCollection> point,
													Producer<PackedCollection> viewer,
													Producer<PackedCollection> normal,
													Iterable<ShadableSurface> surfaces,
													Iterable<Light> lights, ShaderContext context,
													Producer<PackedCollection> tint) {
		Producer<PackedCollection> reflectedRay = new ReflectedRay(point, viewer, normal, blur);

		// A reflected ray that misses every surface contributes black: the environment map is not
		// yet consulted (a known limitation in docs/internals/ray-traced-reflection-and-refraction.md)
		CollectionProducer color = new LightingEngineAggregator(reflectedRay, surfaces, lights, context)
				.getClosestColor();

		CollectionProducer cosine = dotProduct(normal, viewer).divide(length(normal).multiply(length(viewer)));
		CollectionProducer reflective = c(reflectivity).add(
				c(1 - reflectivity).multiply(pow(c(1.0).subtract(cosine), c(5.0))));
		return color.multiply(tint).multiply(reflective);
	}

	/**
	 * Sets the reflectivity value used by this ReflectionShader object.
	 */
	public void setReflectivity(double reflectivity) { this.reflectivity = reflectivity; }
	
	/**
	 * Sets the blur factor used by this ReflectionShader object.
	 * 
	 * @param blur  Blur factor to use.
	 */
	public void setBlur(double blur) { this.blur = blur; }
	
	/**
	 * Sets the reflective color used by this ReflectionShader object
	 * to the color represented by the specified ColorProducer object.
	 */
	public void setReflectiveColor(Producer<PackedCollection> color) { this.reflectiveColor = color; }
	
	/**
	 * Sets the Texture object used as an environment map for this ReflectionShader object.
	 * 
	 * @param map  The Texture object to use.
	 */
	public void setEnvironmentMap(Texture map) { this.eMap = map; }
	
	/**
	 * Returns the reflectivity value used by this ReflectionShader object.
	 *
	 * @return  The reflectivity value used by this ReflectionShader object.
	 */
	public double getReflectivity() { return this.reflectivity; }

	/**
	 * Returns the blur factor used by this ReflectionShader object.
	 *
	 * @return  The blur factor used by this ReflectionShader object.
	 */
	public double getBlur() { return this.blur; }
	
	/**
	 * Returns the reflective color used by this {@link ReflectionShader}
	 * as an {@link RGB} {@link Producer}.
	 */
	public Producer<PackedCollection> getReflectiveColor() { return this.reflectiveColor; }
	
	/**
	 * Returns the Texture object used as an environment map for this ReflectionShader object.
	 *
	 * @return  The Texture object used as an environment map for this ReflectionShader object.
	 */
	public Texture getEnvironmentMap() { return this.eMap; }

	/**
	 * Returns "Reflection Shader".
	 */
	@Override
	public String toString() { return "Reflection Shader"; }
}
