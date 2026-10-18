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

package org.almostrealism.algebra;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.CollectionComparisonFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Provides advanced algebraic operations including broadcasting and weighted sums.
 *
 * <p>
 * {@link AlgebraFeatures} extends {@link CollectionFeatures} to provide sophisticated algebraic
 * operations used throughout the framework. This interface includes:
 * <ul>
 *   <li>Broadcasting operations for combining tensors with different shapes</li>
 *   <li>Weighted sum computations for implementing operations like matrix multiplication</li>
 * </ul>
 *
 * <h2>Broadcasting Examples</h2>
 * <pre>{@code
 * // Simple broadcast addition
 * CollectionProducer a = c(shape(3, 1), ...);  // (3, 1)
 * CollectionProducer b = c(shape(1, 4), ...);  // (1, 4)
 * CollectionProducer result = broadcast(a, b); // (3, 4)
 *
 * // Custom broadcast with grouping
 * TraversalPolicy groupShape = shape(2, 1, 1);
 * CollectionProducer sum = broadcastSum("add", groupShape, a, b);
 * }</pre>
 *
 * <h2>Weighted Sum Examples</h2>
 * <pre>{@code
 * // Matrix-vector multiplication via weighted sum
 * TraversalPolicy inputPos = shape(3, 4);
 * TraversalPolicy weightPos = shape(3, 4);
 * TraversalPolicy groupShape = shape(1, 4);  // Sum over dimension 1
 * CollectionProducer result = weightedSum(
 *     "matmul", inputPos, weightPos, groupShape, matrix, vector);
 * }</pre>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author  Michael Murray
 * @see CollectionFeatures
 * @see TraversalPolicy
 * @see Producer
 */
public interface AlgebraFeatures extends CollectionComparisonFeatures {
	/**
	 * Broadcasts two producers to a common shape and combines them.
	 * This uses uniform grouping (all 1s) across all dimensions.
	 *
	 * <p>
	 * Broadcasting follows NumPy-style rules: dimensions are aligned from the right,
	 * and sizes of 1 are expanded to match the other tensor.
	 * </p>
	 *
	 * @param left  the left operand
	 * @param right  the right operand
	 * @param <T>  the collection type
	 * @return a producer for the broadcast result
	 */
	default <T extends PackedCollection> CollectionProducer broadcast(Producer<T> left,
																	  Producer<T> right) {
		TraversalPolicy groupShape = TraversalPolicy.uniform(1, shape(left).getDimensions());
		return broadcastSum("broadcast", groupShape, left, right);
	}

	/**
	 * Broadcasts two producers using a specified group shape and sums over the groups.
	 *
	 * <p>
	 * This method performs a sophisticated broadcast operation that:
	 * <ol>
	 *   <li>Aligns the input shapes to a common result shape</li>
	 *   <li>Repeats dimensions where necessary (size 1 -> size N)</li>
	 *   <li>Sums over groups defined by the group shape</li>
	 * </ol>
	 * </p>
	 *
	 * <p>
	 * Example: Using group shape (2, 1, 1) with left shape (2, 3, 1) and right shape (2, 1, 2):
	 * <ul>
	 *   <li>Left is repeated 2 times along the final axis</li>
	 *   <li>Right is repeated 3 times along the second-to-last axis</li>
	 *   <li>Result is summed over the first axis for final shape (1, 3, 2)</li>
	 * </ul>
	 * </p>
	 *
	 * @param name  descriptive name for the operation
	 * @param groupShape  defines the grouping for summation
	 * @param left  the left operand
	 * @param right  the right operand
	 * @param <T>  the collection type
	 * @return a producer for the broadcast sum result
	 * @throws IllegalArgumentException if shapes are incompatible with the group shape
	 */
	default <T extends PackedCollection> CollectionProducer broadcastSum(String name,
																		 TraversalPolicy groupShape,
																		 Producer<T> left,
																		 Producer<T> right) {
		TraversalPolicy leftShape = shape(left);
		TraversalPolicy rightShape = shape(right);
		if (leftShape.getDimensions() != groupShape.getDimensions() ||
				rightShape.getDimensions() != groupShape.getDimensions()) {
			throw new IllegalArgumentException();
		}

		long[] resultDims = new long[groupShape.getDimensions()];
		TraversalPolicy leftPosition = leftShape;
		TraversalPolicy rightPosition = rightShape;

		for (int i = 0; i < resultDims.length; i++) {
			// Identify the result length along current axis
			long len = Math.max(leftShape.length(i), rightShape.length(i));

			// Repeat along current axis, if necessary
			leftPosition = leftPosition.broadcast(i, len);
			rightPosition = rightPosition.broadcast(i, len);

			// Adjust the result length by the length of the sum group
			resultDims[i] = len / groupShape.length(i);
		}

		TraversalPolicy resultShape = new TraversalPolicy(resultDims);
		return weightedSum(name, resultShape, leftPosition, rightPosition,
							groupShape, groupShape, left, right);
	}

	/**
	 * Computes a weighted sum with symmetric group shapes for input and weights.
	 *
	 * @param name  descriptive name for the operation
	 * @param inputPositions  traversal policy for input positions
	 * @param weightPositions  traversal policy for weight positions
	 * @param groupShape  group shape for both input and weights
	 * @param input  the input values
	 * @param weights  the weight values
	 * @param <T>  the collection type
	 * @return a producer for the weighted sum result
	 */
	default <T extends PackedCollection> CollectionProducer weightedSum(String name,
																		TraversalPolicy inputPositions,
																		TraversalPolicy weightPositions,
																		TraversalPolicy groupShape,
																		Producer<T> input,
																		Producer<T> weights) {
		return weightedSum(name, inputPositions, weightPositions, groupShape, groupShape, input, weights);
	}

	/**
	 * Computes a weighted sum with separate group shapes for input and weights.
	 * The result shape is inferred from the input positions.
	 *
	 * @param name  descriptive name for the operation
	 * @param inputPositions  traversal policy for input positions
	 * @param weightPositions  traversal policy for weight positions
	 * @param inputGroupShape  group shape for the input
	 * @param weightGroupShape  group shape for the weights
	 * @param input  the input values
	 * @param weights  the weight values
	 * @param <T>  the collection type
	 * @return a producer for the weighted sum result
	 */
	default <T extends PackedCollection> CollectionProducer weightedSum(String name,
																		TraversalPolicy inputPositions,
																		TraversalPolicy weightPositions,
																		TraversalPolicy inputGroupShape,
																		TraversalPolicy weightGroupShape,
																		Producer<T> input,
																		Producer<T> weights) {
		return weightedSum(name, inputPositions.extentShape().traverse(0),
				inputPositions, weightPositions, inputGroupShape, weightGroupShape, input, weights);
	}

	/**
	 * Computes a weighted sum with full control over result shape, positions, and group shapes.
	 *
	 * <p>
	 * Weighted sum is a fundamental operation for implementing:
	 * <ul>
	 *   <li>Matrix multiplication</li>
	 *   <li>Convolution</li>
	 *   <li>Attention mechanisms</li>
	 *   <li>Custom tensor contractions</li>
	 * </ul>
	 * </p>
	 *
	 * @param name  descriptive name for the operation
	 * @param resultShape  the shape of the result
	 * @param inputPositions  traversal policy for input positions
	 * @param weightPositions  traversal policy for weight positions
	 * @param inputGroupShape  group shape for the input (dimensions to sum over)
	 * @param weightGroupShape  group shape for the weights (dimensions to sum over)
	 * @param input  the input values
	 * @param weights  the weight values
	 * @param <T>  the collection type
	 * @return a producer for the weighted sum result
	 */
	default <T extends PackedCollection> CollectionProducer weightedSum(String name,
																		TraversalPolicy resultShape,
																		TraversalPolicy inputPositions,
																		TraversalPolicy weightPositions,
																		TraversalPolicy inputGroupShape,
																		TraversalPolicy weightGroupShape,
																		Producer<T> input,
																		Producer<T> weights) {
		return new WeightedSumComputation(
						resultShape,
						inputPositions, weightPositions,
						inputGroupShape, weightGroupShape,
						(Producer) input, (Producer) weights);
	}
}
