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

import io.almostrealism.code.ExpressionList;
import io.almostrealism.expression.Expression;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

// TODO  Shouldn't this implement Shape?
public interface CollectionExpression extends TraversableExpression<Double> {

	TraversalPolicy getShape();

	default Stream<Expression<Double>> stream() {
		return IntStream.range(0, getShape().getTotalSize()).mapToObj(i -> getValueAt(e(i)));
	}

	default ExpressionList<Double> toList() {
		return stream().collect(ExpressionList.collector());
	}

	default Expression<Double> sum() { return toList().sum(); }

	default Expression<Double> max() { return toList().max(); }

	default ExpressionList<Double> exp() { return toList().exp(); }

	default ExpressionList<Double> multiply(CollectionVariable<?> operands) {
		ExpressionList<Double> a = stream().collect(ExpressionList.collector());
		ExpressionList<Double> b = operands.stream().collect(ExpressionList.collector());
		return a.multiply(b);
	}

	static CollectionExpression create(TraversalPolicy shape, Function<Expression<?>, Expression<Double>> valueAt) {
		return new CollectionExpression() {
			@Override
			public TraversalPolicy getShape() {
				return shape;
			}

			// TODO  This should be the default implementation for all CollectionExpressions
			@Override
			public Expression<Double> getValue(Expression... pos) {
				return getValueAt(getShape().index(pos));
			}

			@Override
			public Expression<Double> getValueAt(Expression index) {
				return valueAt.apply(index);
			}

			@Override
			public Expression<Double> getValueRelative(Expression index) {
				return CollectionExpression.super.getValueRelative(index);
			}
		};
	}

	static TraversableExpression traverse(Object o, IntFunction<Expression> offset) {
		TraversableExpression exp = TraversableExpression.traverse(o);
		if (exp == null) return null;

		if (exp instanceof Shape) {
			return new RelativeTraversableExpression(exp, offset.apply(((Shape) exp).getShape().getSize()));
		} else if (exp instanceof CollectionExpression) {
			return new RelativeTraversableExpression(exp, offset.apply(((CollectionExpression) exp).getShape().getSize()));
		} else {
			return exp;
		}
	}
}
