/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds the closest intersection point among multiple surfaces for a given ray.
 * This class is central to ray tracing, where rays must be tested against many
 * surfaces to find the nearest hit point.
 *
 * <p>The class evaluates all intersections lazily and returns the one with the
 * smallest positive distance value (i.e., in front of the ray origin).</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * List<Intersectable<PackedCollection<?>>> surfaces = scene.getSurfaces();
 * ClosestIntersection closest = new ClosestIntersection(ray, surfaces);
 * Producer<Ray> hitNormal = closest.get(0);  // Gets intersection position and normal
 * }</pre>
 *
 * @author Michael Murray
 * @see Intersectable
 * @see ContinuousField
 */
public class ClosestIntersection extends ArrayList<Producer<Ray>> implements ContinuousField {
	private Producer<Ray> r;
	private List<ContinuousField> s;

	/**
	 * Constructs a ClosestIntersection finder for the given ray and surfaces.
	 *
	 * @param ray the ray to test for intersections
	 * @param surfaces the collection of surfaces to test against
	 */
	public ClosestIntersection(Producer<Ray> ray, Iterable<Intersectable> surfaces) {
		r = ray;
		s = new ArrayList<>();

		for (Intersectable<?> in : surfaces) {
			s.add(in.intersectAt(ray));
		}

		this.add(() -> args -> {
			double d = Double.MAX_VALUE;
			ContinuousField intersection = null;

			p:
			for (ContinuousField in : s) {
				if (in == null) continue p;

				PackedCollection<?> dist = (PackedCollection<?>) ((Evaluable) ((ShadableIntersection) in).getDistance().get()).evaluate(args);
				if (dist == null) continue p;

				double v = dist.toDouble(0);
				if (v >= 0.0 && v < d) {
					d = v;
					intersection = in;
				}
			}

			return intersection == null ? null : intersection.get(0).get().evaluate(args);
		});
	}

	/**
	 * Returns the surface normal at the closest intersection point.
	 *
	 * @param point the point at which to get the normal (typically the intersection point)
	 * @return a producer for the surface normal vector at the closest intersection
	 */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		return () -> args -> {
			double d = Double.MAX_VALUE;
			Vector normal = null;

			p:
			for (ContinuousField in : s) {
				if (in == null) continue p;

				PackedCollection<?> dist = (PackedCollection<?>) ((Evaluable) ((ShadableIntersection) in).getDistance().get()).evaluate(args);
				if (dist == null) continue p;

				double v = dist.toDouble(0);
				if (v >= 0.0 && v < d) {
					d = v;
					normal = in.getNormalAt(point).get().evaluate(args);
				}
			}

			return normal;
		};
	}
}
