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
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

public class Quotient<T extends Number> extends NAryExpression<T> {
	protected Quotient(List<Expression<?>> values) {
		super((Class<T>) type(values), "/", values);
	}

	protected Quotient(Expression<Double>... values) {
		super((Class<T>) type(values), "/", values);
	}

	@Override
	public KernelSeries kernelSeries() {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		KernelSeries numerator = getChildren().get(0).kernelSeries();
		OptionalInt denominator = getChildren().get(1).intValue();

		if (denominator.isPresent()) {
			return numerator.scale(denominator.getAsInt());
		}

		return  KernelSeries.infinite();
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		OptionalInt l = getChildren().get(0).upperBound(context);
		OptionalInt r = getChildren().get(1).upperBound(context);
		if (l.isPresent() && r.isPresent()) {
			return OptionalInt.of((int) Math.ceil(l.getAsInt() / (double) r.getAsInt()));
		}

		return OptionalInt.empty();
	}

	@Override
	public Number value(IndexValues indexValues) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		Number numerator = getChildren().get(0).value(indexValues);
		Number denominator = getChildren().get(1).value(indexValues);

		if (numerator instanceof Integer && denominator instanceof Integer) {
			return ((Integer) numerator) / ((Integer) denominator);
		} else {
			return numerator.doubleValue() / denominator.doubleValue();
		}
	}

	@Override
	public Number evaluate(Number... children) {
		if (getType() == Integer.class) {
			int value = children[0].intValue();
			for (int i = 1; i < children.length; i++) {
				value = value / children[i].intValue();
			}

			return value;
		} else {
			double value = children[0].doubleValue();
			for (int i = 1; i < children.length; i++) {
				value = value / children[i].doubleValue();
			}

			return value;
		}
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return (Expression) Quotient.of(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		Expression numerator = getChildren().get(0);
		Expression denominator = getChildren().get(1);

		CollectionExpression numeratorDelta = numerator.delta(target);
		CollectionExpression denominatorDelta = denominator.delta(target);

		// f'(x)g(x)
		CollectionExpression term1 = CollectionExpression.product(target.getShape(),
				List.of(numeratorDelta, CollectionExpression.create(target.getShape(), denominator)));
		// f(x)g'(x)
		CollectionExpression term2 = CollectionExpression.product(target.getShape(),
				List.of(CollectionExpression.create(target.getShape(), numerator), denominatorDelta));

		CollectionExpression derivativeNumerator =
				CollectionExpression.difference(target.getShape(), List.of(term1, term2)); // f'(x)g(x) - f(x)g'(x)
		CollectionExpression derivativeDenominator =
				CollectionExpression.create(target.getShape(), new Product(denominator, denominator)); // [g(x)]^2
		return CollectionExpression.quotient(target.getShape(), List.of(derivativeNumerator, derivativeDenominator));
	}

	@Override
	public Expression simplify(KernelStructureContext context) {
		Expression<?> flat = super.simplify(context);
		if (!enableSimplification || !(flat instanceof Quotient)) return flat;

		List<Expression<?>> children = flat.getChildren().subList(1, flat.getChildren().size()).stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 1.0)
				.collect(Collectors.toList());
		children.add(0, flat.getChildren().get(0));

		if (children.isEmpty()) return new IntegerConstant(1);
		if (children.size() == 1) return children.get(0);

		if (children.get(0) instanceof Index && children.size() == 2) {
			OptionalInt divisor = children.get(1).intValue();
			OptionalInt max = ((Index) children.get(0)).getLimit();
			if (divisor.isPresent() && max.isPresent() && max.getAsInt() <= divisor.getAsInt()) {
				return new IntegerConstant(0);
			}
		}

		if (children.get(0).intValue().isPresent()) {
			int numerator = children.get(0).intValue().getAsInt();
			if (numerator == 0) return new IntegerConstant(0).toInt();

			int i;
			i: for (i = 1; i < children.size(); i++) {
				if (children.get(i).intValue().isPresent()) {
					numerator = numerator / children.get(i).intValue().getAsInt();
				} else {
					break i;
				}
			}

			if (i == children.size()) return new IntegerConstant(numerator).toInt();
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.add(new IntegerConstant(numerator).toInt());
			newChildren.addAll(children.subList(i, children.size()));
			children = newChildren;
		} else if (children.get(0).doubleValue().isPresent()) {
			double numerator = children.get(0).doubleValue().getAsDouble();
			if (numerator == 0) return new DoubleConstant(0.0);

			int i;
			i: for (i = 1; i < children.size(); i++) {
				if (children.get(i).doubleValue().isPresent()) {
					numerator = numerator / children.get(i).doubleValue().getAsDouble();
				} else {
					break i;
				}
			}

			if (i == children.size()) return new DoubleConstant(numerator);
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.add(new DoubleConstant(numerator));
			newChildren.addAll(children.subList(i, children.size()));
			children = newChildren;
		}

		return generate(children).populate(this);
	}

	public static Expression<?> of(Expression<?>... values) {
		if (values.length == 0) throw new IllegalArgumentException();
		if (values.length == 1) return values[0];

		List<Expression> operands = new ArrayList<>();
		operands.add(values[0]);
		for (int i = 1; i < values.length; i++) {
			if (values[i].intValue().orElse(-1) != 1) {
				operands.add(values[i]);
			}
		}

		if (operands.size() == 1) return operands.get(0);
		return new Quotient(operands);
	}
}
