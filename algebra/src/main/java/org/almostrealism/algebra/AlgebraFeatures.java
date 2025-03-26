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

public interface AlgebraFeatures extends CollectionFeatures {
	boolean enableIsolationWarnings = false;
	boolean enableDeepCannotMatch = true;
	boolean enableOptionalMatch = true;

	default <T extends PackedCollection<?>> CollectionProducer<T> broadcast(Producer<T> left,
																			   Producer<T> right) {
		TraversalPolicy groupShape = TraversalPolicy.uniform(1, shape(left).getDimensions());
		return broadcastSum("broadcast", groupShape, left, right);
	}

	/**
	 * Traverse the provided inputs using the specified group shape. As an example,
	 * using a group (2, 1, 1) with a left shape of (2, 3, 1) and right shape of
	 * (2, 1, 2), the left shape will be repeated 2 times along the final axis and
	 * the right shape will be repeated 3 times along the second to last axis.
	 * The result will then be a sum over the first axis for a final shape of
	 * (1, 3, 2).
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> broadcastSum(String name,
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

	default TraversalPolicy adjustPosition(TraversalPolicy position, int axis, long length) {
		if (position.length(axis) == length) {
			return position;
		} else if (position.length(axis) == 1) {
			return position.repeat(axis, length);
		} else {
			throw new IllegalArgumentException();
		}
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> weightedSum(String name,
																			  TraversalPolicy inputPositions,
																			  TraversalPolicy weightPositions,
																			  TraversalPolicy groupShape,
																			  Producer<T> input,
																			  Producer<T> weights) {
		return weightedSum(name, inputPositions, weightPositions, groupShape, groupShape, input, weights);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> weightedSum(String name,
																			  TraversalPolicy inputPositions,
																			  TraversalPolicy weightPositions,
																			  TraversalPolicy inputGroupShape,
																			  TraversalPolicy weightGroupShape,
																			  Producer<T> input,
																			  Producer<T> weights) {
		return weightedSum(name, new TraversalPolicy(inputPositions.extent()),
				inputPositions, weightPositions, inputGroupShape, weightGroupShape, input, weights);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> weightedSum(String name,
																			  TraversalPolicy resultShape,
																			  TraversalPolicy inputPositions,
																			  TraversalPolicy weightPositions,
																			  TraversalPolicy inputGroupShape,
																			  TraversalPolicy weightGroupShape,
																			  Producer<T> input,
																			  Producer<T> weights) {
		return new WeightedSumComputation<>(
						resultShape,
						inputPositions, weightPositions,
						inputGroupShape, weightGroupShape,
						(Supplier) input, (Supplier) weights);
	}

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

	static Optional<Producer<?>> matchInput(Supplier<?> producer, Supplier<?> target) {
		if (producer instanceof Producer && target instanceof Producer) {
			return matchInput((Producer) producer, (Producer<?>) target);
		}

		return null;
	}

	static <T> Optional<Producer<T>> matchInput(Producer<T> producer, Producer<?> target) {
		List<Producer<T>> matched = matchingInputs(producer, target);

		if (matched.isEmpty()) {
			return enableOptionalMatch ? Optional.empty() : null;
		} else if (matched.size() == 1) {
			return Optional.of(matched.get(0));
		}

		return null;
	}

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

	static boolean isRoot(Supplier<?> p) {
		return p instanceof CollectionProviderProducer || p instanceof PassThroughProducer || p instanceof InputStub;
	}

	static Supplier<?> getRoot(Supplier<?> p) {
		while (p instanceof ReshapeProducer || p instanceof MemoryDataDestinationProducer) {
			if (p instanceof ReshapeProducer) {
				p = ((ReshapeProducer<?>) p).getChildren().iterator().next();
			} else {
				p = (Producer<?>) ((MemoryDataDestinationProducer) p).getDelegate();
			}
		}

		return p;
	}

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
