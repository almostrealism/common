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
import java.util.concurrent.Callable;

import io.almostrealism.code.Scope;
import org.almostrealism.algebra.*;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;

/**
 * Extends {@link Intersection} to provide metadata that is required for shading.
 * 
 * @author  Michael Murray
 */
public class ShadableIntersection extends Intersection implements ContinuousField {
	private Vector viewerDirection;
	private Callable<Ray> normal;
	
	public ShadableIntersection(Ray ray, Intersectable<ShadableIntersection, ?> surface, Scalar intersection) {
		super(ray, surface, intersection);
		
		Vector rayDirection = ray.getDirection();
		viewerDirection = (rayDirection.divide(rayDirection.length())).minus();
		
		normal = () -> {
			Vector p = ray.pointAt(intersection.getValue());
			return new Ray(p, ((Gradient) surface).getNormalAt(p).evaluate(new Object[0]));
		};
	}
	
	/** Returns the viewer direction. */
	@Override
	public VectorProducer getNormalAt(Vector point) { return new ImmutableVector(viewerDirection); }
	
	/** Delegates to {@link #getNormalAt(Vector)}. */
	@Override
	public Vector operate(Triple t) { return getNormalAt(new Vector(t.getA(), t.getB(), t.getC())).evaluate(new Object[0]); }

	@Override
	public Scope getScope(String prefix) {
		throw new RuntimeException("getScope is not implemented"); // TODO
	}
	
	@Override
	public Callable<Ray> get(int index) { return normal; }
	
	public Vector getViewerDirection() { return viewerDirection; }
	
	public int size() { return 1; }

	@Override
	public boolean isEmpty() { return getIntersection() == null; }

	@Override
	public boolean contains(Object o) { return false; }

	@Override
	public Iterator<Callable<Ray>> iterator() { return Arrays.asList(normal).iterator(); }

	@Override
	public Object[] toArray() { return new Object[] { normal }; }

	@Override
	public <T> T[] toArray(T[] a) { return Arrays.asList(normal).toArray(a); }

	@Override
	public boolean add(Callable<Ray> e) { return false; }

	@Override
	public boolean remove(Object o) { return false; }

	@Override
	public boolean containsAll(Collection<?> c) { return false; }

	@Override
	public boolean addAll(Collection<? extends Callable<Ray>> c) { return false; }

	@Override
	public boolean addAll(int index, Collection<? extends Callable<Ray>> c) { return false; }

	@Override
	public boolean removeAll(Collection<?> c) { return false; }

	@Override
	public boolean retainAll(Collection<?> c) { return false; }

	@Override
	public void clear() { }

	@Override
	public Callable<Ray> set(int index, Callable<Ray> element) { return null; }

	@Override
	public void add(int index, Callable<Ray> element) { }

	@Override
	public Callable<Ray> remove(int index) { return null; }

	@Override
	public int indexOf(Object o) { return 0; }

	@Override
	public int lastIndexOf(Object o) { return 0; }

	@Override
	public ListIterator<Callable<Ray>> listIterator() {
		return Arrays.asList(normal).listIterator();
	}

	@Override
	public ListIterator<Callable<Ray>> listIterator(int index) {
		return Arrays.asList(normal).listIterator();
	}

	@Override
	public List<Callable<Ray>> subList(int fromIndex, int toIndex) {
		return Arrays.asList(normal).subList(fromIndex, toIndex);
	}
}
