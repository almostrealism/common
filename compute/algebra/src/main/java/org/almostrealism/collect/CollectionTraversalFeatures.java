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
public interface CollectionTraversalFeatures extends ShapeFeatures {
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
	 *
	 * @param producer the collection producer to modify
	 * @return a Producer configured to traverse each element
	 */
	default Producer<PackedCollection> each(Producer<PackedCollection> producer) {
		return traverseEach(producer);
	}

	/**
	 * Configures a producer to traverse each individual element.
	 * This sets up the traversal policy to process every element independently.
	 *
	 * @param producer the collection producer to configure
	 * @return a Producer configured for element-wise traversal
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
	 * Aligns the traversal axes of a list of producers so they can be combined element-wise.
	 * Producers with mismatched shapes are traversed or repeated as needed before being
	 * passed to the processor function.
	 *
	 * @param <T>       the return type of the resulting producer
	 * @param <P>       the specific producer type returned by the processor
	 * @param producers the list of producers to align
	 * @param processor a function that receives the aligned shape and producer list and returns a combined producer
	 * @return a Producer for the result of applying the processor to the aligned producers
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
	 * Returns the shape with the largest total number of elements among all given producers.
	 *
	 * @param <PackedCollection> the collection type of the producers
	 * @param producers          the list of producers to compare
	 * @return the shape with the greatest total element count
	 */
	default <PackedCollection> TraversalPolicy largestTotalSize(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).max(Comparator.comparing(TraversalPolicy::getTotalSizeLong)).get();
	}

	/**
	 * Returns the lowest traversal count among all given producers.
	 *
	 * @param <PackedCollection> the collection type of the producers
	 * @param producers          the list of producers to compare
	 * @return the minimum count across all producers
	 */
	default <PackedCollection> long lowestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).min().getAsLong();
	}

	/**
	 * Returns the highest traversal count among all given producers.
	 *
	 * @param <PackedCollection> the collection type of the producers
	 * @param producers          the list of producers to compare
	 * @return the maximum count across all producers
	 */
	default <PackedCollection> long highestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).max().getAsLong();
	}

	/**
	 * Determines the output shape for an operation combining the given producers.
	 * Selects the shape with the largest total size, then adjusts the traversal axis
	 * so that the count matches the highest count across all inputs.
	 *
	 * @param <PackedCollection> the collection type of the producers
	 * @param producers          the input producers whose shapes determine the output shape
	 * @return the output traversal policy for the combined operation
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
