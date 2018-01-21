/*
 * Copyright 2017 Michael Murray
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;

import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.DiscreteField;
import org.almostrealism.algebra.Intersectable;
import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.Ray;
import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.Vector;

/**
 * Extends {@link Intersection} to provide metadata that is required for shading.
 * 
 * @author  Michael Murray
 */
public class ShadableIntersection extends Intersection implements ContinuousField {
	private int nearestIndex = 0;
	
	private Vector viewerDirection;
	private List<Callable<Ray>> normals;
	
	public ShadableIntersection(Ray ray, Intersectable<ShadableIntersection> surface, double intersections[]) {
		super(ray, surface, intersections);
		
		Vector rayDirection = ray.getDirection();
		viewerDirection = (rayDirection.divide(rayDirection.length())).minus();
		
		normals = new ArrayList<Callable<Ray>>();
		
		if (surface instanceof Gradient) {
			for (int i = 0; i < intersections.length; i++) {
				final int index = i;
				
				normals.add(() -> {
					Vector p = ray.pointAt(intersections[index]);
					return new Ray(p, ((Gradient) surface).getNormalAt(p));
				});
				
				if (intersections[i] >= 0 && intersections[i] < intersections[nearestIndex]) {
					nearestIndex = i;
				}
			}
		}
	}
	
	/** Returns the viewer direction. */
	@Override
	public Vector getNormalAt(Vector point) { return viewerDirection; }
	
	/** Delegates to {@link #getNormalAt(Vector)}. */
	@Override
	public Vector operate(Triple t) { return getNormalAt(new Vector(t.getA(), t.getB(), t.getC())); }
	
	@Override
	public Callable<Ray> get(int index) {
		return normals.get(index);
	}
	
	public Vector getViewerDirection() { return viewerDirection; }
	
	public int size() { return normals.size(); }

	@Override
	public boolean isEmpty() { return normals.isEmpty(); }

	@Override
	public boolean contains(Object o) { return false; }

	@Override
	public Iterator<Callable<Ray>> iterator() { return normals.iterator(); }

	@Override
	public Object[] toArray() { return normals.toArray(); }

	@Override
	public <T> T[] toArray(T[] a) { return normals.toArray(a); }

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
		return normals.listIterator();
	}

	@Override
	public ListIterator<Callable<Ray>> listIterator(int index) {
		return normals.listIterator();
	}

	@Override
	public List<Callable<Ray>> subList(int fromIndex, int toIndex) {
		return normals.subList(fromIndex, toIndex);
	}
}
