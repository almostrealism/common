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

package org.almostrealism.algebra.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.SubsetTraversalExpression;
import io.almostrealism.collect.SubsetTraversalWeightedSumExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.WeightedSumDeltaExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

import java.util.List;
import java.util.function.Supplier;

public class WeightedSumComputation <T extends PackedCollection<?>>
		extends TraversableExpressionComputation<T> {
	private TraversalPolicy resultShape;
	private TraversalPolicy inputPositions, weightPositions;
	private TraversalPolicy inputGroupShape, weightGroupShape;

	private TraversalPolicy inShape, weightShape;

	public WeightedSumComputation(TraversalPolicy resultShape,
								  TraversalPolicy inputPositions,
								  TraversalPolicy weightPositions,
								  TraversalPolicy inputGroupShape,
								  TraversalPolicy weightGroupShape,
								  Supplier<Evaluable<? extends PackedCollection<?>>> input,
								  Supplier<Evaluable<? extends PackedCollection<?>>> weights) {
		super("weightedSum", resultShape.traverseEach(), input, weights);
		this.resultShape = resultShape;
		this.inputPositions = inputPositions;
		this.weightPositions = weightPositions;
		this.inputGroupShape = inputGroupShape;
		this.weightGroupShape = weightGroupShape;
		this.inShape = shape(input);
		this.weightShape = shape(weights);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new SubsetTraversalWeightedSumExpression(
				resultShape,
				inputPositions, weightPositions,
				inShape, weightShape,
				inputGroupShape, weightGroupShape,
				args[1], args[2]);
	}

	public SubsetTraversalExpression getInputTraversal() {
		return new SubsetTraversalExpression(resultShape, inShape, inputGroupShape, inputPositions);
	}

	public SubsetTraversalExpression getWeightsTraversal() {
		return new SubsetTraversalExpression(resultShape, weightShape, weightGroupShape, weightPositions);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new WeightedSumComputation<>(resultShape,
				inputPositions, weightPositions,
				inputGroupShape, weightGroupShape,
				(Producer) children.get(1),
				(Producer) children.get(2));
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (AlgebraFeatures.match(getInputs().get(1), target) && AlgebraFeatures.cannotMatch(getInputs().get(2), target)) {
			return new DefaultTraversableExpressionComputation<>("weightedSumDelta",
					getShape().append(shape(target)),
					args ->
							new WeightedSumDeltaExpression(getShape(), shape(target), getInputTraversal(), getWeightsTraversal(), args[1]),
					(Supplier) getInputs().get(2));
		} else if (AlgebraFeatures.match(getInputs().get(2), target) && AlgebraFeatures.cannotMatch(getInputs().get(1), target)) {
			return new DefaultTraversableExpressionComputation<>("weightedSumDelta",
					getShape().append(shape(target)),
					args ->
							new WeightedSumDeltaExpression(getShape(), shape(target), getWeightsTraversal(), getInputTraversal(), args[1]),
					(Supplier) getInputs().get(1));
		}

		return super.delta(target);
	}
}
