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

package org.almostrealism.algebra;

import io.almostrealism.relation.Function;

/**
 * A function from {@link Vector} positions to values that can be differentiated.
 *
 * <p>
 * {@link Differentiable} marks a {@link Function} as being differentiable with respect to
 * its spatial {@link Vector} input. This is a marker interface that indicates the function
 * has continuous derivatives, making it suitable for:
 * </p>
 * <ul>
 *   <li>Gradient-based optimization</li>
 *   <li>Surface normal computation</li>
 *   <li>Physical field calculations</li>
 *   <li>Implicit surface representations</li>
 * </ul>
 *
 * <p>
 * Implementations typically represent mathematical functions f: R^3 -> T where T is often
 * {@link Scalar} for scalar fields or other types for more complex field representations.
 * </p>
 *
 * @param <T>  the type of value produced by the function (often {@link Scalar})
 * @author  Michael Murray
 * @see Gradient
 * @see Function
 * @see Vector
 */
public interface Differentiable<T> extends Function<Vector, T> {

}
