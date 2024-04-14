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
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelIndexChild;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Sum<T extends Number> extends NAryExpression<T> {
	protected Sum(Stream<Expression<? extends Number>> values) {
		super("+", (Stream) values);
	}

	protected Sum(List<Expression<? extends Number>> values) {
		super((Class<T>) type(values), "+", (List) values);
	}

	protected Sum(Expression<? extends Number>... values) {
		super((Class<T>) type(List.of(values)), "+", values);
	}

	@Override
	public Expression withIndex(Index index, Expression<?> e) {
		Expression<T> result = super.withIndex(index, e);
		if (!(result instanceof Sum)) return result;

		if (result.getChildren().stream().allMatch(v -> v.doubleValue().isPresent())) {
			double r = result.getChildren().stream()
					.mapToDouble(v -> v.doubleValue().getAsDouble()).reduce(0.0, (a, b) -> a + b);
			return result.getType() == Integer.class ? new IntegerConstant((int) r) : new DoubleConstant(r);
		}

		return result;
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		List<OptionalLong> values = getChildren().stream()
				.map(e -> e.upperBound(context)).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size())
			return OptionalLong.empty();
		return OptionalLong.of(values.stream().map(o -> o.getAsLong()).reduce(0L, (a, b) -> a + b));
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
	public Number evaluate(Number... children) {
		double value = children[0].doubleValue();
		for (int i = 1; i < children.length; i++) {
			value = value + children[i].doubleValue();
		}

		return value;
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		return Sum.of(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		CollectionExpression delta = sum(target.getShape(),
				getChildren().stream().map(e -> e.delta(target)).collect(Collectors.toList()));
		return ExpressionMatchingCollectionExpression.create(
				new ConstantCollectionExpression(target.getShape(), this),
				target,
				new ConstantCollectionExpression(target.getShape(), new IntegerConstant(1)),
				delta);
	}

	@Override
	public List<Expression<?>> flatten() {
		List<Expression<?>> flat = super.flatten();

		List<Expression<?>> terms = flat.stream()
				.filter(e -> e instanceof Sum)
				.flatMap(e -> e.flatten().stream())
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
		Expression<?> simple = super.simplify(context);
		if (!(simple instanceof Sum)) return simple;

		List<Expression<?>> children = simple.flatten().stream()
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
			if (children.size() == 1) return children.get(0);
			return generate(children).populate(this);
		} else {
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.addAll(children);
			newChildren.add(getType() == Integer.class ? new IntegerConstant((int) sum) : new DoubleConstant(sum));
			if (newChildren.size() == 1) return newChildren.get(0);
			return generate(newChildren).populate(this);
		}
	}

	@Override
	public Number value(IndexValues indexValues) {
		List<Number> values = getChildren().stream()
				.map(e -> e.value(indexValues))
				.collect(Collectors.toList());

		if (values.stream().anyMatch(v -> !(v instanceof Integer))) {
			return values.stream().mapToDouble(v -> v.doubleValue()).reduce(0.0, (a, b) -> a + b);
		} else {
			return values.stream().mapToInt(v -> v.intValue()).reduce(0, (a, b) -> a + b);
		}
	}

	public static <T> Expression<T> of(Expression... values) {
		List<Expression> operands =
				Stream.of(values).filter(v -> v.intValue().orElse(-1) != 0).collect(Collectors.toList());
		i: if (operands.size() == 2) {
			int index[] = IntStream.range(0, 2).filter(i -> operands.get(i) instanceof DefaultIndex &&
					!(operands.get(i) instanceof KernelIndex)).toArray();
			if (index.length != 1) break i;

			DefaultIndex idx = (DefaultIndex) operands.get(index[0]);
			Expression p = operands.get(index[0] == 0 ? 1 : 0);
			if (!(p instanceof Product)) break i;

			List<Expression> args = p.getChildren();
			if (args.size() != 2) break i;

			index = IntStream.range(0, 2).filter(i -> args.get(i) instanceof KernelIndex).toArray();
			if (index.length != 1) break i;

			Expression k = args.get(index[0] == 0 ? 1 : 0);
			OptionalInt v = k.intValue();
			if (!v.isPresent()) break i;

			OptionalLong r = idx.getLimit();
			if (!r.isPresent()) break i;

			if (v.getAsInt() == r.getAsLong()) {
				return (Expression) new KernelIndexChild(((KernelIndex) args.get(index[0])).getContext(), idx);
			}
		}

		if (operands.isEmpty()) return (Expression) new IntegerConstant(0);
		return operands.size() == 1 ? operands.get(0) : (Expression) new Sum(operands);
	}
}
