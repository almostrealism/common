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

/**
 * A marker for types that can be differentiated with respect to
 * a spatial {@link Vector} input, indicating the type has
 * continuous derivatives suitable for:
 * <ul>
 *   <li>Gradient-based optimization</li>
 *   <li>Surface normal computation</li>
 *   <li>Physical field calculations</li>
 *   <li>Implicit surface representations</li>
 * </ul>
 *
 * <p>
 * Implementations typically represent mathematical functions f: R^3 -> T
 * </p>
 *
 * @param <T>  the type of value produced by the function
 * @author  Michael Murray
 * @see Gradient
 * @see Vector
 */
public interface Differentiable<T> {

}
