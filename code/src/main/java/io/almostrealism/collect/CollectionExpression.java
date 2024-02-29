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
import io.almostrealism.expression.Difference;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

// TODO  Shouldn't this implement Shape?
public interface CollectionExpression extends TraversableExpression<Double> {

	TraversalPolicy getShape();

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

	default CollectionExpression delta(TraversalPolicy targetShape, Function<Expression, Predicate<Expression>> target) {
		throw new UnsupportedOperationException();
	}

	static CollectionExpression sum(TraversalPolicy shape, List<CollectionExpression> operands) {
		return create(shape, idx -> Sum.of(operands.stream().map(o -> o.getValueAt(idx)).toArray(Expression[]::new)));
	}

	static CollectionExpression difference(TraversalPolicy shape, List<CollectionExpression> operands) {
		return create(shape, idx -> Difference.of(operands.stream().map(o -> o.getValueAt(idx)).toArray(Expression[]::new)));
	}

	static CollectionExpression product(TraversalPolicy shape, List<CollectionExpression> operands) {
		return create(shape, idx -> Product.of(operands.stream().map(o -> o.getValueAt(idx)).toArray(Expression[]::new)));
	}

	static CollectionExpression quotient(TraversalPolicy shape, List<CollectionExpression> operands) {
		return create(shape, idx -> Quotient.of(operands.stream().map(o -> o.getValueAt(idx)).toArray(Expression[]::new)));
	}

	static CollectionExpression conditional(TraversalPolicy shape, Expression<Boolean> condition,
											CollectionExpression positive, CollectionExpression negative) {
		return create(shape, idx -> condition.conditional(positive.getValueAt(idx), negative.getValueAt(idx)));
	}

	static CollectionExpression zeros(TraversalPolicy shape) {
		return create(shape, new IntegerConstant(0));
	}

	static CollectionExpression create(TraversalPolicy shape, Expression<?> valueAt) {
		return create(shape, idx -> valueAt);
	}

	static CollectionExpression create(TraversalPolicy shape, Function<Expression<?>, Expression<?>> valueAt) {
		if (shape == null) {
			throw new IllegalArgumentException("Shape is required");
		}

		return new CollectionExpression() {
			@Override
			public TraversalPolicy getShape() {
				return shape;
			}

			@Override
			public Expression<Double> getValueAt(Expression index) {
				return (Expression) valueAt.apply(index);
			}

			@Override
			public Expression<Double> getValueRelative(Expression index) {
				return CollectionExpression.super.getValueRelative(index);
			}

			@Override
			public CollectionExpression delta(TraversalPolicy targetShape, Function<Expression, Predicate<Expression>> target) {
				return createDelta(this, targetShape, target);
			}
		};
	}

	static CollectionExpression createDelta(CollectionExpression exp, TraversalPolicy targetShape, Function<Expression, Predicate<Expression>> target) {
		return new CollectionExpression() {
			@Override
			public TraversalPolicy getShape() {
				return exp.getShape();
			}

			@Override
			public Expression<Double> getValueAt(Expression index) {
				return exp.getValueAt(index.divide(targetShape.getTotalSize()))
										.delta(targetShape, target)
						.getValueAt(index.imod(targetShape.getTotalSize()));
			}

			@Override
			public CollectionExpression delta(TraversalPolicy targetShape, Function<Expression, Predicate<Expression>> target) {
				return createDelta(this, targetShape, target);
			}
		};
	}

	static TraversableExpression traverse(Object o, IntFunction<Expression> offset) {
		TraversableExpression exp = TraversableExpression.traverse(o);
		if (exp == null) return null;

		if (exp instanceof Shape) {
			return new RelativeTraversableExpression(((Shape) exp).getShape(), exp, offset);
		} else if (exp instanceof CollectionExpression) {
			return new RelativeTraversableExpression(((CollectionExpression) exp).getShape(), exp, offset);
		} else {
			return exp;
		}
	}
}
