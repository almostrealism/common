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

import io.almostrealism.code.Computation;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Parent;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.calculus.InputStub;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Provides advanced algebraic operations including broadcasting, weighted sums, and producer matching.
 *
 * <p>
 * {@link AlgebraFeatures} extends {@link CollectionFeatures} to provide sophisticated algebraic
 * operations used throughout the framework. This interface includes:
 * <ul>
 *   <li>Broadcasting operations for combining tensors with different shapes</li>
 *   <li>Weighted sum computations for implementing operations like matrix multiplication</li>
 *   <li>Producer matching utilities for automatic differentiation and optimization</li>
 * </ul>
 *
 * <h2>Broadcasting Examples</h2>
 * <pre>{@code
 * // Simple broadcast addition
 * CollectionProducer<PackedCollection> a = c(shape(3, 1), ...);  // (3, 1)
 * CollectionProducer<PackedCollection> b = c(shape(1, 4), ...);  // (1, 4)
 * CollectionProducer<PackedCollection> result = broadcast(a, b); // (3, 4)
 *
 * // Custom broadcast with grouping
 * TraversalPolicy groupShape = shape(2, 1, 1);
 * CollectionProducer<PackedCollection> sum = broadcastSum("add", groupShape, a, b);
 * }</pre>
 *
 * <h2>Weighted Sum Examples</h2>
 * <pre>{@code
 * // Matrix-vector multiplication via weighted sum
 * TraversalPolicy inputPos = shape(3, 4);
 * TraversalPolicy weightPos = shape(3, 4);
 * TraversalPolicy groupShape = shape(1, 4);  // Sum over dimension 1
 * CollectionProducer<PackedCollection> result = weightedSum(
 *     "matmul", inputPos, weightPos, groupShape, matrix, vector);
 * }</pre>
 *
 * @author  Michael Murray
 * @see CollectionFeatures
 * @see TraversalPolicy
 * @see Producer
 */
public interface AlgebraFeatures extends CollectionFeatures {
	/** Enable warnings when isolated producers cannot be matched (debugging) */
	boolean enableIsolationWarnings = false;

	/** Enable deep traversal when checking if producers cannot match */
	boolean enableDeepCannotMatch = true;

	/** Return Optional.empty() instead of null for no-match cases */
	boolean enableOptionalMatch = true;

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

		long resultDims[] = new long[groupShape.getDimensions()];
		TraversalPolicy leftPosition = leftShape;
		TraversalPolicy rightPosition = rightShape;

		for (int i = 0; i < resultDims.length; i++) {
			// Identify the result length along current axis
			long len = Math.max(leftShape.length(i), rightShape.length(i));

			// Repeat along current axis, if necessary
			leftPosition = adjustPosition(leftPosition, i, len);
			rightPosition = adjustPosition(rightPosition, i, len);

			// Adjust the result length by the length of the sum group
			resultDims[i] = len / groupShape.length(i);
		}

		TraversalPolicy resultShape = new TraversalPolicy(resultDims);
		return weightedSum(name, resultShape, leftPosition, rightPosition,
							groupShape, groupShape, left, right);
	}

	/**
	 * Adjusts a position along a specific axis to match a target length.
	 * If the position already has the target length, it is returned unchanged.
	 * If the position has length 1, it is repeated to match the target length.
	 *
	 * @param position  the traversal policy to adjust
	 * @param axis  the axis to adjust
	 * @param length  the target length for the axis
	 * @return the adjusted traversal policy
	 * @throws IllegalArgumentException if the position cannot be adjusted to the target length
	 */
	default TraversalPolicy adjustPosition(TraversalPolicy position, int axis, long length) {
		if (position.length(axis) == length) {
			return position;
		} else if (position.length(axis) == 1) {
			return position.repeat(axis, length);
		} else {
			throw new IllegalArgumentException();
		}
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
		return weightedSum(name, new TraversalPolicy(inputPositions.extent()),
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
		return (CollectionProducer) new WeightedSumComputation(
						resultShape,
						inputPositions, weightPositions,
						inputGroupShape, weightGroupShape,
						(Producer) input, (Producer) weights);
	}

	/**
	 * Finds all child inputs of a producer that match the target producer.
	 * Used in automatic differentiation to identify which inputs depend on a target variable.
	 *
	 * @param producer  the producer to search within
	 * @param target  the target producer to match
	 * @param <T>  the producer type
	 * @return list of matching child producers (empty if no matches or producer is not a Process)
	 */
	static <T> List<Producer<T>> matchingInputs(Producer<T> producer, Producer<?> target) {
		if (!(producer instanceof Process)) return Collections.emptyList();

		List<Producer<T>> matched = new ArrayList<>();

		for (Process<?, ?> p : ((Process<?, ?>) producer).getChildren()) {
			if (deepMatch(p, target)) {
				matched.add((Producer<T>) p);
			}
		}

		return matched;
	}

	/**
	 * Attempts to match a single input from a supplier against a target.
	 * Delegates to the typed version if both arguments are producers.
	 *
	 * @param producer  the supplier to search within
	 * @param target  the target supplier to match
	 * @return Optional containing the matched producer, empty if no match, or null if ambiguous
	 */
	static Optional<Producer<?>> matchInput(Supplier<?> producer, Supplier<?> target) {
		if (producer instanceof Producer && target instanceof Producer) {
			return matchInput((Producer) producer, (Producer<?>) target);
		}

		return null;
	}

	/**
	 * Attempts to match a single input from a producer against a target.
	 *
	 * @param producer  the producer to search within
	 * @param target  the target producer to match
	 * @param <T>  the producer type
	 * @return Optional containing the matched producer if exactly one match exists,
	 *         empty Optional if no matches (when {@link #enableOptionalMatch} is true),
	 *         or null if multiple matches or optional matching is disabled
	 */
	static <T> Optional<Producer<T>> matchInput(Producer<T> producer, Producer<?> target) {
		List<Producer<T>> matched = matchingInputs(producer, target);

		if (matched.isEmpty()) {
			return enableOptionalMatch ? Optional.empty() : null;
		} else if (matched.size() == 1) {
			return Optional.of(matched.get(0));
		}

		return null;
	}

	/**
	 * Checks if two suppliers cannot possibly match.
	 * When {@link #enableDeepCannotMatch} is true, unwraps to root suppliers before checking.
	 *
	 * @param p  the first supplier
	 * @param target  the second supplier
	 * @return true if the suppliers definitely cannot match, false otherwise
	 */
	static boolean cannotMatch(Supplier<?> p, Supplier<?> target) {
		if (enableDeepCannotMatch) {
			p = getRoot(p);
			target = getRoot(target);
		}

		if (isRoot(p) && isRoot(target)) {
			return !match(p, target);
		}

		Optional<Producer<?>> matched = matchInput(p, target);
		if (matched != null) {
			return matched.isEmpty();
		}

		return false;
	}

	/**
	 * Performs a deep match check, recursively searching through the producer tree.
	 * Returns true if p matches target or if any child of p matches target.
	 *
	 * @param p  the supplier to search within
	 * @param target  the target supplier to match
	 * @return true if a match is found at any level, false otherwise
	 */
	static boolean deepMatch(Supplier<?> p, Supplier<?> target) {
		if (match(p, target)) {
			return true;
		} if (p instanceof Parent) {
			for (Supplier<?> child : ((Parent<Supplier>) p).getChildren()) {
				if (deepMatch(child, target)) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Checks if a supplier is a root producer (not derived from other producers).
	 * Root producers include collection providers, pass-through producers, and input stubs.
	 *
	 * @param p  the supplier to check
	 * @return true if the supplier is a root producer, false otherwise
	 */
	static boolean isRoot(Supplier<?> p) {
		return p instanceof CollectionProviderProducer || p instanceof PassThroughProducer || p instanceof InputStub;
	}

	/**
	 * Unwraps a supplier to find its root, skipping through reshape and destination wrappers.
	 *
	 * @param p  the supplier to unwrap
	 * @return the root supplier
	 */
	static Supplier<?> getRoot(Supplier<?> p) {
		while (p instanceof ReshapeProducer || p instanceof MemoryDataDestinationProducer) {
			if (p instanceof ReshapeProducer) {
				p = ((ReshapeProducer) p).getChildren().iterator().next();
			} else {
				p = (Producer<?>) ((MemoryDataDestinationProducer) p).getDelegate();
			}
		}

		return p;
	}

	/**
	 * Checks if two suppliers match, using algebraic matching rules when applicable.
	 *
	 * <p>
	 * Matching is determined by:
	 * <ul>
	 *   <li>For {@link Algebraic} types, uses their matches() method</li>
	 *   <li>For {@link PassThroughProducer}s, compares referenced argument indices</li>
	 *   <li>Otherwise, uses Object equality</li>
	 * </ul>
	 * </p>
	 *
	 * @param p  the first supplier
	 * @param q  the second supplier
	 * @return true if the suppliers match, false otherwise
	 */
	static boolean match(Supplier<?> p, Supplier<?> q) {
		p = getRoot(p);
		q = getRoot(q);

		if (p instanceof Algebraic && q instanceof Algebraic) {
			return ((Algebraic) p).matches((Algebraic) q);
		} else if (Objects.equals(p, q)) {
			// This comparison between p and q does not cover the case where they are both
			// CollectionProviderProducers referring to the same underlying value via
			// delegation
			return true;
		} else if (p instanceof PassThroughProducer && 	q instanceof PassThroughProducer) {
			return ((PassThroughProducer) p).getReferencedArgumentIndex() == ((PassThroughProducer) q).getReferencedArgumentIndex();
		} else if (enableIsolationWarnings &&
				(p instanceof CollectionProducerComputation.IsolatedProcess ||
						q instanceof CollectionProducerComputation.IsolatedProcess)) {
			Computation.console.features(DeltaFeatures.class)
					.warn("Isolated producer cannot be matched");
		}

		return false;
	}
}
