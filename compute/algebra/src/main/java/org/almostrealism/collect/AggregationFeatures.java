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
	/** When true, uses an index-projection computation as the delta alternate for max operations. */
	boolean enableIndexProjectionDeltaAlt = true;

	/** When true, enables a unary (single-input) form of the weighted sum computation. */
	boolean enableUnaryWeightedSum = false;

	/** When true, enables subdivision of inputs, tied to {@link #enableUnaryWeightedSum}. */
	boolean enableSubdivide = enableUnaryWeightedSum;

	/**
	 * Finds the maximum element in a collection.
	 * This reduction operation scans through all elements and returns
	 * the largest value as a single-element collection.
	 *
	 * @param input the collection to find the maximum element in
	 * @return a {@link CollectionProducerComputationBase} that generates the maximum value
	 * 
	 *
	 * <pre>{@code
	 * // Find maximum in a vector
	 * CollectionProducer values = c(3.0, 7.0, 2.0, 9.0, 5.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> maximum = max(values);
	 * // Result: Producer that generates [9.0]
	 * 
	 * // Find maximum in a matrix (flattened)
	 * CollectionProducer matrix = c(shape(2, 3), 1.0, 8.0, 3.0, 4.0, 2.0, 6.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> matrixMax = max(matrix);
	 * // Result: Producer that generates [8.0] (maximum across all elements)
	 * 
	 * // Maximum of negative numbers
	 * CollectionProducer negatives = c(-5.0, -2.0, -8.0, -1.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negMax = max(negatives);
	 * // Result: Producer that generates [-1.0] (least negative = maximum)
	 * }</pre>
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
	 * <p>
	 * This method uses {@link TraversableRepeatedProducerComputation} to identify the index.
	 * 
	 * <p>The computation works by:
	 * <ul>
	 *   <li>Initializing with index 0 as the current maximum location</li>
	 *   <li>Iterating through all elements comparing values</li>
	 *   <li>Updating the stored index when a larger value is found</li>
	 * </ul>
	 * 
	 * @param input The collection to find the maximum index in
	 *
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
	 * This reduction operation adds up all elements to produce a single scalar result.
	 * It's one of the most common aggregation operations in numerical computing.
	 * 
	 * @param input the collection to sum
	 * @return a {@link CollectionProducerComputation} that generates a single-element collection containing the sum
	 * 
	 *
	 * <pre>{@code
	 * // Sum all elements in a vector
	 * CollectionProducer vector = c(1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer total = sum(vector);
	 * // Result: Producer that generates [10.0] (1+2+3+4)
	 * 
	 * // Sum elements in a matrix (flattened)
	 * CollectionProducer matrix = c(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer matrixSum = sum(matrix);
	 * // Result: Producer that generates [10.0] (1+2+3+4)
	 * 
	 * // Sum of zeros returns zero
	 * CollectionProducer zeros = zeros(shape(5));
	 * CollectionProducer zeroSum = sum(zeros);
	 * // Result: Producer that generates [0.0]
	 * }</pre>
	 */
	default CollectionProducer sum(Producer<PackedCollection> input) {
		TraversalPolicy targetShape = shape(input).replace(shape(1));

		if (Algebraic.isZero(input)) {
			// Mathematical optimization: sum(zeros) = 0
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
	 * This is calculated as the sum of all elements divided by the number of elements.
	 * 
	 *
	 * @param input the collection to compute the mean for
	 * @return a {@link CollectionProducer} that generates a single-element collection containing the mean
	 * 
	 *
	 * <pre>{@code
	 * // Calculate mean of a vector
	 * CollectionProducer vector = c(2.0, 4.0, 6.0, 8.0);
	 * CollectionProducer average = mean(vector);
	 * // Result: Producer that generates [5.0] ((2+4+6+8)/4)
	 * 
	 * // Mean of a single element
	 * CollectionProducer single = c(42.0);
	 * CollectionProducer singleMean = mean(single);
	 * // Result: Producer that generates [42.0] (42/1)
	 * 
	 * // Mean of mixed positive/negative values
	 * CollectionProducer mixed = c(-2.0, 0.0, 2.0);
	 * CollectionProducer mixedMean = mean(mixed);
	 * // Result: Producer that generates [0.0] ((-2+0+2)/3)
	 * }</pre>
	 */
	default CollectionProducer mean(Producer<PackedCollection> input) {
		return sum(input).divide(c(shape(input).getSize()));
	}

	/**
	 * Subtracts the mean of a collection from each of its elements (mean-centering).
	 * The mean is computed over all elements and broadcast back for subtraction.
	 *
	 * @param input the collection producer whose elements are mean-centered
	 * @return a {@link CollectionProducer} containing the mean-centered values
	 */
	default CollectionProducer subtractMean(Producer<PackedCollection> input) {
		CollectionProducer mean = mean(input);
		return subtract(input, mean);
	}

	/**
	 * Computes the variance of a collection as the mean of the squared deviations from
	 * the collection's mean. Equivalent to {@code mean(sq(subtractMean(input)))}.
	 *
	 * @param input the collection producer to compute variance over
	 * @return a {@link CollectionProducer} producing the scalar variance value
	 */
	default CollectionProducer variance(Producer<PackedCollection> input) {
		return mean(sq(subtractMean(input)));
	}

	/**
	 * Attempts to subdivide a large collection producer into smaller slices and apply an
	 * operation to each slice, then combine the results. The slice size starts at the
	 * kernel work-subdivision unit and halves until a valid subdivision is found or
	 * no subdivision is possible. Returns {@code null} if the collection cannot be
	 * subdivided (e.g. its size is below the threshold).
	 *
	 * @param input     the large collection producer to subdivide
	 * @param operation a function to apply to each slice of the subdivided collection
	 * @return a {@link CollectionProducer} over the combined result, or {@code null}
	 *         if no valid subdivision exists
	 */
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

	/**
	 * Subdivides a collection producer into slices of the given size and applies an operation
	 * to the resulting traversal, consolidating the intermediate result before applying the
	 * operation a second time. Returns {@code null} if the collection's total size is not
	 * evenly divisible by {@code sliceSize}.
	 *
	 * @param input     the collection producer to subdivide
	 * @param operation a function applied first to the sliced input and then to its consolidation
	 * @param sliceSize the number of elements per slice; must evenly divide the total size
	 * @return a {@link CollectionProducer} over the subdivided and recombined result,
	 *         or {@code null} if the size is not divisible by {@code sliceSize}
	 */
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
