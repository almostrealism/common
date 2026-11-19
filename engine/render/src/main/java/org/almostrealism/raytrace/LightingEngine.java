/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.raytrace;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.algebra.computations.ProducerWithRankAdapter;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.DimensionAware;
import org.almostrealism.geometry.Intersectable;
import org.almostrealism.geometry.ShadableIntersection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link LightingEngine} computes the color contribution from a single light source at a ray
 * intersection point on a surface. It combines shadow computation and surface shading to produce
 * the final RGB color.
 *
 * <p>The lighting calculation process:</p>
 * <ol>
 *   <li>Compute the intersection between the ray and surface (passed as ContinuousField)</li>
 *   <li>Calculate shadow mask if shadows are enabled</li>
 *   <li>Invoke the surface's shader with the light and intersection information</li>
 *   <li>Multiply shadow and shade to get final color contribution</li>
 * </ol>
 *
 * <p>This class implements {@link io.almostrealism.relation.ProducerWithRank} where the rank is the intersection distance.
 * This allows {@link LightingEngineAggregator} to use ranked choice to select the closest visible
 * surface.</p>
 *
 * <p><b>Type Parameter Note:</b> The type parameter T should extend ShadableIntersection (from ar-common)
 * to ensure intersection distance is available as the rank. Currently constrained to ContinuousField
 * due to legacy reasons - this is a TODO item.</p>
 *
 * <p><b>Known Issues:</b></p>
 * <ul>
 *   <li>Shadows are disabled by default ({@code enableShadows=false})</li>
 *   <li>The surface shader interface (Shadable from ar-common) may not be fully implemented</li>
 * </ul>
 *
 * @param <T> The type of intersection data, expected to be a ShadableIntersection
 *
 * @see org.almostrealism.geometry.ShadableIntersection
 */
// TODO  T must extend ShadableIntersection so that distance can be used as the rank
public class LightingEngine<T extends ContinuousField> extends ProducerWithRankAdapter<PackedCollection>
				implements DimensionAware, CodeFeatures, RGBFeatures {
	/** When true, shadow masks are computed for each surface point; disabled by default for performance. */
	public static boolean enableShadows = false;

	/** The intersection data (position, normal) for the surface point being lit. */
	private T intersections;
	/** The primary surface whose shading is being evaluated. */
	private ShadableSurface surface;

	/** The computed color contribution (shadow * shade product) for this lighting engine. */
	private Producer<PackedCollection> color;
	/** The intersection distance used as the rank for closest-surface selection. */
	private Producer<PackedCollection> distance;

	/**
	 * Constructs a {@link LightingEngine} for a specific surface-light pair.
	 *
	 * @param intersections  The intersection data providing surface position and normal
	 * @param surface        The surface being shaded
	 * @param otherSurfaces  All other surfaces for shadow occlusion testing
	 * @param light          The light source to evaluate
	 * @param otherLights    All other lights in the scene
	 * @param p              Shader context with additional rendering parameters
	 */
	public LightingEngine(T intersections,
						  ShadableSurface surface,
						  Collection<? extends ShadableSurface> otherSurfaces,
						  Light light, Iterable<Light> otherLights, ShaderContext p) {
		super(((ShadableIntersection) intersections).getDistance());
		this.color = shadowAndShadeProduct(intersections, surface, otherSurfaces, light, otherLights, p);
		this.intersections = intersections;
		this.surface = surface;
		this.distance = ((ShadableIntersection) intersections).getDistance();
	}

	/**
	 * Computes the combined shadow-and-shade color by multiplying the shadow mask and shade results.
	 *
	 * @param intersections  The ray-surface intersection data
	 * @param surface        The surface being shaded
	 * @param otherSurfaces  Other scene surfaces for shadow testing
	 * @param light          The light to evaluate
	 * @param otherLights    Other lights in the scene
	 * @param p              Shader context
	 * @return A producer that computes shadow * shade color
	 */
	protected CollectionProducer shadowAndShadeProduct(ContinuousField intersections,
													   ShadableSurface surface,
													   Collection<? extends ShadableSurface> otherSurfaces,
													   Light light, Iterable<Light> otherLights, ShaderContext p) {
		Supplier shadowAndShade[] = shadowAndShade(intersections, surface, otherSurfaces, light, otherLights, p);
		return multiply((Producer) shadowAndShade[0], (Producer) shadowAndShade[1]);
	}

	/**
	 * Computes separate shadow and shade suppliers for the given surface-light pair.
	 *
	 * <p>Returns a two-element array: {@code [shadow, shade]}. The shadow supplier returns
	 * white (no shadow) when shadows are disabled or the light does not cast shadows. The
	 * shade supplier is the light's own {@link Light#lightingCalculation} for the surface.</p>
	 *
	 * @param intersections  The ray-surface intersection data
	 * @param surface        The surface being shaded
	 * @param otherSurfaces  Other scene surfaces for shadow testing
	 * @param light          The light to evaluate
	 * @param otherLights    Other lights in the scene
	 * @param p              Shader context
	 * @return A two-element array {@code [shadowSupplier, shadeSupplier]}
	 */
	protected Supplier[] shadowAndShade(ContinuousField intersections,
											   ShadableSurface surface,
											   Collection<? extends ShadableSurface> otherSurfaces,
											   Light light, Iterable<Light> otherLights, ShaderContext p) {
		Supplier shadow, shade;

		List<Intersectable> allSurfaces = new ArrayList<>();
		allSurfaces.add(surface);

		if (enableShadows && light.castShadows) {
			shadow = new ShadowMask(light, allSurfaces, Ops.o().origin(intersections.get(0)).get());
		} else {
			shadow = RGBFeatures.getInstance().white();
		}

		ShaderContext context = p.clone();
		context.setLight(light);
		context.setIntersection(intersections);
		context.setOtherLights(otherLights);
		context.setOtherSurfaces(otherSurfaces);

		shade = light.lightingCalculation(surface, context);

		return new Supplier[] { shadow, shade };
	}

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		if (intersections instanceof DimensionAware) {
			((DimensionAware) intersections).setDimensions(width, height, ssw, ssh);
		}
	}

	public ShadableSurface getSurface() { return surface; }

	@Override
	public Producer<PackedCollection> getProducer() { return color; }

	@Override
	public Producer<PackedCollection> getRank() { return distance; }

//	public static double brdf(Vector ld, Vector vd, Vector n, double nv, double nu, double r) {
//		ld = ld.divide(ld.length());
//		vd = vd.divide(vd.length());
//		n = n.divide(n.length());
//
//		Vector h = ld.add(vd);
//		h = h.divide(h.length());
//
//		Vector v = null;
//
//		if (Math.abs(h.getX()) < Math.abs(h.getY()) && Math.abs(h.getX()) < Math.abs(h.getZ()))
//			v = new Vector(0.0, h.getZ(), -h.getY());
//		else if (Math.abs(h.getY()) < Math.abs(h.getZ()))
//			v = new Vector(h.getZ(), 0.0, -h.getX());
//		else
//			v = new Vector(h.getY(), -h.getX(), 0.0);
//
//		v = v.divide(v.length());
//
//		Vector u = v.crossProduct(h);
//
//		double hu = h.dotProduct(u);
//		double hv = h.dotProduct(v);
//		double hn = h.dotProduct(n);
//		double hk = h.dotProduct(ld);
//
//		//System.out.println("hk = " + hk);
//
//		double a = Math.sqrt((nu + 1.0) * (nv + 1.0)) / (8.0 * Math.PI);
//		double b = Math.pow(hn, (nu * hu * hu + nv * hv * hv) / ( 1.0 - hn * hn));
//		b = b / (hk * Math.max(n.dotProduct(ld), n.dotProduct(vd)));
//
//		double value =  a * b;
//
//		//System.out.println("a = " + a);
//		//System.out.println("b = " + b);
//		//System.out.println("BRDF =  " + value);
//
//		return value;
//	}
}
