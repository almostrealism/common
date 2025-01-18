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

package io.almostrealism.expression;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ExpressionCache;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.IntStream;

public class Minus<T extends Number> extends UnaryExpression<T> {
	public static boolean enableDistributive = true;

	protected Minus(Expression<? extends Number> value) {
		super((Class) value.getType(), "-", value);
	}

	@Override
	public OptionalInt intValue() {
		OptionalInt i = getChildren().get(0).intValue();
		if (i.isPresent()) return OptionalInt.of(i.getAsInt() * -1);
		return super.intValue();
	}

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble d = getChildren().get(0).doubleValue();
		if (d.isPresent()) return OptionalDouble.of(d.getAsDouble() * -1);
		return super.doubleValue();
	}

	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong i = getChildren().get(0).upperBound(context);
		if (i.isPresent()) {
			long value = i.getAsLong();
			if (value > 0) return OptionalLong.of(0);
			return OptionalLong.of(-value);
		}

		return super.upperBound(context);
	}

	@Override
	public boolean isPossiblyNegative() {
		return true;
	}

	@Override
	public Number value(IndexValues indexValues) {
		Number v = getChildren().get(0).value(indexValues);
		if (v instanceof Integer) return -1 * (Integer) v;
		return -1.0 * (Double) v;
	}

	@Override
	public Number evaluate(Number... children) {
		return -1 * children[0].doubleValue();
	}

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		IndexSequence seq = getChildren().get(0).sequence(index, len, limit);
		if (seq == null) return null;

		return seq.minus();
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.size() != 1)  throw new UnsupportedOperationException();
		return (Expression) Minus.of(children.get(0));
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return minus(target.getShape(), getChildren().get(0).delta(target));
	}

	public static Expression<?> of(Expression<?> value) {
		return Expression.process(create(value));
	}

	protected static Expression<?> create(Expression<?> value) {
		if (value instanceof Minus) {
			return value.getChildren().get(0);
		} else if (enableDistributive && value instanceof Product) {
			int c = IntStream.range(0, value.getChildren().size())
					.filter(i -> value.getChildren().get(i).doubleValue().isPresent())
					.findFirst().orElse(-1);

			if (c >= 0) {
				return Product.of(IntStream.range(0, value.getChildren().size()).mapToObj(i -> {
					if (i == c) return value.getChildren().get(i).minus();
					return value.getChildren().get(i);
				}).toArray(Expression[]::new));
			}
		}

		return new Minus(value);
	}
}
