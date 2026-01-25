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

	default <T, P extends Producer<PackedCollection>> Producer<PackedCollection> alignTraversalAxes(
			List<Producer<PackedCollection>> producers, BiFunction<TraversalPolicy, List<Producer<PackedCollection>>, P> processor) {
		return TraversalPolicy
				.alignTraversalAxes(
						producers.stream().map(this::shape).collect(Collectors.toList()),
						producers,
						(i, p) -> traverse(i, p),
						(i, p) -> {
							if (enableVariableRepeat || Countable.isFixedCount(p)) {
								return repeat(i, p);
							} else {
								return p;
							}
						},
						processor);
	}

	default <PackedCollection> TraversalPolicy largestTotalSize(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).max(Comparator.comparing(TraversalPolicy::getTotalSizeLong)).get();
	}

	default <PackedCollection> long lowestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).min().getAsLong();
	}

	default <PackedCollection> long highestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).max().getAsLong();
	}

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

	// Required for internal use, to be overridden by CollectionFeatures
	CollectionProducerComputation repeat(int repeat, Producer<?> collection);
}
