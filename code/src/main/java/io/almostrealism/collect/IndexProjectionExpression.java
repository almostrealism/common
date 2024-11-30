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
import io.almostrealism.kernel.Index;

import java.util.function.UnaryOperator;

public class IndexProjectionExpression extends OperandCollectionExpression {
	private UnaryOperator<Expression<?>> indexProjection;

	public IndexProjectionExpression(TraversalPolicy shape,
									 UnaryOperator<Expression<?>> indexProjection,
									 TraversableExpression<Double> input) {
		super(shape, input);
		this.indexProjection = indexProjection;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return operands[0].getValueAt(indexProjection.apply(index));
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		TraversableExpression<Double> in = getOperands().get(0);
		if (!(in instanceof CollectionExpression))
			return super.delta(target);

		CollectionExpression delta = ((CollectionExpression) in).delta(target);

		TraversalPolicy outShape = getShape();
		TraversalPolicy inShape = ((CollectionExpression<?>) in).getShape();
		TraversalPolicy targetShape = target.getShape();

		int outSize = outShape.getTotalSize();
		int inSize = inShape.getTotalSize();
		int targetSize = targetShape.getTotalSize();

		TraversalPolicy deltaShape = new TraversalPolicy(inSize, targetSize);
		TraversalPolicy overallShape = new TraversalPolicy(outSize, targetSize);

		TraversalPolicy shape = outShape.append(targetShape);

		UnaryOperator<Expression<?>> project = idx -> {
			Expression pos[] = overallShape.position(idx);
			return deltaShape.index(indexProjection.apply(pos[0]), pos[1]);
		};

		return new IndexProjectionExpression(shape, project, delta);
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return operands[0].uniqueNonZeroOffset(globalIndex, localIndex, indexProjection.apply(targetIndex));
	}
}
