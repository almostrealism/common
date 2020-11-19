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

import io.almostrealism.code.Scope;
import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.geometry.DefaultRayProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.algebra.computations.RayDirection;
import org.almostrealism.algebra.computations.RayPointAt;
import org.almostrealism.geometry.RayFromVectors;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.Producer;
import org.almostrealism.util.ProducerWithRankAdapter;

/**
 * Extends {@link Intersection} to provide metadata that is required for shading.
 * 
 * @author  Michael Murray
 */
public class ShadableIntersection extends Intersection implements ContinuousField, CodeFeatures {
	private Supplier<Producer<? extends Vector>> incident;
	private Supplier<Producer<? extends Ray>> normal;

	public ShadableIntersection(Gradient surface, Supplier<Producer<? extends Ray>> r, Supplier<Producer<? extends Scalar>> distance) {
		this(surface, new RayPointAt(r, distance), new RayDirection(r), distance);
	}

	public ShadableIntersection(Supplier<Producer<? extends Ray>> r, Supplier<Producer<? extends Vector>> normal, Supplier<Producer<? extends Scalar>> distance) {
		this(new RayPointAt(r, distance), new RayDirection(r), normal, distance);
	}

	public ShadableIntersection(Gradient surface, Supplier<Producer<? extends Vector>> point, Supplier<Producer<? extends Vector>> incident, Supplier<Producer<? extends Scalar>> distance) {
		this(point, incident, () -> surface.getNormalAt(point.get()), distance);
	}

	public ShadableIntersection(Supplier<Producer<? extends Vector>> point, Supplier<Producer<? extends Vector>> incident, Supplier<Producer<? extends Vector>> normal, Supplier<Producer<? extends Scalar>> distance) {
		super(point, distance);

		this.incident = incident;

		Producer<Ray> p = new DefaultRayProducer(new RayFromVectors(getPoint(), normal));
		this.normal = () -> new ProducerWithRankAdapter<>(p, (Producer<Scalar>) distance.get()); // TODO  Should be accelerated producer
	}
	
	/** Returns the viewer direction. */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		return (Producer<Vector>) normalize(incident).scalarMultiply(-1.0).get();
	}
	
	/** Delegates to {@link #getNormalAt(Producer)}. */
	@Override
	public Vector operate(Vector t) { return getNormalAt((Producer<Vector>) v(t).get()).evaluate(); }

	@Override
	public Scope getScope(NameProvider p) {
		throw new RuntimeException("getScope is not implemented"); // TODO
	}
	
	@Override
	public Producer<Ray> get(int index) { return (Producer<Ray>) normal.get(); }
	
	public int size() { return 1; }

	@Override
	public boolean isEmpty() { return normal == null; }

	@Override
	public boolean contains(Object o) { return false; }

	@Override
	public Iterator<Producer<Ray>> iterator() { return Arrays.asList((Producer<Ray>) normal.get()).iterator(); }

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
		return Arrays.asList((Producer<Ray>) normal.get()).listIterator();
	}

	@Override
	public ListIterator<Producer<Ray>> listIterator(int index) {
		return Arrays.asList((Producer<Ray>) normal.get()).listIterator();
	}

	@Override
	public List<Producer<Ray>> subList(int fromIndex, int toIndex) {
		return Arrays.asList((Producer<Ray>) normal.get()).subList(fromIndex, toIndex);
	}
}
