/*
 * Copyright 2023 Michael Murray
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

import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

public class RelativeTraversableExpression<T> implements TraversableExpression<T>, Shape<T> {
	private final TraversalPolicy shape;
	private final TraversableExpression<T> expression;
	private final Supplier<Expression<?>> offset;

	public RelativeTraversableExpression(TraversalPolicy shape, TraversableExpression<T> expression,
										 LongFunction<Expression<?>> offset) {
		this(shape, expression, () -> offset.apply(shape.getSizeLong()));
	}

	public RelativeTraversableExpression(TraversalPolicy shape, TraversableExpression<T> expression, Expression offset) {
		this(shape, expression, () -> offset);
	}

	protected RelativeTraversableExpression(TraversalPolicy shape, TraversableExpression<T> expression, Supplier<Expression<?>> offset) {
		this.shape = shape;
		this.expression = expression;
		this.offset = offset;
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
		return expression.getValueAt(index);
	}

	@Override
	public Expression<T> getValueRelative(Expression index) {
		if (expression.isRelative()) {
			return expression.getValueRelative(index);
		} else {
			return expression.getValueAt(offset.get().add(index));
		}
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return expression.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	public Expression uniqueNonZeroIndexRelative(Index localIndex, Expression targetIndex) {
		if (expression.isRelative()) {
			return expression.uniqueNonZeroIndexRelative(localIndex, targetIndex);
		} else {
			Set<Index> indices = offset.get().getIndices();

			if (indices.isEmpty()) {
				return null;
			} else if (indices.size() > 1) {
				throw new UnsupportedOperationException();
			}

			return expression.uniqueNonZeroIndex(indices.iterator().next(), localIndex, offset.get().add(targetIndex));
		}
	}

	@Override
	public boolean isTraversable() { return expression.isTraversable(); }

	@Override
	public boolean isRelative() { return expression.isRelative(); }
}
