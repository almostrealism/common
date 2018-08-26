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

import io.almostrealism.code.Scope;
import org.almostrealism.algebra.*;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayDirection;
import org.almostrealism.geometry.RayPointAt;
import org.almostrealism.util.Producer;

/**
 * Extends {@link Intersection} to provide metadata that is required for shading.
 * 
 * @author  Michael Murray
 */
public class ShadableIntersection extends Intersection implements ContinuousField {
	private Producer<Vector> incident;
	private Producer<Ray> normal; // TODO  Change to Producer<Ray>

	public ShadableIntersection(Intersectable<?> surface, Producer<Ray> r, Producer<Scalar> distance) {
		this(surface, new RayPointAt(r, distance), new RayDirection(r), distance);
	}

	public ShadableIntersection(Intersectable<?> surface,
								Producer<Vector> point, Producer<Vector> incident, Producer<Scalar> distance) {
		super(surface, point, distance);

		this.incident = incident;

		normal = new Producer<Ray>() {
			public Ray evaluate(Object args[]) {
				Vector p = (Vector) getPoint().evaluate(args);
				if (p == null) return null;
				return new Ray(p, ((Gradient) surface).getNormalAt(p).evaluate(args));
			}

			public void compact() {
				// TODO  Compact surface.getNormalAt
			}
		};
	}
	
	/** Returns the viewer direction. */
	@Override
	public Producer<Vector> getNormalAt(Vector point) {
		return new Producer<Vector>() {

			@Override
			public Vector evaluate(Object[] args) {
				Vector v = incident.evaluate(args);
				v.normalize();
				v.multiplyBy(-1);
				return v;
			}

			@Override
			public void compact() {
				incident.compact();
			}
		};
	}
	
	/** Delegates to {@link #getNormalAt(Vector)}. */
	@Override
	public Vector operate(Triple t) { return getNormalAt(new Vector(t.getA(), t.getB(), t.getC())).evaluate(new Object[0]); }

	@Override
	public Scope getScope(String prefix) {
		throw new RuntimeException("getScope is not implemented"); // TODO
	}
	
	@Override
	public Producer<Ray> get(int index) { return normal; }
	
	public int size() { return 1; }

	@Override
	public boolean isEmpty() { return getPoint() == null; } // TODO  This isn't right

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
