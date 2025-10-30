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
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelIndex;

public class RelativeTraversableExpression<T> implements TraversableExpression<T>, Shape<T> {
	private final TraversalPolicy shape;
	private final TraversableExpression<T> expression;
	private final Expression index;
	private final int memLength;

	protected RelativeTraversableExpression(TraversalPolicy shape,
											TraversableExpression<T> expression,
											Expression index, int memLength) {
		this.shape = shape;
		this.expression = expression;
		this.index = index;
		this.memLength = memLength;
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public T reshape(TraversalPolicy shape) {
		throw new UnsupportedOperationException();
	}

	@Override
	public T traverse(int axis) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return Shape.super.containsIndex(index);
	}

	public TraversableExpression<T> getExpression() {
		return expression;
	}

	@Override
	public Expression<T> getValue(Expression... pos) {
		return expression.getValue(pos);
	}

	@Override
	public Expression<T> getValueAt(Expression index) {
		if (index.longValue().orElse(0) < 0) {
			throw new IllegalArgumentException();
		}

		return expression.getValueAt(index);
	}

	@Override
	public Expression<T> getValueRelative(Expression localIndex) {
		Expression offset = index.toInt().divide(memLength).multiply(shape.getSizeLong());
		Expression result = expression.getValueAt(offset.add(localIndex));
		Expression standard = expression.getValueRelative(localIndex);

		if (memLength != shape.getSize() && index instanceof KernelIndex == false
		 		&& !result.getExpression(Expression.defaultLanguage())
				.equals(standard.getExpression(Expression.defaultLanguage()))) {
			expression.getValueRelative(localIndex);
		}

		return result;
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return expression.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	public boolean isTraversable() { return expression.isTraversable(); }

	public static TraversableExpression getExpression(TraversableExpression expression) {
		while (expression instanceof RelativeTraversableExpression) {
			expression = ((RelativeTraversableExpression) expression).getExpression();
		}

		return expression;
	}
}
