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

import org.almostrealism.algebra.Vector;
import io.almostrealism.code.Operator;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Function;

import java.util.function.Supplier;

/**
 * @author  Michael Murray
 */
@Function
public interface Intersectable<T> extends Supplier<Operator<T>> {
	/**
	 * Returns a {@link ContinuousField} that represents the values for t that solve
	 * the vector equation p = o + t * d where p is a point of intersection of
	 * the specified ray and the surface.
	 */
	ContinuousField intersectAt(Producer<Ray> ray);

	/**
	 * If the evaluation of the {@link Operator} returned by {@link #get()}
	 * is equal to the evaluation of this {@link Operator}, the {@link Vector}
	 * is an intersection point for this {@link Intersectable}.
	 */
	Operator<T> expect();
}
