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
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Product<T extends Number> extends NAryExpression<T> {
	protected Product(Stream<Expression<? extends Number>> values) {
		super("*", (Stream) values);
	}

	protected Product(List<Expression<Double>> values) {
		super((Class<T>) type(values), "*", (List) values);
	}

	protected Product(Expression<Double>... values) {
		super((Class<T>) type(List.of(values)), "*", values);
	}

	@Override
	public KernelSeries kernelSeries() {
		List<KernelSeries> children =
				getChildren().stream().map(e -> e.kernelSeries()).collect(Collectors.toList());

		if (children.stream().anyMatch(k -> !k.getPeriod().isPresent())) {
			// If any of the children are not periodic, then the product cannot be periodic
			return KernelSeries.product(children);
		} else {
			// If all of the children are periodic, just return the one with the largest period
			return children.stream()
					.max(Comparator.comparing(k -> k.getPeriod().getAsInt()))
					.get();
		}
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		List<OptionalInt> values = getChildren().stream()
				.map(e -> e.upperBound(context)).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size()) return OptionalInt.empty();
		return OptionalInt.of(values.stream().map(o -> o.getAsInt()).reduce(1, (a, b) -> a * b));
	}

	@Override
	public Number value(IndexValues indexValues) {
		List<Number> values = getChildren().stream()
				.map(e -> e.value(indexValues))
				.collect(Collectors.toList());

		if (values.stream().anyMatch(v -> !(v instanceof Integer))) {
			return values.stream().mapToDouble(v -> v.doubleValue()).reduce(1.0, (a, b) -> a * b);
		} else {
			return values.stream().mapToInt(v -> v.intValue()).reduce(1, (a, b) -> a * b);
		}
	}

	@Override
	public Number evaluate(Number... children) {
		double value = children[0].doubleValue();
		for (int i = 1; i < children.length; i++) {
			value = value * children[i].doubleValue();
		}

		return value;
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return (Expression) Product.of(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		List<Expression<?>> operands = getChildren();
		List<CollectionExpression> sum = new ArrayList<>();

		for (int i = 0; i < operands.size(); i++) {
			List<CollectionExpression> product = new ArrayList<>();

			for (int j = 0; j < operands.size(); j++) {
				CollectionExpression op = i == j ? operands.get(j).delta(target) :
						CollectionExpression.create(target.getShape(), operands.get(j));
				product.add(op);
			}

			sum.add(CollectionExpression.product(target.getShape(), product));
		}

		CollectionExpression result;

		if (sum.isEmpty()) {
			result = CollectionExpression.zeros(target.getShape());
		} else if (sum.size() == 1) {
			result = sum.get(0);
		} else {
			result = CollectionExpression.sum(target.getShape(), sum);
		}

		return ExpressionMatchingCollectionExpression.create(
				DefaultCollectionExpression.create(target.getShape(), idx -> this),
				target,
				CollectionExpression.create(target.getShape(), idx -> new IntegerConstant(1)),
				result);
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
	public Expression simplify(KernelStructureContext context) {
		if (getChildren().stream().anyMatch(e -> e.doubleValue().orElse(-1) == 0)) {
			return getType() == Integer.class ? new IntegerConstant(0) : new DoubleConstant(0.0);
		}

		Expression<?> flat = super.simplify(context);
		if (!(flat instanceof Product)) return flat;

		List<Expression<?>> children = flat.flatten().stream().collect(Collectors.toList());

		Mask mask = children.stream()
				.filter(e -> e instanceof Mask)
				.map(e -> (Mask) e)
				.findFirst()
				.orElse(null);

		if (mask != null) {
			children = new ArrayList<>(children.stream()
					.filter(e -> e != mask)
					.collect(Collectors.toList()));
			children.add(mask.getMaskedValue());
		}

		children = children.stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 1.0)
				.collect(Collectors.toList());


		Expression simple = null;

		if (children.size() == 1) {
			simple = children.get(0);
		} else if (children.isEmpty()) {
			simple = getType() == Integer.class ? new IntegerConstant(1) : new DoubleConstant(1.0);
		}

		List<Double> values = null;

		if (simple == null) {
			values = children.stream()
					.map(Expression::doubleValue)
					.filter(d -> d.isPresent())
					.map(d -> d.getAsDouble())
					.collect(Collectors.toList());

			if (values.size() <= 0) {
				simple = generate(children).populate(this);
			} else if (values.size() == 1) {
				if (values.get(0).doubleValue() == 0.0) {
					return getType() == Integer.class ? new IntegerConstant(0) : new DoubleConstant(0.0);
				} else {
					simple = generate(children).populate(this);
				}
			}
		}

		if (simple == null) {
			children = children.stream()
					.filter(e -> !e.doubleValue().isPresent())
					.collect(Collectors.toList());

			double product = values.stream().reduce(1.0, (a, b) -> a * b);

			if (product == 0.0) {
				return getType() == Integer.class ? new IntegerConstant(0) : new DoubleConstant(0.0);
			} else if (product == 1.0) {
				if (children.isEmpty()) {
					simple = getType() == Integer.class ? new IntegerConstant(1) : new DoubleConstant(1.0);
				} else if (children.size() == 1) {
					simple = children.get(0);
				} else {
					simple = generate(children).populate(this);
				}
			} else {
				List<Expression<?>> newChildren = new ArrayList<>();
				newChildren.addAll(children);
				newChildren.add(getType() == Integer.class ? new IntegerConstant((int) product) : new DoubleConstant(product));

				if (newChildren.size() == 1) {
					simple = newChildren.get(0);
				} else {
					simple = generate(newChildren).populate(this);
				}
			}
		}

		if (mask == null) {
			return simple;
		} else {
			return Mask.of(mask.getMask(), simple);
		}
	}

	public static Expression<?> of(Expression<?>... values) {
		if (values.length == 0) throw new IllegalArgumentException();
		if (values.length == 1) return values[0];

		if (Stream.of(values).anyMatch(e -> e.intValue().orElse(-1) == 0)) {
			return new IntegerConstant(0);
		}

		Optional<Mask> mask = Stream.of(values)
				.filter(e -> e instanceof Mask)
				.map(e -> (Mask) e)
				.findFirst();

		if (mask.isPresent()) {
			List<Expression> operands = Stream.of(values)
					.map(e -> e == mask.get() ? mask.get().getMaskedValue() : e)
					.collect(Collectors.toList());
			return Mask.of(mask.get().getMask(), Product.of(operands.toArray(new Expression[0])));
		}

		List<Expression> operands = Stream.of(values)
				.filter(e -> e.intValue().orElse(-1) != 1)
				.collect(Collectors.toList());

		if (operands.isEmpty()) return new IntegerConstant(1);
		if (operands.size() == 1) return operands.get(0);
		return new Product(operands);
	}
}
