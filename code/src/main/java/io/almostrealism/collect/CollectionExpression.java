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

import io.almostrealism.code.Array;
import io.almostrealism.code.ExpressionList;
import io.almostrealism.expression.Expression;
import org.almostrealism.io.Describable;

import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface CollectionExpression<T> extends TraversableExpression<Double>, Shape<T>, Describable {
	boolean enableArrayTraversal = false;

	@Override
	default Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	default Stream<Expression<Double>> stream() {
		return IntStream.range(0, getShape().getTotalSize()).mapToObj(i -> getValueAt(e(i)));
	}

	default ExpressionList<Double> toList() {
		return stream().collect(ExpressionList.collector());
	}

	default Expression<Double> sum() { return toList().sum(); }

	default Expression<Double> max() { return toList().max(); }

	default ExpressionList<Double> exp() { return toList().exp(); }

	@Deprecated
	default ExpressionList<Double> multiply(CollectionVariable<?> operands) {
		ExpressionList<Double> a = stream().collect(ExpressionList.collector());
		ExpressionList<Double> b = operands.stream().collect(ExpressionList.collector());
		return a.multiply(b);
	}

	default CollectionExpression delta(CollectionExpression target) {
		throw new UnsupportedOperationException();
	}

	/**
	 * If this {@link CollectionExpression} represents a subset of a larger set of values,
	 * this method can be used to provide the {@link TraversalPolicy} for the larger
	 * collection which may be useful in choosing the optimal behavior for the
	 * {@link CollectionExpression}.
	 */
	default void setTotalShape(TraversalPolicy shape) { }

	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		return Shape.super.containsIndex(index);
	}

	@Override
	default String describe() {
		return getShape().toStringDetail();
	}

	static CollectionExpression create(TraversalPolicy shape, Function<Expression<?>, Expression<?>> valueAt) {
		return new DefaultCollectionExpression(shape, valueAt);
	}

	static TraversableExpression traverse(Object o, LongFunction<Expression<?>> offset) {
		TraversableExpression exp = TraversableExpression.traverse(o);
		if (exp == null) {
			if (enableArrayTraversal && o instanceof Array) {
				return new TraversableExpression() {
					@Override
					public Expression getValue(Expression[] pos) {
						throw new UnsupportedOperationException();
					}

					@Override
					public Expression getValueAt(Expression index) {
						return ((Array) o).valueAt(index);
					}
				};
			}

			return null;
		}

		if (exp instanceof Shape) {
			return new RelativeTraversableExpression(((Shape) exp).getShape(), exp, offset);
		} else if (exp instanceof CollectionExpression) {
			return new RelativeTraversableExpression(((CollectionExpression) exp).getShape(), exp, offset);
		} else {
			return exp;
		}
	}
}
