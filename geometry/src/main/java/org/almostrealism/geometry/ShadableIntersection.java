/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.geometry;

import java.util.*;
import java.util.function.Supplier;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Gradient;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.computations.ProducerWithRankAdapter;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Extends {@link Intersection} to provide metadata that is required for shading,
 * including the surface normal and incident ray direction.
 *
 * <p>This class implements {@link ContinuousField}, allowing it to be used in
 * shading calculations that require gradient information. The normal is stored
 * as a {@link Ray} where:</p>
 * <ul>
 *   <li>The origin is the intersection point</li>
 *   <li>The direction is the surface normal at that point</li>
 * </ul>
 *
 * <p>Usage in shading:</p>
 * <pre>{@code
 * ShadableIntersection hit = surface.intersectAt(ray);
 * Producer<PackedCollection> point = hit.getPoint();
 * Producer<PackedCollection> normal = hit.getNormalAt(point);
 * // Use for lighting calculations...
 * }</pre>
 *
 * @author  Michael Murray
 * @see Intersection
 * @see ContinuousField
 */
public class ShadableIntersection extends Intersection implements ContinuousField, RayFeatures {
	private Producer<PackedCollection> incident;
	private Producer<Ray> normal;

	/**
	 * Constructs a ShadableIntersection from a surface gradient, ray, and distance.
	 *
	 * @param surface the surface gradient (provides normal calculation)
	 * @param r the incident ray
	 * @param distance the parametric distance to the intersection
	 */
	public ShadableIntersection(Gradient surface, Producer<?> r, Producer distance) {
		this(surface,
				RayFeatures.getInstance().pointAt(r, distance),
				RayFeatures.getInstance().direction(r), distance);
	}

	/**
	 * Constructs a ShadableIntersection from a ray, explicit normal, and distance.
	 *
	 * @param r the incident ray
	 * @param normal the surface normal at the intersection
	 * @param distance the parametric distance to the intersection
	 */
	public ShadableIntersection(Producer<?> r, Producer<PackedCollection> normal,
								Producer<PackedCollection> distance) {
		this(RayFeatures.getInstance().pointAt(r, distance),
				RayFeatures.getInstance().direction(r),
				normal, distance);
	}

	/**
	 * Constructs a ShadableIntersection from a surface gradient, point, incident direction, and distance.
	 *
	 * @param surface the surface gradient (provides normal calculation)
	 * @param point the intersection point
	 * @param incident the incident ray direction
	 * @param distance the parametric distance to the intersection
	 */
	public ShadableIntersection(Gradient surface,
								Producer<PackedCollection> point, Producer<PackedCollection> incident,
								Producer<PackedCollection> distance) {
		this(point, incident, surface.getNormalAt(point), distance);
	}

	/**
	 * Constructs a ShadableIntersection with explicit point, incident, normal, and distance.
	 *
	 * @param point the intersection point
	 * @param incident the incident ray direction
	 * @param normal the surface normal at the intersection
	 * @param distance the parametric distance to the intersection
	 */
	public ShadableIntersection(Producer<PackedCollection> point, Producer<PackedCollection> incident,
								Producer<PackedCollection> normal, Producer<PackedCollection> distance) {
		super(point, distance);

		this.incident = incident;

		CollectionProducer p = ray(getPoint(), normal);
		this.normal = new ProducerWithRankAdapter<>((Producer) p, (Producer) distance);
	}

	/**
	 * Returns the viewer direction (normalized negative incident direction).
	 * This is useful for view-dependent shading calculations.
	 *
	 * @param point the point at which to get the normal (ignored, uses stored incident)
	 * @return a producer for the negated, normalized incident direction
	 */
	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> point) {
		return minus(normalize(incident));
	}

	@Override
	public Producer<Ray> get(int index) { return normal; }
	
	public int size() { return 1; }

	@Override
	public boolean isEmpty() { return normal == null; }

	@Override
	public boolean contains(Object o) { return false; }

	@Override
	public Iterator<Producer<Ray>> iterator() { return Arrays.asList(normal).iterator(); }

	@Override
	public Object[] toArray() { return new Object[] { normal }; }

	@Override
	public <T> T[] toArray(T[] a) { return Arrays.asList(normal).toArray(a); }

	@Override
	public boolean add(Producer<Ray> e) { return false; }

	@Override
	public boolean remove(Object o) { return false; }

	@Override
	public boolean containsAll(Collection<?> c) { return false; }

	@Override
	public boolean addAll(Collection<? extends Producer<Ray>> c) { return false; }

	@Override
	public boolean addAll(int index, Collection<? extends Producer<Ray>> c) { return false; }

	@Override
	public boolean removeAll(Collection<?> c) { return false; }

	@Override
	public boolean retainAll(Collection<?> c) { return false; }

	@Override
	public void clear() { }

	@Override
	public Producer<Ray> set(int index, Producer<Ray> element) { return null; }

	@Override
	public void add(int index, Producer<Ray> element) { }

	@Override
	public Producer<Ray> remove(int index) { return null; }

	@Override
	public int indexOf(Object o) { return 0; }

	@Override
	public int lastIndexOf(Object o) { return 0; }

	@Override
	public ListIterator<Producer<Ray>> listIterator() {
		return Arrays.asList(normal).listIterator();
	}

	@Override
	public ListIterator<Producer<Ray>> listIterator(int index) {
		return Arrays.asList(normal).listIterator();
	}

	@Override
	public List<Producer<Ray>> subList(int fromIndex, int toIndex) {
		return Arrays.asList(normal).subList(fromIndex, toIndex);
	}
}
