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

package io.almostrealism.expression;

import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Difference<T extends Number> extends NAryExpression<T> {
	public static boolean enableFactorySimplification = true;

	public Difference(Expression<T>... values) {
		super((Class<T>) type(List.of(values)), "-", values);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		List<OptionalLong> values = getChildren().stream()
				.map(e -> e.upperBound(context)).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size()) return OptionalLong.empty();
		return OptionalLong.of(IntStream.range(0, values.size())
				.mapToLong(i -> i == 0 ? values.get(i).getAsLong() : -1 * values.get(i).getAsLong())
				.reduce(0, (a, b) -> a + b));
	}

	@Override
	public Number value(IndexValues indexValues) {
		List<Number> values = getChildren().stream()
				.map(e -> e.value(indexValues))
				.collect(Collectors.toList());

		if (values.stream().anyMatch(v -> !(v instanceof Integer))) {
			return IntStream.range(0, values.size())
					.mapToDouble(i -> i == 0 ? values.get(i).doubleValue() : -1 * values.get(i).doubleValue())
					.reduce(0.0, (a, b) -> a + b);
		} else {
			return IntStream.range(0, values.size())
					.map(i -> i == 0 ? values.get(i).intValue() : -1 * values.get(i).intValue())
					.reduce(0, (a, b) -> a + b);
		}
	}

	@Override
	public Number evaluate(Number... children) {
		double value = children[0].doubleValue();
		for (int i = 1; i < children.length; i++) {
			value = value - children[i].doubleValue();
		}

		return value;
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return new Difference(children.toArray(new Expression[0]));
	}

	public static <T> Expression<T> of(Expression... values) {
		if (values.length == 0) throw new IllegalArgumentException();
		if (!enableFactorySimplification) return new Difference(values);

		Expression first = values[0];
		if (first.doubleValue().orElse(-1.0) == 0.0) {
			return Sum.of(values).minus();
		}

		values = Stream.of(values).skip(1)
				.filter(e -> e.doubleValue().orElse(-1) != 0.0)
				.toArray(Expression[]::new);
		if (values.length == 0) return first;
		if (values.length == 1) return new Difference(first, values[0]);
		return new Difference(Stream.concat(Stream.of(first), Stream.of(values))
				.toArray(Expression[]::new));
	}
}
