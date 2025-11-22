/*
 * Copyright 2018 Michael Murray
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

import org.almostrealism.color.DirectionalAmbientLight;
import org.almostrealism.color.PointLight;
import org.almostrealism.geometry.ClosestIntersection;
import org.almostrealism.geometry.Intersectable;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Ray;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.DynamicProducerForMemoryData;

import java.util.function.Supplier;

/**
 * {@link ShadowMask} computes whether a point in the scene is in shadow with respect to a
 * specific light source. It returns white (1,1,1) for fully lit points and black (0,0,0)
 * for shadowed points.
 *
 * <p>The shadow computation works by:</p>
 * <ol>
 *   <li>Constructing a "shadow ray" from the surface point toward the light source</li>
 *   <li>Testing this ray against all scene surfaces for intersections</li>
 *   <li>If an intersection exists between the point and the light, the point is shadowed</li>
 * </ol>
 *
 * <h2>Light Types</h2>
 * <p>Shadow computation differs based on light type:</p>
 * <ul>
 *   <li><b>PointLight:</b> Shadow ray direction is from point to light position.
 *       Only intersections between the point and light are considered (not beyond).</li>
 *   <li><b>DirectionalAmbientLight:</b> Shadow ray is in the opposite direction of the light.
 *       Any intersection along the ray causes shadow (infinite light source).</li>
 *   <li><b>Other lights:</b> Return white (no shadow computation).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>ShadowMask is typically used internally by {@link LightingEngine} when
 * {@code LightingEngine.enableShadows} is true. It multiplies the shaded color by the
 * shadow mask result (white = full color, black = no light contribution).</p>
 *
 * <pre>{@code
 * // Internal usage in LightingEngine
 * if (enableShadows && light.castShadows) {
 *     shadow = new ShadowMask(light, surfaces, point);
 * }
 * finalColor = shadow * shade;  // black shadow = no light
 * }</pre>
 *
 * @see LightingEngine
 * @see org.almostrealism.color.PointLight
 * @see org.almostrealism.color.DirectionalAmbientLight
 * @author Michael Murray
 */
public class ShadowMask implements Evaluable<RGB>, Supplier<Evaluable<? extends RGB>> {
	private Light light;
	private Iterable<Intersectable> surfaces;
	private Evaluable<Vector> point;

	/**
	 * Constructs a new {@link ShadowMask} for computing shadows from a specific light.
	 *
	 * @param light    The light source to test shadows against
	 * @param surfaces The scene surfaces that may cast shadows
	 * @param point    An evaluable that provides the surface point to test
	 */
	public ShadowMask(Light light, Iterable<Intersectable> surfaces, Evaluable<Vector> point) {
		this.light = light;
		this.surfaces = surfaces;
		this.point = point;
	}

	/**
	 * Returns this ShadowMask as an evaluable (self-reference).
	 *
	 * @return This ShadowMask instance
	 */
	@Override
	public Evaluable<? extends RGB> get() { return this; }

	/**
	 * Evaluates whether the surface point is in shadow relative to the light source.
	 *
	 * <p>For point lights, a shadow ray is cast from the surface point toward the light.
	 * If any surface intersects this ray between the point and the light, the point is
	 * shadowed. For directional lights, any intersection along the reverse light direction
	 * causes shadow.</p>
	 *
	 * @param args The evaluation arguments (passed through to point evaluable)
	 * @return RGB(1,1,1) white if the point is lit, RGB(0,0,0) black if shadowed
	 */
	@Override
	public RGB evaluate(Object[] args) {
		Vector p = point.evaluate(args);
		if (p == null) return new RGB(1.0, 1.0, 1.0);

		double maxDistance = -1.0;
		Vector direction;

		if (light instanceof PointLight) {
			direction = ((PointLight) light).getLocation().subtract(p);
			direction = direction.divide(direction.length());
			maxDistance = direction.length();
		} else if (light instanceof DirectionalAmbientLight) {
			direction = ((DirectionalAmbientLight) light).getDirection().minus();
		} else {
			return new RGB(1.0, 1.0, 1.0);
		}

		final Vector fdirection = direction;

		Producer<Ray> shadowRay = new DynamicProducerForMemoryData<>(arguments -> new Ray(p, fdirection));

		ClosestIntersection intersection = new ClosestIntersection(shadowRay, surfaces);
		Ray r = intersection.get(0).get().evaluate(args);
		double intersect = 0.0;
		if (r != null)
			intersect = r.getOrigin().subtract(p).length();

		if (r == null || intersect <= Intersection.e || (maxDistance >= 0.0 && intersect > maxDistance)) {
//			if (Settings.produceOutput && Settings.produceRayTracingEngineOutput) {
//				Settings.rayEngineOut.print(" False }");
//			}

			return new RGB(1.0, 1.0, 1.0);
		} else {
//			if (Settings.produceOutput && Settings.produceRayTracingEngineOutput) {
//				Settings.rayEngineOut.print(" True }");
//			}

			return new RGB(0.0, 0.0, 0.0);
		}
	}
}
