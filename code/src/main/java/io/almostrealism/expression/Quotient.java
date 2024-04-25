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
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Collectors;

public class Quotient<T extends Number> extends NAryExpression<T> {
	protected Quotient(List<Expression<?>> values) {
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
	public OptionalLong upperBound(KernelStructureContext context) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		OptionalLong l = getChildren().get(0).upperBound(context);
		OptionalLong r = getChildren().get(1).upperBound(context);
		if (l.isPresent() && r.isPresent()) {
			return OptionalLong.of((long) Math.ceil(l.getAsLong() / (double) r.getAsLong()));
		}

		return OptionalLong.empty();
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
	public IndexSequence sequence(Index index, long len, long limit) {
		if (getChildren().size() != 2 ||
				getChildren().get(1).longValue().isEmpty())
			return super.sequence(index, len, limit);

		IndexSequence seq = getChildren().get(0).sequence(index, len, limit);
		if (seq == null) return null;

		long divisor = getChildren().get(1).longValue().getAsLong();
		return seq.divide(divisor);
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
		CollectionExpression term1 = product(target.getShape(),
				List.of(numeratorDelta, new ConstantCollectionExpression(target.getShape(), denominator)));
		// f(x)g'(x)
		CollectionExpression term2 = product(target.getShape(),
				List.of(new ConstantCollectionExpression(target.getShape(), numerator), denominatorDelta));

		CollectionExpression derivativeNumerator =
				difference(target.getShape(), List.of(term1, term2)); // f'(x)g(x) - f(x)g'(x)
		CollectionExpression derivativeDenominator =
				new ConstantCollectionExpression(target.getShape(), new Product(denominator, denominator)); // [g(x)]^2
		return quotient(target.getShape(), List.of(derivativeNumerator, derivativeDenominator));
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

		if (children.size() == 2) {
			OptionalLong divisor = children.get(1).longValue();
			OptionalLong max = children.get(0).getLimit();

			if (divisor.isPresent()) {
				if (max.isPresent() && max.getAsLong() <= divisor.getAsLong()) {
					return new IntegerConstant(0);
				} else if (children.get(0) instanceof Sum) {
					Expression simple = trySumSimplify((Sum) children.get(0), divisor.getAsLong());
					if (simple != null) return simple;
				}
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

	private static Expression trySumSimplify(Sum<?> sum, long divisor) {
		if (sum.getChildren().size() != 2) return null;

		Product<?> product = (Product) sum.getChildren().stream()
				.filter(e -> e instanceof Product)
				.findFirst().orElse(null);
		if (product == null) return null;
		if (product.getChildren().size() != 2) return null;

		Expression<?> arg = product.getChildren()
				.stream().filter(e -> e.longValue().isEmpty())
				.findFirst().orElse(null);
		if (arg == null) return null;

		long constant = product.getChildren().stream()
				.map(Expression::longValue)
				.filter(OptionalLong::isPresent).findFirst()
				.map(OptionalLong::getAsLong).orElse(-1L);
		if (constant <= 0) return null;
		if (divisor % constant != 0) return null;
		if (constant > divisor) return null;

		Mod<?> mod = (Mod) sum.getChildren().stream()
				.filter(e -> e instanceof Mod)
				.findFirst().orElse(null);
		if (mod == null) return null;
		if (mod.isFP()) return null;
		if (!mod.getChildren().get(0).equals(arg)) return null;
		if (mod.getChildren().get(1).longValue().isEmpty()) return null;
		if (mod.getChildren().get(1).longValue().getAsLong() != constant) return null;

		return Quotient.of(arg, Constant.of(divisor / constant));
	}
}
