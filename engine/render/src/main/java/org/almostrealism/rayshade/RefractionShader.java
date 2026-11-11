/*
 * Copyright 2026 Michael Murray
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
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.raytrace.LightingEngineAggregator;

import java.util.Arrays;
import java.util.List;

/**
 * {@link RefractionShader} renders transparent materials such as glass and water by tracing
 * the ray refracted at the surface according to Snell's law:
 * <pre>
 * n1 * sin(theta1) = n2 * sin(theta2)
 * </pre>
 *
 * <p>The surface is treated as the boundary of a closed solid of the material (a sphere of
 * glass, for example): a ray refracted into it always leaves through it again. The whole
 * shader is a {@link Producer} graph, so the refracted colour compiles into the kernel of the
 * surface being shaded:</p>
 * <ul>
 *   <li>The ray entering the material is refracted with
 *       {@link org.almostrealism.geometry.GeometryFeatures#refract}, which applies Snell's law
 *       with relative index {@code 1 / n} on the way in and {@code n} on the way out, and falls
 *       back to the mirror reflection under total internal reflection.</li>
 *   <li>The point where it leaves is found by intersecting the ray inside with the
 *       {@link ShadableSurface} being shaded ({@link ShaderContext#getSurface()}), and the ray
 *       is refracted again there, out of the material.</li>
 *   <li>Each refracted ray starts a short distance ({@link #RAY_OFFSET}) along its direction,
 *       so that it does not intersect the surface at the point it leaves.</li>
 *   <li>The colour seen along the ray leaving the material is the closest hit among the other
 *       surfaces, selected on the device by {@link LightingEngineAggregator#getClosestColor()}.
 *       The shader is invoked once for each light, so that hit is lit only by the context's
 *       current light ({@link ShaderContext#getLight()}); the per-light results add up to the
 *       colour under every light instead of repeating it once per light.</li>
 *   <li>The colour is weighted by the transmitted fraction {@code 1 - R}, where {@code R} is
 *       Schlick's approximation of the Fresnel reflectance at the entry point,
 *       {@code R0 + (1 - R0)(1 - cos(theta))^5} with {@code R0 = ((n - 1) / (n + 1))^2}, and by
 *       the attenuation factors of the material the ray travelled through.</li>
 * </ul>
 *
 * <p>Each refraction through the material counts as one bounce of the {@link ShaderContext},
 * and at most {@link #maxRefractions} bounces are traced (a second transparent object seen
 * through the first is another bounce); a ray still bouncing after that contributes black.</p>
 *
 * <h2>Common Index of Refraction Values</h2>
 * <ul>
 *   <li>Air: 1.0</li>
 *   <li>Water: 1.33</li>
 *   <li>Glass: 1.5 - 1.9</li>
 *   <li>Diamond: 2.42</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RefractionShader glass = new RefractionShader(1.5);
 * glass.setAttenuationFactors(0.95, 0.95, 0.98);     // Slight blue-green tint
 * sphere.setShaders(new Shader[] { glass });
 * }</pre>
 *
 * @see org.almostrealism.color.Shader
 * @see ReflectionShader
 * @see org.almostrealism.raytrace.LightingEngineAggregator
 * @author Michael Murray
 */
public class RefractionShader implements Shader<ShaderContext>, RGBFeatures, CodeFeatures {
	/**
	 * The distance along the refracted direction at which the refracted ray starts, so that
	 * it does not intersect the surface at the point it leaves.
	 */
	public static final double RAY_OFFSET = 1e-3;

	/**
	 * The number of bounces of a {@link ShaderContext} beyond which refraction is no longer
	 * traced. The default of 2 lets one transparent object be seen through another.
	 */
	public static int maxRefractions = 2;

	/** The index of refraction of the material (e.g., 1.0 for air, 1.5 for glass). */
	private double indexOfRefraction;
	/** Red, green, and blue attenuation factors applied to light travelling inside the material. */
	private double ra, ga, ba;

	/**
	 * Constructs a new {@link RefractionShader} with an index of refraction of 1.0 (which
	 * passes rays through undeflected) and no attenuation.
	 */
	public RefractionShader() {
		this(1.0);
	}

	/**
	 * Constructs a new {@link RefractionShader} with the given index of refraction and no
	 * attenuation.
	 *
	 * @param indexOfRefraction the index of refraction of the material
	 */
	public RefractionShader(double indexOfRefraction) {
		setIndexOfRefraction(indexOfRefraction);
		setAttenuationFactors(1.0, 1.0, 1.0);
	}

	/**
	 * Computes the colour seen through the surface at the intersection described by the
	 * given context.
	 *
	 * <p>The candidate surfaces for the ray leaving the material are the other surfaces, in
	 * the order the context holds them; {@link LightingEngineAggregator} breaks ties between
	 * equal distances in favour of the earlier candidate.</p>
	 *
	 * @param p       The shader context containing intersection and lighting information
	 * @param normals The surface normals at the intersection point
	 * @return A Producer that computes the refracted color
	 */
	@Override
	public Producer<PackedCollection> shade(ShaderContext p, DiscreteField normals) {
		if (p.getReflectionCount() >= maxRefractions) {
			return black();
		}

		p.addReflection();

		CollectionProducer point = origin(p.getIntersection().get(0));
		Producer<PackedCollection> normal = direction(normals.iterator().next());
		Producer<PackedCollection> viewer = p.getIntersection().getNormalAt(point);

		// Refract into the material, then find where the ray leaves it again
		CollectionProducer inside = refract(viewer, normal, indexOfRefraction);
		ContinuousField exit = p.getSurface().intersectAt(ray(point, inside, RAY_OFFSET));

		// Refract back out of the material where the ray leaves it
		CollectionProducer outside = refract(inside.minus(), direction(exit.get(0)), indexOfRefraction);
		CollectionProducer refractedRay = ray(origin(exit.get(0)), outside, RAY_OFFSET);

		// The shader runs once for each light, so the ray leaving the material is lit only by
		// the current light; summed over the lights, the passes give the colour under all of them
		List<ShadableSurface> surfaces = Arrays.asList(p.getOtherSurfaces());
		CollectionProducer color = new LightingEngineAggregator(refractedRay, surfaces, List.of(p.getLight()), p)
				.getClosestColor();

		CollectionProducer cos = dotProduct(normalize(viewer), normalize(normal));
		double r0 = Math.pow((indexOfRefraction - 1.0) / (indexOfRefraction + 1.0), 2.0);
		CollectionProducer transmitted = c(1.0 - r0).subtract(
				c(1.0 - r0).multiply(pow(c(1.0).subtract(abs(cos)), c(5.0))));
		return GeneratedColorProducer.fromProducer(this, color.multiply(rgb(ra, ga, ba)).multiply(transmitted));
	}

	/**
	 * Sets the index of refraction value used by this RefractionShader object.
	 *
	 * @param n the index of refraction of the material
	 */
	public void setIndexOfRefraction(double n) { this.indexOfRefraction = n; }

	/**
	 * Sets the attenuation factors applied to light travelling inside the material.
	 *
	 * @param r the red attenuation factor
	 * @param g the green attenuation factor
	 * @param b the blue attenuation factor
	 */
	public void setAttenuationFactors(double r, double g, double b) {
		this.ra = r;
		this.ga = g;
		this.ba = b;
	}

	/**
	 * Returns the index of refraction value used by this RefractionShader object.
	 *
	 * @return the index of refraction of the material
	 */
	public double getIndexOfRefraction() { return this.indexOfRefraction; }

	/**
	 * Returns the 3 attenuation factors (RGB) used by this RefractionShader object.
	 *
	 * @return the red, green and blue attenuation factors
	 */
	public double[] getAttenuationFactors() {
		return new double[] {this.ra, this.ga, this.ba};
	}

	/**
	 * Returns "Refraction Shader".
	 */
	@Override
	public String toString() { return "Refraction Shader"; }
}
