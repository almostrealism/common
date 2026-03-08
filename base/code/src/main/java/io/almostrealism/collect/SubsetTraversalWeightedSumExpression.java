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

package io.almostrealism.collect;

public class SubsetTraversalWeightedSumExpression extends WeightedSumExpression {

	public SubsetTraversalWeightedSumExpression(TraversalPolicy inputPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		// Do not traverse the weights, only traverse the input
		this(inputPositions, TraversalPolicy.uniform(1, inputPositions.getDimensions()),
				inputShape, groupShape, input, weights);
	}

	public SubsetTraversalWeightedSumExpression(TraversalPolicy inputPositions, TraversalPolicy weightPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		this(new TraversalPolicy(inputPositions.extent()),
				inputPositions, weightPositions, inputShape, groupShape, input, weights);
	}

	public SubsetTraversalWeightedSumExpression(TraversalPolicy shape,
												TraversalPolicy inputPositions, TraversalPolicy weightPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		this(shape, inputPositions, weightPositions, inputShape, inputShape,
				groupShape, groupShape, input, weights);
	}

	public SubsetTraversalWeightedSumExpression(TraversalPolicy shape,
												TraversalPolicy inputPositions, TraversalPolicy weightPositions,
												TraversalPolicy inputShape, TraversalPolicy weightShape,
												TraversalPolicy inputGroupShape, TraversalPolicy weightGroupShape,
												TraversableExpression input, TraversableExpression weights) {
		super(shape, inputGroupShape.getTotalSize(), input, weights,
				indexGenerator(shape,
						inputPositions, weightPositions,
						inputShape, weightShape,
						inputGroupShape, weightGroupShape));

		if (weightGroupShape.getTotalSize() != inputGroupShape.getTotalSize()) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return super.delta(target);
	}

	private static MemberIndexGenerator indexGenerator(
			TraversalPolicy resultShape,
			TraversalPolicy inputPositions, TraversalPolicy weightPositions,
			TraversalPolicy inputShape, TraversalPolicy weightShape,
			TraversalPolicy inputGroupShape, TraversalPolicy weightGroupShape) {
		if (inputPositions.getDimensions() != resultShape.getDimensions() ||
				weightPositions.getDimensions() != resultShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (inputPositions.getDimensions() != inputShape.getDimensions() ||
				inputGroupShape.getDimensions() != inputShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (weightPositions.getDimensions() != weightShape.getDimensions() ||
				weightGroupShape.getDimensions() != weightShape.getDimensions()) {
			throw new IllegalArgumentException();
		}

		return (groupIndex, operandIndex) -> {
			TraversalPolicy operandShape = operandIndex == 0 ? inputShape : weightShape;
			TraversalPolicy groupShape = operandIndex == 0 ? inputGroupShape : weightGroupShape;
			TraversalPolicy positions = operandIndex == 0 ? inputPositions : weightPositions;
			return new SubsetTraversalIndexMapping(resultShape, operandShape, groupShape, positions, groupIndex);
		};
	}
}
