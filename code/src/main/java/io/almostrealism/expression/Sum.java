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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.NoOpKernelStructureContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Sum<T extends Number> extends NAryExpression<T> {
	public Sum(Stream<Expression<? extends Number>> values) {
		super("+", (Stream) values);
	}

	public Sum(List<Expression<? extends Number>> values) {
		super((Class<T>) type(values), "+", (List) values);
	}

	public Sum(Expression<? extends Number>... values) {
		super((Class<T>) type(List.of(values)), "+", values);
	}

	@Override
	public OptionalInt upperBound() {
		List<OptionalInt> values = getChildren().stream()
				.map(e -> e.upperBound()).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size()) return OptionalInt.empty();
		return OptionalInt.of(values.stream().map(o -> o.getAsInt()).reduce(0, (a, b) -> a + b));
	}

	@Override
	public KernelSeries kernelSeries() {
		List<KernelSeries> children =
				getChildren().stream().map(e -> e.kernelSeries()).collect(Collectors.toList());

		if (children.stream().anyMatch(k -> !k.getPeriod().isPresent())) {
			// If any of the children are not periodic, then the sum cannot be periodic
			List<OptionalInt> scales = children.stream()
					.map(k -> k.getScale())
					.filter(o -> o.isPresent())
					.collect(Collectors.toList());
			if (scales.isEmpty()) return KernelSeries.infinite();
			if (scales.size() == 1) return KernelSeries.infinite(scales.get(0).getAsInt());
			return KernelSeries.infinite(scales.stream()
					.max(Comparator.comparing(a -> Integer.valueOf(a.getAsInt())))
					.get().getAsInt());
		} else {
			// If all of the children are periodic, just return a periodic series that is
			// compatible with all of the periods
			return KernelSeries.periodic(children.stream()
					.map(k -> k.getPeriod().getAsInt()).collect(Collectors.toList()));
		}
	}
	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return new Sum<>(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression delta(TraversalPolicy shape, Function<Expression, Predicate<Expression>> target) {
		return CollectionExpression.sum(shape,
				getChildren().stream().map(e -> e.delta(shape, target)).collect(Collectors.toList()));
	}

	@Override
	public List<Expression<?>> flatten() {
		List<Expression<?>> flat = super.flatten();

		List<Expression<?>> terms = flat.stream()
				.filter(e -> e instanceof Sum)
				.flatMap(e -> e.getChildren().stream())
				.collect(Collectors.toList());

		if (terms.size() == 0) return flat;

		List<Expression<?>> children = new ArrayList<>();
		terms.forEach(children::add);
		children.addAll(flat.stream()
				.filter(e -> !(e instanceof Sum))
				.collect(Collectors.toList()));

		return children;
	}

	@Override
	public Expression simplify(KernelStructureContext context) {
		List<Expression<?>> children = super.simplify(context).flatten().stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 0.0)
				.collect(Collectors.toList());

		if (children.size() == 1) return children.get(0);
		if (children.size() == 0) {
			return getType() == Integer.class ? new IntegerConstant(0) : new DoubleConstant(0.0);
		}

		if (context.getTraversalProvider() != null &&
				children.stream().allMatch(e -> e.isSingleIndexMasked())) {
			return context.getTraversalProvider()
					.generateReordering(generate(children))
					.populate(this);
		}

		List<Double> values = children.stream()
				.map(Expression::doubleValue)
				.filter(d -> d.isPresent())
				.map(d -> d.getAsDouble())
				.collect(Collectors.toList());

		if (values.size() <= 1) {
			return generate(children).populate(this);
		}

		children = children.stream()
				.filter(e -> !e.doubleValue().isPresent())
				.collect(Collectors.toList());

		double sum = values.stream().reduce(0.0, (a, b) -> a + b);

		if (sum == 0.0) {
			if (children.isEmpty())
				return getType() == Integer.class ? new IntegerConstant(0) : new DoubleConstant(0.0);
			if (children.size() == 1) return (Expression<Double>) children.get(0);
			return generate(children).populate(this);
		} else {
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.addAll(children);
			newChildren.add(getType() == Integer.class ? new IntegerConstant((int) sum) : new DoubleConstant(sum));
			if (newChildren.size() == 1) return (Expression<Double>) newChildren.get(0);
			return generate(newChildren).populate(this);
		}
	}

	@Override
	public Number kernelValue(int kernelIndex) {
		List<Number> values = getChildren().stream()
				.map(e -> e.kernelValue(kernelIndex))
				.collect(Collectors.toList());

		if (values.stream().anyMatch(v -> !(v instanceof Integer))) {
			return values.stream().mapToDouble(v -> v.doubleValue()).reduce(0.0, (a, b) -> a + b);
		} else {
			return values.stream().mapToInt(v -> v.intValue()).reduce(0, (a, b) -> a + b);
		}
	}

	public static <T> Expression<T> of(Expression... values) {
		return values.length == 1 ? values[0] : (Expression) new Sum(values);
	}
}
