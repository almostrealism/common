/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.space;

import java.util.*;
import java.util.function.Supplier;

import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.algebra.computations.RayDirection;
import org.almostrealism.algebra.computations.RayPointAt;
import org.almostrealism.geometry.RayFromVectors;
import io.almostrealism.relation.Producer;
import org.almostrealism.util.CodeFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.util.ProducerWithRankAdapter;

/**
 * Extends {@link Intersection} to provide metadata that is required for shading.
 * 
 * @author  Michael Murray
 */
public class ShadableIntersection extends Intersection implements ContinuousField, CodeFeatures {
	private Producer<Vector> incident;
	private Producer<Ray> normal;

	public ShadableIntersection(Gradient surface, Supplier<Evaluable<? extends Ray>> r, Producer<Scalar> distance) {
		this(surface, new RayPointAt(r, distance), new RayDirection(r), distance);
	}

	public ShadableIntersection(Supplier<Evaluable<? extends Ray>> r, Supplier<Evaluable<? extends Vector>> normal, Producer<Scalar> distance) {
		this(new RayPointAt(r, distance), new RayDirection(r), normal, distance);
	}

	public ShadableIntersection(Gradient surface, Producer<Vector> point, Producer<Vector> incident, Producer<Scalar> distance) {
		this(point, incident, surface.getNormalAt(point), distance);
	}

	public ShadableIntersection(Producer<Vector> point, Producer<Vector> incident, Supplier<Evaluable<? extends Vector>> normal, Producer<Scalar> distance) {
		super(point, distance);

		this.incident = incident;

		Producer<Ray> p = new RayFromVectors(getPoint(), normal);
		this.normal = new ProducerWithRankAdapter<>(p, distance);
	}
	
	/** Returns the viewer direction. */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		return normalize(incident).scalarMultiply(-1.0);
	}
	
	/** Delegates to {@link #getNormalAt(Producer)}. */
	@Override
	public Vector operate(Vector t) { return getNormalAt(v(t)).get().evaluate(); }

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
