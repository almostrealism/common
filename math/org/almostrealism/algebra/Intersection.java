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

package org.almostrealism.algebra;

import java.util.ArrayList;

import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.PathElement;

/**
 * An Intersection object stores data for the intersections between a ray and a surface.
 */
public class Intersection implements PathElement<Intersection> {
	/** A very small value (0.00000001) that is used in '>=' and '<=' operations to account for computational errors. */
	public static final double e = 0.00000001;

	private Intersectable surface;
	private Ray ray;
	private Scalar intersection;
	
	private Vector point;
	
	private ArrayList<PathElement<Intersection>> children;

	/**
	 * Constructs a new Intersection object that represents an intersection between the specified
	 * Ray and Surface objects at the specified points along the ray represented by the Ray object.
	 */
	public Intersection(Ray ray, Intersectable surface, Scalar intersection) {
		this.ray = ray;
		this.surface = surface;

		this.intersection = intersection;
		
		this.point = ray.pointAt(intersection.getValue());
	}
	
	/** @return  The Ray object stored by this Intersection object. */
	public Ray getRay() { return this.ray; }
	
	/** @return  The Surface object stored by this Intersection object. */
	public Intersectable getSurface() { return this.surface; }
	
	/** @return  The intersection stored by this Intersection object. */
	public Scalar getIntersection() { return this.intersection; }
	
	public Vector getPoint() { return point; }
	
	public void add(Intersection inter) {
		if (children == null) children = new ArrayList<PathElement<Intersection>>();
		children.add(inter);
	}
	
	@Override
	public Iterable<PathElement<Intersection>> next() { return children; }

	/**
	 * @return  A String representation of this Intersection object.
	 */
	public String toString() {
		return "[" + getIntersection() + "]";
	}
}
