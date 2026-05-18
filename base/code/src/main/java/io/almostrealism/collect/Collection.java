/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.collect;

import io.almostrealism.uml.Multiple;

/**
 * A core interface representing a shaped collection of elements. This interface
 * combines the multi-dimensional shape capabilities of {@link Shape} with the
 * element multiplicity semantics of {@link Multiple}.
 *
 * <p>{@code Collection} is the fundamental type for representing arrays, tensors,
 * and other multi-dimensional data structures in the computation framework.
 * It provides:</p>
 * <ul>
 *   <li>Shape information and transformation operations (from {@link Shape})</li>
 *   <li>Multiple element semantics (from {@link Multiple})</li>
 * </ul>
 *
 * <p>Implementations typically represent packed collections of numeric data
 * (usually Double values) that can be reshaped, traversed, and operated upon
 * in a hardware-accelerated manner.</p>
 *
 * @param <T> the element type contained in the collection (typically Double)
 * @param <S> the self-referential type for fluent shape transformation methods
 *
 * @see Shape
 * @see Multiple
 * @see CollectionVariable
 */
public interface Collection<T, S> extends Shape<S>, Multiple<T> {
}
