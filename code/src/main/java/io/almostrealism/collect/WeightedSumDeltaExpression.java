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

public class WeightedSumDeltaExpression extends CollectionExpressionAdapter {

	private final TraversalPolicy resultShape;
	private final TraversalPolicy targetShape;

	private final SubsetTraversalExpression targetMapping;
	private final SubsetTraversalExpression operandMapping;
	private final TraversableExpression<Double> operand;

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
