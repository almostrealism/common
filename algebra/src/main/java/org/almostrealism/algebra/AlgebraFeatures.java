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

import io.almostrealism.collect.SubsetTraversalWeightedSumExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.function.Supplier;

public interface AlgebraFeatures extends CollectionFeatures {
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
}
