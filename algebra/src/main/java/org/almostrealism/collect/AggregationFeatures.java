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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.computations.AggregatedProducerComputation;
import org.almostrealism.collect.computations.CollectionMaxComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.DynamicIndexProjectionProducerComputation;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.TraversableRepeatedProducerComputation;

import java.util.function.Function;

/**
 * Factory interface for aggregation and reduction operations on collections.
 * This interface provides methods for sum, mean, max, indexOfMax, variance, and related operations.
 *
 * @author Michael Murray
 * @see CollectionFeatures
 */
public interface AggregationFeatures extends ArithmeticFeatures, ExpressionFeatures {
	boolean enableIndexProjectionDeltaAlt = true;
	boolean enableUnaryWeightedSum = false;
	boolean enableSubdivide = enableUnaryWeightedSum;

	/**
	 * Finds the maximum element in a collection.
	 *
	 * @param input the collection to find the maximum element in
	 * @return a {@link CollectionProducerComputationBase} that generates the maximum value
	 */
	default CollectionProducerComputationBase max(Producer<PackedCollection> input) {
		DynamicIndexProjectionProducerComputation projection =
				new DynamicIndexProjectionProducerComputation("projectMax", shape(input).replace(shape(1)),
						(args, idx) -> args[2].getValueAt(idx).toInt(),
						true, input, indexOfMax(input));

		CollectionMaxComputation c = new CollectionMaxComputation(input);
		if (enableIndexProjectionDeltaAlt) c.setDeltaAlternate(projection);
		return c;
	}

	/**
	 * Creates a computation that finds the index of the maximum value in a collection.
	 *
	 * @param input The collection to find the maximum index in
	 * @return A computation that produces the index of the maximum element
	 */
	default CollectionProducerComputationBase indexOfMax(Producer<PackedCollection> input) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		return new TraversableRepeatedProducerComputation("indexOfMax", shape.replace(shape(1)), size,
				(args, index) -> e(0),
				(args, currentIndex) -> index ->
						conditional(args[1].getValueAt(kernel().multiply(size).add(index))
										.greaterThan(args[1].getValueAt(kernel().multiply(size).add(currentIndex))),
								index, currentIndex),
				input);
	}

	/**
	 * Computes the sum of all elements in a collection.
	 *
	 * @param input the collection to sum
	 * @return a {@link CollectionProducer} that generates a single-element collection containing the sum
	 */
	default CollectionProducer sum(Producer<PackedCollection> input) {
		TraversalPolicy targetShape = shape(input).replace(shape(1));

		if (Algebraic.isZero(input)) {
			return zeros(targetShape);
		}

		CollectionProducer result = null;

		boolean isWeightedSum = input instanceof WeightedSumComputation ||
				(input instanceof ReshapeProducer &&
						((ReshapeProducer) input).getComputation() instanceof WeightedSumComputation);
		boolean isAggregated = input instanceof AggregatedProducerComputation ||
				(input instanceof ReshapeProducer &&
						((ReshapeProducer) input).getComputation() instanceof AggregatedProducerComputation);

		if (enableSubdivide && shape(input).getSize() > KernelPreferences.getWorkSubdivisionMinimum()) {
			CollectionProducer sum = subdivide(input, this::sum);

			if (sum != null) {
				if (!shape(sum).equals(targetShape)) {
					result = sum.reshape(targetShape);
				} else {
					result = sum;
				}
			}
		}

		boolean tryUnaryWeighted = enableUnaryWeightedSum && !isWeightedSum && !isAggregated;

		if (result == null && tryUnaryWeighted &&
				shape(input).getSize() <= KernelPreferences.getWorkSubdivisionMinimum()) {
			TraversalPolicy shape = shape(input);

			TraversalPolicy resultShape = shape.replace(new TraversalPolicy(1));
			TraversalPolicy positions = padDimensions(resultShape, 1, shape.getDimensions(), true);
			TraversalPolicy groupShape = padDimensions(shape.item(), shape.getDimensions());
			result = new WeightedSumComputation(
					positions, positions, positions,
					groupShape, groupShape,
					input, c(1.0).reshape(shape)).reshape(resultShape);
		}

		if (result == null) {
			return new CollectionSumComputation(input);
		} else {
			return CollectionProducerComputationBase.assignDeltaAlternate(
					result, new CollectionSumComputation(input));
		}
	}

	/**
	 * Computes the arithmetic mean (average) of all elements in a collection.
	 *
	 * @param input the collection to compute the mean for
	 * @return a {@link CollectionProducer} that generates a single-element collection containing the mean
	 */
	default CollectionProducer mean(Producer<PackedCollection> input) {
		return sum(input).divide(c(shape(input).getSize()));
	}

	/**
	 * Subtracts the mean from each element in the collection.
	 *
	 * @param input the collection to center
	 * @return a {@link CollectionProducer} that generates the centered values
	 */
	default CollectionProducer subtractMean(Producer<PackedCollection> input) {
		CollectionProducer mean = mean(input);
		return subtract(input, mean);
	}

	/**
	 * Computes the variance of all elements in a collection.
	 *
	 * @param input the collection to compute the variance for
	 * @return a {@link CollectionProducer} that generates the variance
	 */
	default CollectionProducer variance(Producer<PackedCollection> input) {
		return mean(sq(subtractMean(input)));
	}

	default CollectionProducer subdivide(
			Producer<PackedCollection> input, Function<Producer<PackedCollection>, CollectionProducer> operation) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		int split = KernelPreferences.getWorkSubdivisionUnit();

		if (size > split) {
			while (split > 1) {
				CollectionProducer slice = subdivide(input, operation, split);
				if (slice != null) return slice;
				split /= 2;
			}
		}

		return null;
	}

	default CollectionProducer subdivide(
			Producer<PackedCollection> input, Function<Producer<PackedCollection>, CollectionProducer> operation, int sliceSize) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		if (size % sliceSize == 0) {
			TraversalPolicy split = shape.replace(shape(sliceSize, size / sliceSize)).traverse();
			CollectionProducer inner = operation.apply((Producer<PackedCollection>) reshape(split, input)).consolidate();
			return operation.apply(inner);
		}

		return null;
	}
}
