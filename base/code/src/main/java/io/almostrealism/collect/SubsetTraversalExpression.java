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

import io.almostrealism.expression.Conjunction;
import io.almostrealism.expression.Expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SubsetTraversalExpression {
	public static boolean enableLogging = false;

	protected final TraversalPolicy resultShape;
	protected final TraversalPolicy operandShape;
	protected final TraversalPolicy groupShape;
	protected final TraversalPolicy positions;

	public SubsetTraversalExpression(
			TraversalPolicy resultShape, TraversalPolicy operandShape,
			TraversalPolicy groupShape, TraversalPolicy positions) {
		this.resultShape = resultShape;
		this.operandShape = operandShape;
		this.groupShape = groupShape;
		this.positions = positions;
	}

	public TraversalPolicy getResultShape() { return resultShape; }
	public TraversalPolicy getOperandShape() { return operandShape; }
	public TraversalPolicy getGroupShape() { return groupShape; }
	public TraversalPolicy getPositions() { return positions; }

	public Expression[] getPositionOfGroup(Expression outputIndex) {
		// The position in the output being computed
		Expression[] outputPosition = resultShape.position(outputIndex.imod(resultShape.getTotalSizeLong()));

		// The output index needs to be projected into the space of the
		// positions before it can be used with the input (or weights)
		Expression index = positions.index(outputPosition);

		// The position of the current subset in the space of the
		// input (or weights)
		return positions.position(index.imod(positions.getTotalInputSizeLong()));
	}

	public Expression[] getPositionInGroup(Expression operandIndex, Expression resultIndex) {
		Expression subsetPosition[] = getPositionOfGroup(resultIndex);
		Expression operandPosition[] = operandShape.position(operandIndex);

		Expression[] groupPosition = new Expression[operandShape.getDimensions()];
		for (int i = 0; i < groupPosition.length; i++) {
			groupPosition[i] = operandPosition[i].subtract(subsetPosition[i]);
		}

		return groupPosition;
	}

	public Expression<Boolean> isIndexInGroup(Expression operandIndex, Expression resultIndex) {
		Expression groupPosition[] = getPositionInGroup(operandIndex, resultIndex);

		List<Expression<?>> conditions = new ArrayList<>();

		for (int i = 0; i < groupPosition.length; i++) {
			conditions.add(groupPosition[i].greaterThanOrEqual(0));
			conditions.add(groupPosition[i].lessThan(groupShape.length(i)));
		}

		return Conjunction.of(conditions);
	}

	public Expression getIndexInGroup(Expression operandIndex, Expression resultIndex) {
		return groupShape.index(getPositionInGroup(operandIndex, resultIndex));
	}

	public Expression getInputIndex(Expression<?> groupIndex, Expression<?> outputIndex) {
		Expression[] groupPosition = groupShape.position(groupIndex);

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
