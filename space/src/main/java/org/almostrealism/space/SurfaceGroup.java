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

package org.almostrealism.space;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.almostrealism.relation.NodeGroup;
import org.almostrealism.algebra.Gradient;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.Intersectable;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.ClosestIntersection;
import io.almostrealism.code.Constant;
import io.almostrealism.relation.Producer;
import io.almostrealism.code.Operator;

/**
 * A {@link SurfaceGroup} object allows {@link ShadableSurface} objects to be grouped together.
 * The properties of the {@link SurfaceGroup} object are applied to each of its children.
 * 
 * @author  Michael Murray
 */
public class SurfaceGroup<T extends ShadableSurface> extends AbstractSurface implements NodeGroup<T>, Iterable<T> {
	private ArrayList<T> surfaces;

	/** Constructs a {@link SurfaceGroup} object with no {@link Gradient} objects. */
	public SurfaceGroup() {
		surfaces = new ArrayList<T>();
		setColor(new RGB(1.0, 1.0, 1.0));
	}
	
	/** Constructs a {@link SurfaceGroup} using the {@link Gradient}s in the specified array. */
	public SurfaceGroup(T surfaces[]) {
		this();
		this.setSurfaces(surfaces);
	}
	
	/**
	 * Replaces all of the {@link Gradient} objects of this {@link SurfaceGroup} object with
	 * those represented by the specified {@link Gradient} array.
	 */
	public void setSurfaces(T surfaces[]) {
		this.surfaces.clear();
		
		for (int i = 0; i < surfaces.length; i++)
			this.addSurface(surfaces[i]);
	}
	
	/**
	 * Adds the specified {@link Gradient} object to this {@link SurfaceGroup} object and
	 * sets its parent to this {@link SurfaceGroup} object (if it is an instance of
	 * {@link AbstractSurface}).
	 */
	public void addSurface(T surface) {
		if (surface instanceof AbstractSurface)
			((AbstractSurface) surface).setParent(this);
//		else if (surface instanceof AbstractSurfaceUI)
//			((AbstractSurfaceUI)surface).setParent(this);
		
		this.surfaces.add(surface);
	}
	
	/**
	 * Removes the Surface object stored at the specified index from this SurfaceGroup object
	 * and sets the parent of the removed Surface object to null (if it is an instance of AbstractSurface).
	 */
	public void removeSurface(int index) {
		if (this.surfaces.get(index) instanceof AbstractSurface)
			((AbstractSurface) this.surfaces.get(index)).setParent(null);
		
		this.surfaces.remove(index);
	}
	
	/**
	 * Returns the {@link ShadableSurface} objects stored by this {@link SurfaceGroup} object as
	 * a {@link ShadableSurface} array.
	 */
	@Deprecated
	public ShadableSurface[] getSurfaces() {
		System.out.println("Call to deprecated getSurfaces method");
		return this.surfaces.toArray(new ShadableSurface[0]);
	}
	
	/**
	 * Returns the {@link ShadableSurface} object stored by this {@link SurfaceGroup} object at
	 * the specified index.
	 */
	@Deprecated
	public ShadableSurface getSurface(int index) {
		System.out.println("Call to deprecated getSurface method");
		return (ShadableSurface) this.surfaces.get(index);
	}

	@Override
	public void forEach(Consumer<? super T> consumer) {
		surfaces.forEach(consumer);
	}

	@Override
	public Stream<T> children() { return surfaces.stream(); }

	public Iterator<T> iterator() { return surfaces.iterator(); }
	
	/** {@link ShadableSurface#shade(ShaderContext)} */
	@Override
	public Producer<RGB> shade(ShaderContext p) {
		Producer<RGB> color = null;
		
		if (getShaderSet() != null) {
			color = getShaderSet().shade(p, p.getIntersection());
		}
		
		if (getParent() != null) {
			if (color == null) {
				color = getParent().shade(p);
			} else {
				final Producer<RGB> fc = color;
				color = add(fc, getParent().shade(p));
			}
		}
		
		return color;
	}
	
	/** Returns null. */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) { return null; } // TODO?

	@Override
	public Mesh triangulate() {
		Mesh mesh = super.triangulate();
		
		i: for (Gradient s : this) {
			if (s instanceof TriangulatableGeometry == false) continue i;
			
			Mesh m = ((TriangulatableGeometry) s).triangulate();
			
			Vector v[] = m.getVectors();
			Triangle t[] = m.getTriangles();
			
			int index[] = new int[v.length];
			for (int j = 0; j < index.length; j++) {
				index[j] = mesh.addVector(m.getTransform(true).transformAsLocation(v[j]));
			}
			
			for (int j = 0; j < t.length; j++) {
				Vector tv[] = t[j].getVertices();
				
				int v0 = index[m.indexOf(tv[0])];
				int v1 = index[m.indexOf(tv[1])];
				int v2 = index[m.indexOf(tv[2])];
				
				mesh.addTriangle(v0, v1, v2);
			}
		}
		
		return mesh;
	}

	/**
	 * Returns an {@link ContinuousField} that represents the ray-surface intersections
	 * for the AbstractSurface object which is intersected closest to the origin of the ray
	 * (>= 0). If there is no intersection >= 0 along the ray, null is returned.
	 */
	@Override
	public ContinuousField intersectAt(Producer ray) {
		TransformMatrix m = getTransform(true);
		if (m != null) ray = m.getInverse().transform(ray);
		List<Intersectable> l = new ArrayList<>();
		l.addAll(surfaces);
		return new ClosestIntersection(ray, l);
	}

	@Override
	public Operator<Scalar> get() {
		// TODO  Aggregate the operators for each surface some how?
		return null;
	}

	@Override
	public Operator<Scalar> expect() {
		// TODO  This isn't right
		return new Constant<>(new Scalar(0));
	}
}
