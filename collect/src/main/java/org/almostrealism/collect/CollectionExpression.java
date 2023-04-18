package org.almostrealism.collect;

import io.almostrealism.code.ExpressionList;
import io.almostrealism.expression.Expression;

import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
		};
	}
}
