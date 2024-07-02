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

public class IndexProjectionCollectionExpression extends CollectionExpressionAdapter {
	private UnaryOperator<Expression<?>> indexProjection;
	private TraversableExpression<Double> input;

	public IndexProjectionCollectionExpression(TraversalPolicy shape,
											   UnaryOperator<Expression<?>> indexProjection,
											   TraversableExpression<Double> input) {
		super(shape);
		this.indexProjection = indexProjection;
		this.input = input;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return input.getValueAt(indexProjection.apply(index));
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return input.uniqueNonZeroOffset(globalIndex, localIndex, indexProjection.apply(targetIndex));
	}
}
