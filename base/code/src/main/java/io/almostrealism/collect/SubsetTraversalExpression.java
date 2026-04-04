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

/**
 * Encodes the geometry of a subset-traversal operation where a sliding window (group)
 * moves over an operand collection to produce output values.
 *
 * <p>A {@code SubsetTraversalExpression} stores four shapes:
 * <ul>
 *   <li>{@link #resultShape} — the output space</li>
 *   <li>{@link #operandShape} — the input/weight operand space</li>
 *   <li>{@link #groupShape} — the shape of one sliding window</li>
 *   <li>{@link #positions} — the set of anchor positions for the window over the operand space</li>
 * </ul>
 * </p>
 *
 * <p>The main methods translate between output indices, group-member indices, and operand indices,
 * and determine whether a given operand index falls within the active group window for a given
 * output index.</p>
 */
public class SubsetTraversalExpression {
	/** Whether to emit diagnostic logging during index computation. */
	public static boolean enableLogging = false;

	/** The output shape produced by the traversal. */
	protected final TraversalPolicy resultShape;

	/** The shape of the operand (input or weight) collection. */
	protected final TraversalPolicy operandShape;

	/** The shape of one subset window (group) applied at each position. */
	protected final TraversalPolicy groupShape;

	/** The set of anchor positions defining where each group window is placed over the operand. */
	protected final TraversalPolicy positions;

	/**
	 * Creates a subset traversal expression with the given geometry shapes.
	 *
	 * @param resultShape  the output shape
	 * @param operandShape the operand shape
	 * @param groupShape   the window (group) shape
	 * @param positions    the anchor positions of the window over the operand
	 */
	public SubsetTraversalExpression(
			TraversalPolicy resultShape, TraversalPolicy operandShape,
			TraversalPolicy groupShape, TraversalPolicy positions) {
		this.resultShape = resultShape;
		this.operandShape = operandShape;
		this.groupShape = groupShape;
		this.positions = positions;
	}

	/**
	 * Returns the output shape of the traversal.
	 *
	 * @return the result shape
	 */
	public TraversalPolicy getResultShape() { return resultShape; }

	/**
	 * Returns the shape of the operand (input or weight) collection.
	 *
	 * @return the operand shape
	 */
	public TraversalPolicy getOperandShape() { return operandShape; }

	/**
	 * Returns the shape of one subset window.
	 *
	 * @return the group shape
	 */
	public TraversalPolicy getGroupShape() { return groupShape; }

	/**
	 * Returns the anchor positions of the window over the operand space.
	 *
	 * @return the positions shape
	 */
	public TraversalPolicy getPositions() { return positions; }

	/**
	 * Returns the anchor position (in the operand space) of the group window that
	 * contributes to the given output index.
	 *
	 * @param outputIndex the flat output index expression
	 * @return an array of position coordinate expressions in the operand space
	 */
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

	/**
	 * Returns the position within the group window of a given operand index,
	 * relative to the group anchor for the given output index.
	 *
	 * @param operandIndex the flat operand index expression
	 * @param resultIndex  the flat result index expression
	 * @return an array of coordinate expressions within the group window
	 */
	public Expression[] getPositionInGroup(Expression operandIndex, Expression resultIndex) {
		Expression subsetPosition[] = getPositionOfGroup(resultIndex);
		Expression operandPosition[] = operandShape.position(operandIndex);

		Expression[] groupPosition = new Expression[operandShape.getDimensions()];
		for (int i = 0; i < groupPosition.length; i++) {
			groupPosition[i] = operandPosition[i].subtract(subsetPosition[i]);
		}

		return groupPosition;
	}

	/**
	 * Returns a boolean expression that is {@code true} when the given operand index
	 * falls within the group window for the given output index.
	 *
	 * @param operandIndex the flat operand index expression
	 * @param resultIndex  the flat result index expression
	 * @return a boolean expression representing containment in the group window
	 */
	public Expression<Boolean> isIndexInGroup(Expression operandIndex, Expression resultIndex) {
		Expression groupPosition[] = getPositionInGroup(operandIndex, resultIndex);

		List<Expression<?>> conditions = new ArrayList<>();

		for (int i = 0; i < groupPosition.length; i++) {
			conditions.add(groupPosition[i].greaterThanOrEqual(0));
			conditions.add(groupPosition[i].lessThan(groupShape.length(i)));
		}

		return Conjunction.of(conditions);
	}

	/**
	 * Returns the flat index within the group window that corresponds to the given operand and
	 * result indices.
	 *
	 * @param operandIndex the flat operand index expression
	 * @param resultIndex  the flat result index expression
	 * @return the flat group index expression
	 */
	public Expression getIndexInGroup(Expression operandIndex, Expression resultIndex) {
		return groupShape.index(getPositionInGroup(operandIndex, resultIndex));
	}

	/**
	 * Returns the flat operand index corresponding to a given group member position
	 * for the given output index.
	 *
	 * @param groupIndex  the flat group index expression (position within one window)
	 * @param outputIndex the flat output index expression
	 * @return the flat operand index expression
	 */
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
