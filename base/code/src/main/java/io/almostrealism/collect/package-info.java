/*
 * Copyright 2025 Michael Murray
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

/**
 * Lazy expression system for tensor traversal and collection-level operations.
 *
 * <p>This package provides the abstractions for describing element-wise operations on
 * multidimensional collections without eagerly materializing results. The core types are:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.collect.TraversalPolicy} — describes the shape, stride, and
 *       ordering of a multidimensional tensor</li>
 *   <li>{@link io.almostrealism.collect.CollectionExpression} — a lazy expression over a
 *       shaped collection, evaluated per-element via kernel indices</li>
 *   <li>{@link io.almostrealism.collect.TraversableExpression} — maps an index expression to
 *       a value expression at that position</li>
 *   <li>{@link io.almostrealism.collect.TraversalOrdering} — remaps flat indices to support
 *       non-default traversal orders</li>
 * </ul>
 *
 * <p>Specialized collection expressions include element-wise products, weighted sums, diagonal
 * matrices, conditionals, subset traversals, and delta (gradient) expressions.</p>
 */
package io.almostrealism.collect;
