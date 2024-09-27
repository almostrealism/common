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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;

public class SubsetTraversalWeightedSumExpression extends WeightedSumExpression {
	public SubsetTraversalWeightedSumExpression(TraversalPolicy inputPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		// Do not traverse the weights, only traverse the input
		this(inputPositions, new TraversalPolicy(1),
				inputShape, groupShape, input, weights);
	}

	public SubsetTraversalWeightedSumExpression(TraversalPolicy inputPositions, TraversalPolicy weightPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		super(inputPositions, groupShape.getTotalSize(), input, weights,
				indexGenerator(inputPositions, weightPositions, inputShape, groupShape));
	}

	private static MemberIndexGenerator indexGenerator(
			TraversalPolicy inputPositions, TraversalPolicy weightPositions,
			TraversalPolicy inputShape, TraversalPolicy groupShape) {
		if (inputPositions.getDimensions() != inputShape.getDimensions() ||
				groupShape.getDimensions() != inputShape.getDimensions()) {
			throw new IllegalArgumentException();
		}

		return (groupIndex, operandIndex) -> outputIndex -> {
			// The position of the member in the group
			int[] groupPosition = groupShape.position(groupIndex);

			// The position of the subset being operated on in the
			// space of the output
			TraversalPolicy positions = operandIndex == 0 ? inputPositions : weightPositions;
			Expression[] subsetPosition = positions.position(outputIndex.imod(positions.getTotalSize()));

			// The position of the member in the input will be composed
			// of the position of the subset extended along each dimension
			// by the position of the specific member in the group
			Expression[] inputPosition = new Expression[inputShape.getDimensions()];

			// Find the location in the input of the current group member,
			// within the subset of the input that is being operated on
			// to produce the provided outputIndex
			for (int i = 0; i < inputPosition.length; i++) {
				inputPosition[i] = subsetPosition[i].add(groupPosition[i]);
			}

			// Provide the index in the input associated with that position
			return inputShape.index(inputPosition);
		};
	}
}
