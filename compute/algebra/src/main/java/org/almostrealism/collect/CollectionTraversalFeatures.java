/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.ReshapeProducer;

import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Factory interface for traversal and reshape operations on collections.
 * This interface provides methods for changing the traversal axis, reshaping
 * collections, and aligning traversal axes across multiple producers.
 *
 * @author Michael Murray
 * @see TraversalPolicy
 * @see CollectionFeatures
 */
public interface CollectionTraversalFeatures extends TraversalPolicyFeatures {
	/** When true, repeat operations are permitted even for producers with variable (non-fixed) counts. */
	boolean enableVariableRepeat = false;

	/**
	 * Changes the traversal axis of a collection producer.
	 * This operation modifies how the collection is traversed during computation
	 * without changing the underlying data layout.
	 *
	 * @param axis the new traversal axis (0-based index)
	 * @param producer the collection producer to modify
	 * @return a CollectionProducer with the specified traversal axis
	 * 
	 *
	 * <pre>{@code
	 * // Create a 2D collection and change traversal axis
	 * CollectionProducer matrix = c(shape(3, 4),
	 *     1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
	 * 
	 * // Traverse along axis 0 (rows)
	 * CollectionProducer rowTraversal = traverse(0, matrix);
	 * // Changes how iteration occurs over the matrix
	 * 
	 * // Traverse along axis 1 (columns)
	 * CollectionProducer colTraversal = traverse(1, matrix);
	 * // Different traversal pattern for the same data
	 * }</pre>
	 */
	default CollectionProducer traverse(int axis, Producer<PackedCollection> producer) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).traverse(axis);
		} else if (producer instanceof CollectionProducerComputation) {
			return ((CollectionProducerComputation) producer).traverse(axis);
		}

		return new ReshapeProducer(axis, producer);
	}

	/**
	 * Alias for {@link #traverseEach} - sets up the producer to traverse each element.
	 * This is a convenience method that makes collection operations more readable.
	 *
	 * @param producer the collection producer to modify
	 * @return a Producer configured to traverse each element
	 * 
	 *
	 * <pre>{@code
	 * // Set up element-wise traversal
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * Producer eachElement = each(vector);
	 * // Result: Producer configured for element-wise operations
	 * }</pre>
	 */
	default Producer<PackedCollection> each(Producer<PackedCollection> producer) {
		return traverseEach(producer);
	}

	/**
	 * Configures a producer to traverse each individual element.
	 * This sets up the traversal policy to process every element independently,
	 * which is useful for element-wise operations and transformations.
	 *
	 * @param producer the collection producer to configure
	 * @return a Producer configured for element-wise traversal
	 * 
	 *
	 * <pre>{@code
	 * // Configure for element-wise processing
	 * CollectionProducer matrix = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * Producer elementWise = traverseEach(matrix);
	 * // Result: Producer that can process each of the 6 elements individually
	 * 
	 * // Useful for applying functions to each element
	 * CollectionProducer vector = c(1.0, 4.0, 9.0);
	 * Producer sqrt = traverseEach(vector).sqrt(); // hypothetical sqrt operation
	 * // Would apply sqrt to each element: [1.0, 2.0, 3.0]
	 * }</pre>
	 */
	default Producer traverseEach(Producer<PackedCollection> producer) {
		return reshape(((Shape) producer).getShape().traverseEach(), producer);
	}

	/**
	 * Reshapes a collection producer to have a new shape.
	 * This operation changes the dimensional structure of the collection
	 * while preserving the total number of elements.
	 *
	 * @param shape the new shape for the collection
	 * @param producer the collection producer to reshape
	 * @return a Producer with the new shape
	 * @throws IllegalArgumentException if the new shape has a different total size
	 * 
	 *
	 * <pre>{@code
	 * // Reshape a 1D vector to a 2D matrix
	 * CollectionProducer vector = c(1, 2, 3, 4, 5, 6);
	 * Producer<PackedCollection> matrix = reshape(shape(2, 3), vector);
	 * // Result: 2x3 matrix [[1,2,3], [4,5,6]]
	 * 
	 * // Reshape a matrix to a different matrix
	 * CollectionProducer matrix2x3 = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * Producer<PackedCollection> matrix3x2 = reshape(shape(3, 2), matrix2x3);
	 * // Result: 3x2 matrix [[1,2], [3,4], [5,6]]
	 * 
	 * // Flatten a multi-dimensional array
	 * CollectionProducer tensor = c(shape(2, 2, 2), 1, 2, 3, 4, 5, 6, 7, 8);
	 * Producer<PackedCollection> flattened = reshape(shape(8), tensor);
	 * // Result: 1D vector [1, 2, 3, 4, 5, 6, 7, 8]
	 * }</pre>
	 */
	default Producer reshape(TraversalPolicy shape, Producer producer) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).reshape(shape);
		} else if (producer instanceof CollectionProducerComputation) {
			return ((CollectionProducerComputation) producer).reshape(shape);
		}

		return new ReshapeProducer(shape, producer);
	}

	/**
	 * Aligns the traversal axes of a list of collection producers so they share a common
	 * traversal structure, then applies the given processor to produce a combined result.
	 * Producers with fewer axes are traversed or repeated as needed to match the highest
	 * traversal depth in the list.
	 *
	 * @param <T> unused type parameter retained for compatibility
	 * @param <P> the type of {@link Producer} returned by the processor
	 * @param producers the list of collection producers whose axes should be aligned
	 * @param processor a function that receives the aligned shape and aligned producers
	 *                  and produces the combined output
	 * @return a {@link Producer} over the aligned and processed collection
	 */
	default <T, P extends Producer<PackedCollection>> Producer<PackedCollection> alignTraversalAxes(
			List<Producer<PackedCollection>> producers, BiFunction<TraversalPolicy, List<Producer<PackedCollection>>, P> processor) {
		return TraversalPolicy
				.alignTraversalAxes(
						producers.stream().map(this::shape).collect(Collectors.toList()),
						producers,
						(i, p) -> traverse(i, p),
						(i, p) -> {
							if (enableVariableRepeat || Countable.isFixedCount(p)) {
								return CollectionFeatures.getInstance().repeat(i, p);
							} else {
								return p;
							}
						},
						processor);
	}

	/**
	 * Returns the {@link TraversalPolicy} with the largest total element count among the given producers.
	 * This is used to determine the dominant shape when aligning producers with differing sizes.
	 *
	 * @param <PackedCollection> the collection element type (type variable, not the class)
	 * @param producers the list of producers whose shapes are compared
	 * @return the {@link TraversalPolicy} with the greatest total size
	 */
	default <PackedCollection> TraversalPolicy largestTotalSize(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).max(Comparator.comparing(TraversalPolicy::getTotalSizeLong)).get();
	}

	/**
	 * Returns the smallest traversal count (number of top-level items) among all given producers.
	 * The count is determined by the leading dimension of each producer's {@link TraversalPolicy}.
	 *
	 * @param <PackedCollection> the collection element type (type variable, not the class)
	 * @param producers the list of producers whose traversal counts are compared
	 * @return the minimum count across all producer shapes
	 */
	default <PackedCollection> long lowestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).min().getAsLong();
	}

	/**
	 * Returns the largest traversal count (number of top-level items) among all given producers.
	 * The count is determined by the leading dimension of each producer's {@link TraversalPolicy}.
	 *
	 * @param <PackedCollection> the collection element type (type variable, not the class)
	 * @param producers the list of producers whose traversal counts are compared
	 * @return the maximum count across all producer shapes
	 */
	default <PackedCollection> long highestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).max().getAsLong();
	}

	/**
	 * Computes the output {@link TraversalPolicy} for a set of producers by selecting the shape
	 * with the largest total size, then adjusting the traversal axis so the leading count matches
	 * the highest count across all producers.
	 *
	 * @param <PackedCollection> the collection element type (type variable, not the class)
	 * @param producers the input producers whose output shape is to be determined
	 * @return the {@link TraversalPolicy} that describes the output shape of a combined operation
	 */
	default <PackedCollection> TraversalPolicy outputShape(Producer<PackedCollection>... producers) {
		TraversalPolicy result = largestTotalSize(List.of(producers));

		long count = highestCount(List.of(producers));

		if (count != result.getCountLong()) {
			for (int i = 0; i <= result.getDimensions(); i++) {
				if (result.traverse(i).getCountLong() == count) {
					return result.traverse(i);
				}
			}
		}

		return result;
	}
}
