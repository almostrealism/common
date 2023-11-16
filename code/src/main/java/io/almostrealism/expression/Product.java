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

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Product<T extends Number> extends NAryExpression<T> {
	public Product(Stream<Expression<? extends Number>> values) {
		super("*", (Stream) values);
	}

	public Product(List<Expression<Double>> values) {
		super((Class<T>) type(values), "*", (List) values);
	}

	public Product(Expression<Double>... values) {
		super((Class<T>) type(List.of(values)), "*", values);
	}

	@Override
	public OptionalInt upperBound() {
		List<OptionalInt> values = getChildren().stream()
				.map(e -> e.upperBound()).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size()) return OptionalInt.empty();
		return OptionalInt.of(values.stream().map(o -> o.getAsInt()).reduce(1, (a, b) -> a * b));
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return new Product<>(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression delta(TraversalPolicy shape, Function<Expression, Predicate<Expression>> target) {
		List<Expression<?>> operands = getChildren();
		List<CollectionExpression> sum = new ArrayList<>();


		for (int i = 0; i < operands.size(); i++) {
			List<CollectionExpression> product = new ArrayList<>();

			for (int j = 0; j < operands.size(); j++) {
				CollectionExpression op = i == j ? operands.get(j).delta(shape, target) : CollectionExpression.create(shape, operands.get(j));
				product.add(op);
			}

			sum.add(CollectionExpression.product(shape, product.stream()));
		}


		if (sum.isEmpty()) {
			return CollectionExpression.zeros(shape);
		} else if (sum.size() == 1) {
			return sum.get(0);
		} else {
			return CollectionExpression.sum(shape, sum.stream());
		}
	}

	@Override
	public List<Expression<?>> flatten() {
		List<Expression<?>> flat = super.flatten();

		List<Expression<?>> terms = flat.stream()
				.filter(e -> e instanceof Product)
				.flatMap(e -> e.getChildren().stream())
				.collect(Collectors.toList());

		if (terms.size() == 0) return flat;

		List<Expression<?>> children = new ArrayList<>();
		children.addAll(terms);
		children.addAll(flat.stream()
				.filter(e -> !(e instanceof Product))
				.collect(Collectors.toList()));


		return children;
	}

	@Override
	public Expression simplify() {
		List<Expression<?>> children = super.simplify().flatten().stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 1.0)
				.collect(Collectors.toList());

		if (children.size() == 1) return (Expression<Double>) children.get(0);
		if (children.size() == 0) return (Expression<Double>) getChildren().iterator().next();

		List<Double> values = children.stream()
				.map(Expression::doubleValue)
				.filter(d -> d.isPresent())
				.map(d -> d.getAsDouble())
				.collect(Collectors.toList());

		if (values.size() <= 1) {
			return generate(children);
		}

		children = children.stream()
				.filter(e -> !e.doubleValue().isPresent())
				.collect(Collectors.toList());

		double product = values.stream().reduce(1.0, (a, b) -> a * b);

		if (product == 0.0) {
			return getType() == Integer.class ? new IntegerConstant(0) : new DoubleConstant(0.0);
		} else if (product == 1.0) {
			if (children.isEmpty())
				return getType() == Integer.class ? new IntegerConstant(1) : new DoubleConstant(1.0);
			if (children.size() == 1) return (Expression<Double>) children.get(0);
			return generate(children);
		} else {
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.addAll(children);
			newChildren.add(getType() == Integer.class ? new IntegerConstant((int) product) : new DoubleConstant(product));
			if (newChildren.size() == 1) return (Expression<Double>) newChildren.get(0);
			return generate(newChildren);
		}
	}

	@Override
	public Number kernelValue(int kernelIndex) {
		List<Number> values = getChildren().stream()
				.map(e -> e.kernelValue(kernelIndex))
				.collect(Collectors.toList());

		if (values.stream().anyMatch(v -> !(v instanceof Integer))) {
			return values.stream().mapToDouble(v -> v.doubleValue()).reduce(1.0, (a, b) -> a * b);
		} else {
			return values.stream().mapToInt(v -> v.intValue()).reduce(1, (a, b) -> a * b);
		}
	}
}
