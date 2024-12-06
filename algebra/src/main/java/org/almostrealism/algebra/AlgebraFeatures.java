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

package org.almostrealism.algebra;

import io.almostrealism.code.Computation;
import io.almostrealism.collect.SubsetTraversalWeightedSumExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
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
		TraversalPolicy inShape = shape(input);
		TraversalPolicy weightShape = shape(weights);

		return new DefaultTraversableExpressionComputation<>(name, resultShape.traverseEach(),
				(args) -> new SubsetTraversalWeightedSumExpression(
						resultShape,
						inputPositions, weightPositions,
						inShape, weightShape,
						inputGroupShape, weightGroupShape,
						args[1], args[2]), (Supplier) input, (Supplier) weights);
	}

	default <T> List<Producer<T>> matchingInputs(Producer<T> producer, Producer<?> target) {
		if (!(producer instanceof Process)) return Collections.emptyList();

		List<Producer<T>> matched = new ArrayList<>();

		for (Process<?, ?> p : ((Process<?, ?>) producer).getChildren()) {
			if (deepMatch(p, target)) {
				matched.add((Producer<T>) p);
			}
		}

		return matched;
	}

	default <T> Optional<Producer<T>> matchInput(Producer<T> producer, Producer<?> target) {
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
		return p instanceof CollectionProviderProducer || p instanceof PassThroughProducer;
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

		if (Objects.equals(p, q)) {
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
