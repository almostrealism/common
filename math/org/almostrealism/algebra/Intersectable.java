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

import org.almostrealism.geometry.Ray;
import org.almostrealism.relation.Operator;
import org.almostrealism.uml.Function;
import org.almostrealism.util.Producer;

import java.util.concurrent.Future;

/**
 * @author  Michael Murray
 */
@Function
public interface Intersectable<I extends Intersection, T> extends Future<Operator<T>> {
	/** Returns true if the ray intersects the 3d surface in real space. */
	boolean intersect(Ray ray);
	
	/**
	 * Returns an Intersection object that represents the values for t that solve
	 * the vector equation p = o + t * d where p is a point of intersection of
	 * the specified ray and the surface.
	 */
	Producer<I> intersectAt(Producer<Ray> ray); // TODO  Specify <Ray> as generic argument to producer

	/**
	 * If the evaluation of the {@link Operator} returned by {@link #get()}
	 * is equal to the evaluation of this {@link Operator}, the {@link Vector}
	 * is an intersection point for this {@link Intersectable}.
	 */
	Operator<T> expect();
}
