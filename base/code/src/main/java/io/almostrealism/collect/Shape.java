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

import io.almostrealism.expression.Expression;
import org.almostrealism.io.Describable;

import java.util.stream.IntStream;

/**
 * Represents an object that has a defined multi-dimensional shape defined by a
 * {@link TraversalPolicy}. This interface provides methods for reshaping, flattening,
 * and traversing collections along different axes.
 *
 * <p>{@code Shape} extends {@link Traversable} to support axis-based traversal,
 * {@link IndexSet} for index containment checking, and {@link Describable} for
 * human-readable descriptions.</p>
 *
 * <p>Common operations include:</p>
 * <ul>
 *   <li>{@link #reshape(int...)} - Change dimensions while preserving total element count</li>
 *   <li>{@link #flatten()} - Convert to a single dimension</li>
 *   <li>{@link #traverse(int)} - Change the traversal axis for iteration</li>
 *   <li>{@link #each()} - Traverse each individual element</li>
 * </ul>
 *
 * @param <T> the type returned by shape transformation operations
 *
 * @see TraversalPolicy
 * @see Traversable
 * @see Collection
 */
public interface Shape<T> extends Traversable<T>, IndexSet, Describable {

	/**
	 * Returns the {@link TraversalPolicy} that defines the multi-dimensional shape
	 * and traversal behavior of this object.
	 *
	 * @return the shape policy for this object
	 */
	TraversalPolicy getShape();

	/**
	 * Reshapes this object to the specified dimensions. The total number of elements
	 * must remain the same. A dimension value of -1 indicates that dimension should
	 * be inferred from the total size and other dimensions.
	 *
	 * @param dims the new dimensions; at most one dimension may be -1 (inferred)
	 * @return a reshaped version of this object
	 * @throws IllegalArgumentException if a dimension is 0, or if more than one
	 *                                  dimension is negative (to be inferred)
	 */
	default T reshape(int... dims) {
		if (IntStream.range(0, dims.length).filter(i -> dims[i] == 0).findAny().isPresent())
			throw new IllegalArgumentException("Cannot reshape with a dimension of length 0");

		int inf[] = IntStream.range(0, dims.length).filter(i -> dims[i] < 0).toArray();
		if (inf.length > 1) throw new IllegalArgumentException("Only one dimension can be inferred");
		if (inf.length == 1) {
			TraversalPolicy shape = getShape();
			long tot = shape.getTotalSizeLong();
			int known = IntStream.of(dims).filter(i -> i >= 0).reduce(1, (a, b) -> a * b);
			return reshape(new TraversalPolicy(IntStream.range(0, dims.length)
					.mapToLong(i -> i == inf[0] ? tot / known : dims[i]).toArray()));
		}

		return reshape(new TraversalPolicy(dims));
	}

	/**
	 * Reshapes this object to match the specified {@link TraversalPolicy}.
	 *
	 * @param shape the new shape policy to apply
	 * @return a reshaped version of this object
	 */
	T reshape(TraversalPolicy shape);

	/**
	 * Flattens this object to a single dimension containing all elements.
	 *
	 * @return a flattened version of this object with shape [totalSize]
	 */
	default T flatten() {
		return reshape(getShape().getTotalSize());
	}

	/**
	 * Traverses each individual element. Equivalent to {@link #traverseEach()}.
	 *
	 * @return this object configured to traverse each element
	 */
	default T each() {
		return traverseEach();
	}

	/**
	 * Configures traversal to process all elements along axis 0.
	 *
	 * @return this object configured for traversal at axis 0
	 */
	default T traverseAll() {
		return traverse(0);
	}

	/**
	 * Configures traversal to process each individual element by setting
	 * the traversal axis to the total number of dimensions.
	 *
	 * @return this object configured for element-wise traversal
	 */
	default T traverseEach() {
		return traverse(getShape().getDimensions());
	}

	/**
	 * Consolidates traversal by moving to the previous axis (current axis - 1).
	 *
	 * @return this object with traversal consolidated to a lower axis
	 */
	default T consolidate() { return traverse(getShape().getTraversalAxis() - 1); }

	/**
	 * Advances traversal to the next axis (current axis + 1).
	 *
	 * @return this object with traversal advanced to a higher axis
	 */
	default T traverse() { return traverse(getShape().getTraversalAxis() + 1); }

	/**
	 * Checks whether the given index is contained within the valid index range
	 * for this shape's ordering.
	 *
	 * @param index the index expression to check
	 * @return a boolean expression that is true if the index is valid
	 */
	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		return getShape().getOrder().containsIndex(index);
	}

	/**
	 * Returns a detailed string description of this object's shape.
	 *
	 * @return a string representation of the shape details
	 */
	@Override
	default String describe() {
		return getShape().toStringDetail();
	}
}
