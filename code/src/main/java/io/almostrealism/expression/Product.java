/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.kernel.ArrayIndexSequence;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Product<T extends Number> extends NAryExpression<T> {
	public static boolean enableMinusDetection = true;
	public static boolean enableConstantExtraction = true;
	public static boolean enableConstantExtractionValidation = false;
	public static boolean enableSort = true;

	protected Product(List<Expression<? extends Number>> values) {
		this((Class<T>) type(values), (List) values);
	}

	private Product(Class<T> type, List<Expression<T>> values) {
		super(type, "*", (List) values);

		if (enableConstantExtractionValidation &&
				values.stream().filter(v -> !v.doubleValue().isPresent()).count() == 0) {
			throw new IllegalArgumentException("Attempting to create a Product with all constant values");
		}
	}

	@Override
	public Expression withIndex(Index index, Expression<?> e) {
		Expression<T> result = super.withIndex(index, e);
		if (!(result instanceof Product)) return result;

		if (result.getChildren().stream().allMatch(v -> v.doubleValue().isPresent())) {
			double r = result.getChildren().stream()
					.mapToDouble(v -> v.doubleValue().getAsDouble()).reduce(1.0, (a, b) -> a * b);
			return result.getType() == Integer.class ? new IntegerConstant((int) r) : new DoubleConstant(r);
		}

		return result;
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
	public OptionalLong upperBound(KernelStructureContext context) {
		List<OptionalLong> values = getChildren().stream()
				.map(e -> e.upperBound(context)).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size()) return OptionalLong.empty();
		long v = values.stream().map(o -> o.getAsLong()).reduce(1L, (a, b) -> a * b);

		// Some of the children may have negative upper bounds, but that does not
		// guarantee that the resulting product will have a negative upper bound
		return OptionalLong.of(Math.abs(v));
	}

	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		List<OptionalLong> values = getChildren().stream()
				.map(e -> e.lowerBound(context)).filter(o -> o.isPresent())
				.collect(Collectors.toList());
		if (values.size() != getChildren().size()) return OptionalLong.empty();
		long v = values.stream()
				.map(o -> o.getAsLong())
				.reduce(1L, (a, b) -> a * b);

		if (v > 0 && getChildren().stream().anyMatch(Expression::isPossiblyNegative)) {
			// If any of the children can be negative, the resulting product could
			// be negative, so a more conservative lower bound should be negative
			return OptionalLong.of(-v);
		}

		return OptionalLong.of(v);
	}

	@Override
	public Optional<Boolean> isMultiple(Expression<?> e) {
		if (isFP() || e.isFP()) return Optional.empty();

		if (getChildren().stream()
				.anyMatch(c -> c.isMultiple(e).orElse(false))) {
			return Optional.of(true);
		}

		return Optional.empty();
	}

	@Override
	public Number value(IndexValues indexValues) {
		List<Number> values = getChildren().stream()
				.map(e -> e.value(indexValues))
				.collect(Collectors.toList());

		if (isFP()) {
			return values.stream().mapToDouble(Number::doubleValue).reduce(1.0, (a, b) -> a * b);
		} else {
			long l = values.stream().mapToLong(Number::longValue).reduce(1, (a, b) -> a * b);
			if (getType() == Integer.class && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
				return (int) l;

			return l;
		}
	}

	@Override
	public Number evaluate(Number... children) {
		double value = children[0].doubleValue();
		for (int i = 1; i < children.length; i++) {
			value = value * children[i].doubleValue();
		}

		return isFP() ? value : (long) value;
	}

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (isFP()) return super.sequence(index, len, limit);

		List<Expression<?>> constant = new ArrayList<>();
		List<Expression<?>> variable = new ArrayList<>();

		getChildren().forEach(e -> {
			if (e.doubleValue().isPresent()) {
				constant.add(e);
			} else {
				variable.add(e);
			}
		});

		long value = constant.stream()
				.mapToLong(e -> e.longValue().getAsLong())
				.reduce(1L, (a, b) -> a * b);
		if (variable.isEmpty()) return ArrayIndexSequence.of(value, len);
		if (variable.size() != 1) return super.sequence(index, len, limit);

		IndexSequence seq = variable.get(0).sequence(index, len, limit);
		if (seq == null) return null;

		return seq.multiply(value);
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		return (Expression) Product.of(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		List<Expression<?>> operands = getChildren();
		List<CollectionExpression<?>> sum = new ArrayList<>();

		for (int i = 0; i < operands.size(); i++) {
			List<CollectionExpression<?>> product = new ArrayList<>();

			for (int j = 0; j < operands.size(); j++) {
				CollectionExpression<?> op = i == j ? operands.get(j).delta(target) :
						new ConstantCollectionExpression(target.getShape(), operands.get(j));
				product.add(op);
			}

			sum.add(product(target.getShape(), product));
		}

		CollectionExpression<?> result;

		if (sum.isEmpty()) {
			result = constantZero(target.getShape());
		} else if (sum.size() == 1) {
			result = sum.get(0);
		} else {
			result = sum(target.getShape(), sum);
		}

		return ExpressionMatchingCollectionExpression.create(
				new ConstantCollectionExpression(target.getShape(), this),
				target,
				new ConstantCollectionExpression(target.getShape(), new IntegerConstant(1)),
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

	public static Expression<?> of(Expression<?>... values) {
		return Expression.process(create(values));
	}

	protected static Expression<?> create(Expression<?>... values) {
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

		double constant = 1.0;
		List<Expression> operands;

		boolean fp = false;

		if (enableConstantExtraction) {
			operands = new ArrayList<>();

			e: for (Expression e : values) {
				if (e.isFP()) fp = true;
				if (e.longValue().orElse(-1) == 1) continue e;

				OptionalDouble d = e.doubleValue();

				if (d.isPresent()) {
					constant *= d.getAsDouble();
				} else {
					operands.add(e);
				}
			}

			if (constant != 1.0) {
				Expression c = fp ? new DoubleConstant(constant) : ExpressionFeatures.getInstance().e((long) constant);
				operands.add(c);
			}
		} else {
			operands = Stream.of(values)
					.filter(e -> e.intValue().orElse(-1) != 1)
					.sorted(depthOrder())
					.collect(Collectors.toList());
		}

		if (operands.isEmpty()) return new IntegerConstant(1);
		if (operands.size() == 1) return operands.get(0);
		if (enableMinusDetection && operands.size() == 2) {
			if (operands.get(0).doubleValue().orElse(0.0) == -1.0) {
				return Minus.of(operands.get(1));
			} else if (operands.get(1).doubleValue().orElse(0.0) == -1.0) {
				return Minus.of(operands.get(0));
			}
		}

		if (enableSort)
			operands = operands.stream().sorted(depthOrder()).collect(Collectors.toList());

		// TODO  When ArithmeticGenerator is present, this should just delegate to ArithmeticGenerator::multiply
		// TODO  which handles this case, but may include other optimizations
		if (operands.size() == 2 && operands.get(0) instanceof ArithmeticGenerator && operands.get(1).longValue().isPresent()) {
			ArithmeticGenerator<?> ag = (ArithmeticGenerator<?>) operands.get(0);
			return ArithmeticGenerator.create(ag.getIndex(),
					ag.getScale() * operands.get(1).longValue().getAsLong(),
					ag.getGranularity(), ag.getMod());
		}

		return fp ? new Product(Double.class, operands) : new Product(operands);
	}
}
