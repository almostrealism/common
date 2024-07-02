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
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;

public class ConstantCollectionExpression extends CollectionExpressionAdapter {
	private final Expression<?> value;

	public ConstantCollectionExpression(TraversalPolicy shape, Expression<?> value) {
		super(shape);
		this.value = value;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return (Expression) value;
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (getShape().getTotalSizeLong() == 1) return new IntegerConstant(0);
		if (value.doubleValue().orElse(-1) == 0.0) return new IntegerConstant(0);
		return null;
	}

	@Override
	public boolean isIndexIndependent() { return true; }
}
