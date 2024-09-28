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

		return (groupIndex, operandIndex) -> outputIndex -> {
			TraversalPolicy operandShape = operandIndex == 0 ? inputShape : weightShape;
			TraversalPolicy groupShape = operandIndex == 0 ? inputGroupShape : weightGroupShape;
			TraversalPolicy positions = operandIndex == 0 ? inputPositions : weightPositions;

			// The position in the output being computed
			Expression[] outputPosition = resultShape.position(outputIndex.imod(resultShape.getTotalSize()));

			// The output index needs to be projected into the space of the
			// positions before it can be used with the input (or weights)
			Expression index = positions.index(outputPosition);

			// The position of this group member and the current subset.
			// in the space of the input (or weights)
			int[] groupPosition = groupShape.position(groupIndex);
			Expression[] subsetPosition = positions.position(index.imod(positions.getTotalSize()));

//			System.out.println("Operand "  + operandIndex + " " + operandShape +
//					" in " + groupShape + " group over " + positions +
//					" [member " + groupIndex + "] is in position " + Arrays.toString(groupPosition));

			// Find the location in the input of the current group member,
			// within the subset of the input that is being operated on
			// to produce the provided outputIndex. This is composed of
			// the position of the subset extended along each dimension
			// by the relative position of the group member
			Expression[] inputPosition = new Expression[operandShape.getDimensions()];
			for (int i = 0; i < inputPosition.length; i++) {
				inputPosition[i] = subsetPosition[i].add(groupPosition[i]);
//				System.out.println("\t" + i + " " + Arrays.toString(inputPosition[i].sequence().toArray()));
			}

			// Provide the index in the operand that is associated
			// with that position for the current group member
			return operandShape.index(inputPosition);
		};
	}
}
