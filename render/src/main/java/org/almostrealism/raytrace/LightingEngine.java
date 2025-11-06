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

import org.almostrealism.color.AmbientLight;
import org.almostrealism.color.DirectionalAmbientLight;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.SurfaceLight;
import org.almostrealism.Ops;
import org.almostrealism.algebra.computations.ProducerWithRankAdapter;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.Intersectable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.Shadable;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.Curve;
import io.almostrealism.relation.Producer;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.CodeFeatures;
import org.almostrealism.geometry.DimensionAware;
import io.almostrealism.relation.Evaluable;

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
 *   <li>Some light types have migrated to new calculation methods (see deprecated lightingCalculation)</li>
 *   <li>The surface shader interface (Shadable from ar-common) may not be fully implemented</li>
 * </ul>
 *
 * @param <T> The type of intersection data, expected to be a ShadableIntersection
 *
 * @see org.almostrealism.geometry.ShadableIntersection
 */
// TODO  T must extend ShadableIntersection so that distance can be used as the rank
public class LightingEngine<T extends ContinuousField> extends ProducerWithRankAdapter<RGB>
				implements DimensionAware, CodeFeatures, RGBFeatures {
	public static boolean enableShadows = false;

	private T intersections;
	private Curve<RGB> surface;

	private Producer<RGB> color;
	private Producer<Scalar> distance;

	public LightingEngine(T intersections,
						  Curve<RGB> surface,
						  Collection<Curve<RGB>> otherSurfaces,
						  Light light, Iterable<Light> otherLights, ShaderContext p) {
		super(((ShadableIntersection) intersections).getDistance());
		this.color = shadowAndShadeProduct(intersections, surface, otherSurfaces, light, otherLights, p);
		this.intersections = intersections;
		this.surface = surface;
		this.distance = (Producer) ((ShadableIntersection) intersections).getDistance();
	}

	protected CollectionProducer<RGB> shadowAndShadeProduct(ContinuousField intersections,
															Curve<RGB> surface,
															Collection<Curve<RGB>> otherSurfaces,
															Light light, Iterable<Light> otherLights, ShaderContext p) {
		Supplier shadowAndShade[] = shadowAndShade(intersections, surface, otherSurfaces, light, otherLights, p);
		return multiply((Producer) shadowAndShade[0], (Producer) shadowAndShade[1]);
	}

	protected Supplier[] shadowAndShade(ContinuousField intersections,
											   Curve<RGB> surface,
											   Collection<Curve<RGB>> otherSurfaces,
											   Light light, Iterable<Light> otherLights, ShaderContext p) {
		Supplier<Evaluable<? extends RGB>> shadow, shade;

		List<Intersectable> allSurfaces = new ArrayList<>();
		if (surface instanceof Intersectable) allSurfaces.add((Intersectable) surface);

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

		if (light instanceof SurfaceLight) {
			shade = lightingCalculation(intersections, origin(intersections.get(0)).get(),
										surface, otherSurfaces,
										((SurfaceLight) light).getSamples(), p);
		} else if (light instanceof PointLight) {
			shade = surface instanceof Shadable ? ((PointLight) light).forShadable((Shadable) surface, intersections.get(0), context) : null;
		} else if (light instanceof DirectionalAmbientLight) {
			DirectionalAmbientLight directionalLight = (DirectionalAmbientLight) light;

			Vector l = (directionalLight.getDirection().divide(
					directionalLight.getDirection().length())).minus();

			context.setLightDirection(Ops.o().v(l));

			shade = surface instanceof Shadable ? ((Shadable) surface).shade(context) : null;
		} else if (light instanceof AmbientLight) {
			shade = ((AmbientLight) light).lightingCalculation(surface, origin(intersections.get(0)));
		} else {
			shade = RGBFeatures.getInstance().black();
		}

		return new Supplier[] { shadow, shade };
	}

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		if (intersections instanceof DimensionAware) {
			((DimensionAware) intersections).setDimensions(width, height, ssw, ssh);
		}
	}

	public Curve<RGB> getSurface() { return surface; }

	@Override
	public Producer<RGB> getProducer() { return color; }

	@Override
	public Producer<Scalar> getRank() { return distance; }

	/**
	 * Performs the lighting calculations for the specified surface at the specified point of intersection
	 * on that surface using the lighting data from the specified Light objects and returns an RGB object
	 * that represents the color of the point. A list of all other surfaces in the scene must be specified
	 * for reflection/shadowing. This list does not include the specified surface for which the lighting
	 * calculations are to be done.
	 */
	public Producer<RGB> lightingCalculation(ContinuousField intersection, Evaluable<Vector> point,
																		 Curve<RGB> surface, Iterable<Curve<RGB>> otherSurfaces,
																		 Light lights[], ShaderContext p) {
		Producer<RGB> color = null;

		for (int i = 0; i < lights.length; i++) {
			Light otherLights[] = new Light[lights.length - 1];

			for (int j = 0; j < i; j++) { otherLights[j] = lights[j]; }
			for (int j = i + 1; j < lights.length; j++) { otherLights[j - 1] = lights[j]; }

			Producer<RGB> c = lightingCalculation(intersection, point, surface,
										otherSurfaces, lights[i], otherLights, p);
			if (c != null) {
				if (color == null) {
					color = c;
				} else {
					color = add(color, c);
				}
			}
		}

		return color;
	}

	/**
	 * Performs the lighting calculations for the specified surface at the specified point of
	 * intersection on that surface using the lighting data from the specified Light object
	 * and returns an RGB object that represents the color of the point. A list of all other
	 * surfaces in the scene must be specified for reflection/shadowing. This list does not
	 * include the specified surface for which the lighting calculations are to be done.
	 */
	@Deprecated
	public Producer<RGB> lightingCalculation(ContinuousField intersection, Evaluable<Vector> point,
																		 Curve<RGB> surface,
																		 Iterable<Curve<RGB>> otherSurfaces, Light light,
																		 Light otherLights[], ShaderContext p) {
		List<Curve<RGB>> allSurfaces = new ArrayList<>();
		for (Curve<RGB> s : otherSurfaces) allSurfaces.add(s);
		allSurfaces.add(surface);

		if (light instanceof SurfaceLight) {
			Light l[] = ((SurfaceLight) light).getSamples();
			return lightingCalculation(intersection, point,
					surface, otherSurfaces, l, p);
		} else if (light instanceof PointLight) {
			throw new IllegalArgumentException("Migrated elsewhere");
		} else if (light instanceof DirectionalAmbientLight) {
			throw new IllegalArgumentException("Migrated elsewhere");
		} else if (light instanceof AmbientLight) {
			throw new IllegalArgumentException("Migrated elsewhere");
		} else {
			return RGBFeatures.getInstance().black();
		}
	}

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
