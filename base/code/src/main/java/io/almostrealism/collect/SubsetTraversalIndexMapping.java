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

/**
 * A {@link TraversableExpression} that maps output indices to operand indices for a fixed
 * group member position within a subset traversal.
 *
 * <p>Given a fixed {@code groupIndex} (which identifies one member within the sliding window)
 * and an output index, this expression computes the flat index in the operand collection that
 * corresponds to that group member. The computation follows the geometry described by the parent
 * {@link SubsetTraversalExpression}.</p>
 *
 * <p>This is used as the {@link GroupExpression.MemberIndexGenerator} inside
 * {@link SubsetTraversalWeightedSumExpression}: one {@code SubsetTraversalIndexMapping} instance
 * is created per group member, and each instance answers "given this output index, which operand
 * index should I read for member {@code k}?"</p>
 */
public class SubsetTraversalIndexMapping extends SubsetTraversalExpression implements TraversableExpression<Number> {
	/** Whether to emit diagnostic logging during index computation. */
	public static boolean enableLogging = false;

	/** The fixed group member index this mapping corresponds to. */
	private final int groupIndex;

	/**
	 * Creates an index mapping for the given group member position.
	 *
	 * @param resultShape  the output shape of the traversal
	 * @param operandShape the operand collection shape
	 * @param groupShape   the shape of one sliding window
	 * @param positions    the anchor positions of the window over the operand space
	 * @param groupIndex   the flat index within the group window this mapping represents
	 */
	public SubsetTraversalIndexMapping(
			TraversalPolicy resultShape, TraversalPolicy operandShape,
			TraversalPolicy groupShape, TraversalPolicy positions,
			int groupIndex) {
		super(resultShape, operandShape, groupShape, positions);
		this.groupIndex = groupIndex;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the flat operand index for the fixed group member position given the
	 * supplied output index. The group member's position within the window is combined
	 * with the window's anchor position (derived from the output index) to produce the
	 * final operand position.</p>
	 */
	@Override
	public Expression<Number> getValueAt(Expression<?> outputIndex) {
		int[] groupPosition = groupShape.position(groupIndex);

		Expression[] subsetPosition = getPositionOfGroup(outputIndex);

		if (enableLogging) {
			log("Operand " + operandShape +
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
				log("\t" + i + " " + Arrays.toString(inputPosition[i].sequence().toArray()));
		}

		// Provide the index in the operand that is associated
		// with that position for the current group member
		return operandShape.index(inputPosition);
	}
}
