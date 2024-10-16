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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.function.BiFunction;
import java.util.function.Function;

public class TraversableExpressionComputationTest implements TestFeatures {
	protected <T extends PackedCollection<?>> DefaultTraversableExpressionComputation<T> pairSum(Producer a) {
		TraversalPolicy shape = shape(a).replace(shape(1));

		return new DefaultTraversableExpressionComputation<>(null, shape,
				(Function<TraversableExpression[], CollectionExpression>)
						(args) ->
								DefaultCollectionExpression.create(shape,
										idx ->
												Sum.of(args[1].getValueRelative(new IntegerConstant(0)),
										args[1].getValueRelative(new IntegerConstant(1)))), a);
	}

	@Test
	public void pair() {
		int r = 3;
		int c = 2;

		PackedCollection<?> input = new PackedCollection<>(shape(r, c));
		input.fill(pos -> Math.random());

		DefaultTraversableExpressionComputation<?> sum = pairSum(p(input.traverse(1)));
		PackedCollection<?> out = sum.get().evaluate();

		for (int i = 0; i < r; i++) {
			double expected = input.valueAt(i, 0) + input.valueAt(i, 1);
			double actual = out.valueAt(i, 0);
			assertEquals(expected, actual);
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
			assertEquals(expected, actual);
		}
	}
}
