/*
 * Copyright 2022 Michael Murray
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

/**
 * Represents an object that supports axis-based traversal. Traversal determines
 * how elements are iterated or accessed when processing multi-dimensional data.
 *
 * <p>The traversal axis controls which dimensions are treated as the iteration
 * dimension versus the data dimension. For example:</p>
 * <ul>
 *   <li>Axis 0: Iterate over the entire collection as a single unit</li>
 *   <li>Axis 1: Iterate over major slices (e.g., rows in a 2D array)</li>
 *   <li>Axis N: Iterate over individual elements</li>
 * </ul>
 *
 * <p>This interface is typically implemented by shaped collections and expressions
 * to support flexible iteration patterns during computation.</p>
 *
 * @param <T> the type returned by the traverse operation (typically self-referential)
 *
 * @see Shape
 * @see TraversalPolicy
 */
public interface Traversable<T> {

	/**
	 * Returns a view of this object configured to traverse along the specified axis.
	 * The axis determines the granularity of iteration: lower axes represent coarser
	 * traversal (fewer, larger chunks), while higher axes represent finer traversal
	 * (more, smaller chunks).
	 *
	 * @param axis the axis along which to traverse; 0 represents the outermost
	 *             dimension, with higher values representing inner dimensions
	 * @return a traversable view configured for the specified axis
	 */
	T traverse(int axis);
}
