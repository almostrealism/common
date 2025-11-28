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
import org.almostrealism.collect.PackedCollection;

import org.almostrealism.algebra.Vector;
import io.almostrealism.code.Operator;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Function;

import java.util.function.Supplier;

/**
 * An interface for objects that can be tested for ray intersections.
 * Implementations provide methods to compute intersection points between
 * a ray and the surface geometry.
 *
 * <p>The interface is parameterized by type {@code T}, which represents
 * the type of value used to determine if an intersection occurred
 * (typically {@link org.almostrealism.collect.PackedCollection} for distance values).</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * Intersectable<PackedCollection> surface = getSurface();
 * ContinuousField intersections = surface.intersectAt(ray);
 * Producer<Ray> hitNormal = intersections.get(0);
 * }</pre>
 *
 * @param <T> the type of value used for intersection testing
 * @author  Michael Murray
 * @see ContinuousField
 * @see Ray
 */
@Function
public interface Intersectable<T> extends Supplier<Operator<T>> {
	/**
	 * Computes the intersection points between the specified ray and this surface.
	 *
	 * <p>Returns a {@link ContinuousField} that represents the values for t that solve
	 * the vector equation {@code p = o + t * d} where p is a point of intersection of
	 * the specified ray and the surface.</p>
	 *
	 * @param ray the ray to test for intersection, containing origin and direction
	 * @return a {@link ContinuousField} containing intersection data (position and normal),
	 *         or an empty field if no intersection occurs
	 */
	ContinuousField intersectAt(Producer<Ray> ray);

	/**
	 * Returns an {@link Operator} representing the expected value for a valid intersection.
	 *
	 * <p>If the evaluation of the {@link Operator} returned by {@link #get()}
	 * is equal to the evaluation of this {@link Operator}, the tested point
	 * is an intersection point for this {@link Intersectable}.</p>
	 *
	 * @return an operator representing the expected intersection value
	 */
	Operator<T> expect();
}
