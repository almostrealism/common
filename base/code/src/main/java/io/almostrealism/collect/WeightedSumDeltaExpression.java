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
import io.almostrealism.expression.Mask;

/**
 * The Jacobian of a {@link WeightedSumExpression} with respect to one of its operands.
 *
 * <p>The shape of this expression is {@code resultShape.append(targetShape)}.
 * For each flat joint index, the high-order portion selects the output element (result index)
 * and the low-order portion selects the target-operand element (target index). The value is
 * non-zero only when the target index falls within the subset window that contributes to the
 * given result index; in that case the corresponding element from the other operand is returned,
 * masked by a membership test.</p>
 */
public class WeightedSumDeltaExpression extends CollectionExpressionAdapter {

	/** The output shape of the weighted sum being differentiated. */
	private final TraversalPolicy resultShape;

	/** The shape of the target operand with respect to which differentiation is performed. */
	private final TraversalPolicy targetShape;

	/**
	 * The subset geometry that maps target-operand indices to group-member positions
	 * within the output window.
	 */
	private final SubsetTraversalExpression targetMapping;

	/**
	 * The subset geometry that maps group-member positions to operand indices for
	 * the non-differentiated operand.
	 */
	private final SubsetTraversalExpression operandMapping;

	/** The non-differentiated operand (weights or inputs, whichever is not the target). */
	private final TraversableExpression<Double> operand;

	/**
	 * Creates a weighted-sum delta expression.
	 *
	 * @param resultShape   the output shape of the weighted sum
	 * @param targetShape   the shape of the target operand
	 * @param targetMapping the geometry used to map target indices to group positions
	 * @param operandMapping the geometry used to map group positions to the other operand's indices
	 * @param operand       the non-differentiated operand supplying the Jacobian values
	 */
	public WeightedSumDeltaExpression(TraversalPolicy resultShape,
									  TraversalPolicy targetShape,
									  SubsetTraversalExpression targetMapping,
									  SubsetTraversalExpression operandMapping,
									  TraversableExpression<Double> operand) {
		super("weightedSumDelta", resultShape.append(targetShape));
		this.resultShape = resultShape;
		this.targetShape = targetShape;
		this.targetMapping = targetMapping;
		this.operandMapping = operandMapping;
		this.operand = operand;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Extracts the result and target indices from the joint flat index, checks whether the
	 * target index is within the active group window for the result index, and returns the
	 * corresponding value from the other operand masked by the membership boolean.</p>
	 */
	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		Expression targetIndex = index.imod(targetShape.getTotalInputSizeLong());
		Expression resultIndex = index.divide(targetShape.getTotalInputSizeLong());
		Expression groupIndex = targetMapping.getIndexInGroup(targetIndex, resultIndex);
		Expression isGroup = targetMapping.isIndexInGroup(targetIndex, resultIndex);

		Expression<?> inputIndex = operandMapping.getInputIndex(groupIndex, resultIndex);
		return Mask.of(isGroup, operand.getValueAt(inputIndex));
	}
}
