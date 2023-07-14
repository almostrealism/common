package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.TraversableExpressionComputation;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.BiFunction;

public class TraversableExpressionComputationTest implements CodeFeatures {
	protected <T extends PackedCollection<?>> TraversableExpressionComputation<T> pairSum(Producer a) {
		TraversalPolicy shape = shape(a);

		return new TraversableExpressionComputation<>(shape(a).replace(shape(1)),
				(BiFunction<TraversableExpression[], Expression, Expression>) (args, index) -> {
					return new Sum(args[1].getValueRelative(new IntegerConstant(0)),
							args[1].getValueRelative(new IntegerConstant(1)));
				}, a);
	}

	@Test
	public void pair() {
		int r = 3;
		int c = 2;

		PackedCollection<?> input = new PackedCollection<>(shape(r, c));
		input.fill(pos -> Math.random());

		TraversableExpressionComputation<?> sum = pairSum(p(input.traverse(1)));
		PackedCollection<?> out = sum.get().evaluate();

		for (int i = 0; i < r; i++) {
			double expected = input.valueAt(i, 0) + input.valueAt(i, 1);
			double actual = out.valueAt(i, 0);
			Assert.assertEquals(expected, actual, Math.pow(10, -10));
		}
	}

	@Test
	public void map() {
		int r = 3;
		int c = 2;

		PackedCollection<?> input = new PackedCollection<>(shape(r, c));
		input.fill(pos -> Math.random());

		CollectionProducerComputation<?> sum = c(p(input.traverse(1))).map(shape(1), v -> pairSum(v));
		PackedCollection<?> out = sum.get().evaluate();

		for (int i = 0; i < r; i++) {
			double expected = input.valueAt(i, 0) + input.valueAt(i, 1);
			double actual = out.valueAt(i, 0);
			Assert.assertEquals(expected, actual, Math.pow(10, -10));
		}
	}
}
