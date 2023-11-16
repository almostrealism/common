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

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Difference<T extends Number> extends NAryExpression<T> {
	public Difference(Expression<Double>... values) {
		super((Class<T>) type(List.of(values)), "-", values);
	}

	@Override
	public OptionalInt upperBound() {
		List<OptionalInt> values = getChildren().stream()
				.map(e -> e.upperBound()).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size()) return OptionalInt.empty();
		return OptionalInt.of(IntStream.range(0, values.size())
				.map(i -> i == 0 ? values.get(i).getAsInt() : -1 * values.get(i).getAsInt())
				.reduce(0, (a, b) -> a + b));
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return new Difference(children.toArray(new Expression[0]));
	}

	@Override
	public Number kernelValue(int kernelIndex) {
		List<Number> values = getChildren().stream()
				.map(e -> e.kernelValue(kernelIndex))
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
}
