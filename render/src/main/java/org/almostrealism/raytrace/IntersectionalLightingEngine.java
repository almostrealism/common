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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Light;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.Curve;
import org.almostrealism.geometry.Intersectable;

import java.util.Collection;

/**
 * {@link IntersectionalLightingEngine} is a concrete implementation of {@link LightingEngine}
 * that computes ray-surface intersections and delegates to the parent class for lighting calculations.
 *
 * <p>This class serves as the bridge between the intersection calculation (provided by the surface's
 * {@link Intersectable#intersectAt(Producer)} method) and the lighting computation (handled by
 * {@link LightingEngine}).</p>
 *
 * <p>The constructor computes the intersection between the ray and surface, producing a
 * {@link org.almostrealism.geometry.ShadableIntersection} (from ar-common) that contains:</p>
 * <ul>
 *   <li>The intersection point in 3D space</li>
 *   <li>The distance along the ray to the intersection</li>
 *   <li>The surface normal at the intersection (via getNormalAt)</li>
 * </ul>
 *
 * <p>This intersection data is then used by {@link LightingEngine} to:</p>
 * <ul>
 *   <li>Rank this surface relative to others (using intersection distance)</li>
 *   <li>Compute shadows</li>
 *   <li>Apply surface shading with the specified light</li>
 * </ul>
 *
 * <p><b>Note:</b> The surface must implement both {@link Intersectable} (to compute intersections)
 * and extend {@link Curve} of RGB (to provide surface color). Most surfaces extend
 * {@link org.almostrealism.space.AbstractSurface} which provides both.</p>
 *
 * @see LightingEngine
 * @see org.almostrealism.geometry.ShadableIntersection
 * @see org.almostrealism.space.AbstractSurface
 */
public class IntersectionalLightingEngine extends LightingEngine<ContinuousField> {
	// TODO  Arguments are redundant (they are found in ShaderContext)
	/**
	 * Constructs a new {@link IntersectionalLightingEngine} that computes the intersection
	 * of a ray with a surface and calculates lighting at that intersection point.
	 *
	 * <p>The intersection is computed by calling {@code surface.intersectAt(ray)}, which returns
	 * a {@link org.almostrealism.geometry.ShadableIntersection} containing the intersection point,
	 * distance, and surface normal.</p>
	 *
	 * @param ray           The ray to trace
	 * @param surface       The surface to intersect (must be both Intersectable and Curve&lt;RGB&gt;)
	 * @param otherSurfaces All other surfaces in the scene (used for shadows/reflections)
	 * @param light         The light source to use for shading
	 * @param otherLights   All other lights in the scene (for multi-light scenarios)
	 * @param p             The shader context containing rendering parameters
	 */
    public IntersectionalLightingEngine(Producer<?> ray, Intersectable surface, Collection<Curve<PackedCollection>> otherSurfaces,
										Light light, Iterable<Light> otherLights, ShaderContext p) {
        super(surface.intersectAt(ray), (Curve<PackedCollection>) surface, otherSurfaces, light, otherLights, p);
    }

	@Override
	public String toString() {
    	return "IntersectionalLightingEngine[" + getSurface() + "]";
	}
}
