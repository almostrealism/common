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

import io.almostrealism.expression.Expression;

import java.util.Arrays;

public class SubsetTraversalIndexMapping extends SubsetTraversalExpression implements TraversableExpression<Number> {
	public static boolean enableLogging = false;

	private final int groupIndex;

	public SubsetTraversalIndexMapping(
			TraversalPolicy resultShape, TraversalPolicy operandShape,
			TraversalPolicy groupShape, TraversalPolicy positions,
			int groupIndex) {
		super(resultShape, operandShape, groupShape, positions);
		this.groupIndex = groupIndex;
	}

	@Override
	public Expression<Number> getValueAt(Expression<?> outputIndex) {
		int[] groupPosition = groupShape.position(groupIndex);

		Expression[] subsetPosition = getPositionOfGroup(outputIndex);

		if (enableLogging) {
			System.out.println("Operand " + operandShape +
					" in " + groupShape + " group over " + positions +
					" [member " + groupIndex + "] is in position " + Arrays.toString(groupPosition));
		}

		// Find the location in the input of the current group member,
		// within the subset of the input that is being operated on
		// to produce the provided outputIndex. This is composed of
		// the position of the subset extended along each dimension
		// by the relative position of the group member
		Expression[] inputPosition = new Expression[operandShape.getDimensions()];
		for (int i = 0; i < inputPosition.length; i++) {
			inputPosition[i] = subsetPosition[i].add(groupPosition[i]);

			if (enableLogging)
				System.out.println("\t" + i + " " + Arrays.toString(inputPosition[i].sequence().toArray()));
		}

		// Provide the index in the operand that is associated
		// with that position for the current group member
		return operandShape.index(inputPosition);
	}
}
