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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ExpressionCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Sum<T extends Number> extends NAryExpression<T> {
	public static boolean enableMinusSimplification = true;
	public static boolean enableConstantExtraction = true;
	public static boolean enableCoefficientExtraction = true;
	public static int maxOppositeDetectionDepth = 10;
	public static int maxDistinctDetectionWidth = 8;
	public static int maxCoefficientExtractionWidth = 8;

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
	public Expression<T> recreate(List<Expression<?>> children) {
		return Sum.of(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
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
	public Expression simplify(KernelStructureContext context, int depth) {
		Expression<?> simple = super.simplify(context, depth);
		if (!(simple instanceof Sum)) return simple;

		List<Expression<?>> children = new ArrayList<>();

		simple.flatten().stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 0.0)
				.forEach(children::add);

		if (children.size() == 1) return children.get(0);
		if (children.size() == 0) {
			return getType() == Integer.class ? new IntegerConstant(0) : new DoubleConstant(0.0);
		}

		if (enableMinusSimplification) {
			Set<Integer> removed = new HashSet<>();

			i: for (int i = 0; i < children.size(); i++) {
				if (removed.contains(i)) continue i;

				if (children.get(i) instanceof Minus) {
					j: for (int j = 0; j < children.size(); j++) {
						if (i == j || removed.contains(j)) continue j;

						if (children.get(j).equals(children.get(i).getChildren().get(0))) {
							removed.add(i);
							removed.add(j);
							children.set(i, new IntegerConstant(0));
							children.set(j, new IntegerConstant(0));
							continue i;
						}
					}
				}
			}

			if (!removed.isEmpty()) return Sum.of(children.toArray(Expression[]::new));
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

		if (isFP()) {
			return values.stream().mapToDouble(Number::doubleValue).reduce(0.0, Double::sum);
		} else {
			long l = values.stream().mapToLong(Number::longValue).reduce(0, Long::sum);
			if (getType() == Integer.class && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
				return (int) l;

			return l;
		}
	}

	public static <T> Expression<T> of(Expression... values) {
		return Expression.process(create(values));
	}

	protected static <T> Expression<T> create(Expression<?>... values) {
		double constant = 0.0;
		List<Expression<?>> operands;

		boolean fp = false;

		if (enableConstantExtraction) {
			operands = new ArrayList<>();

			e: for (Expression e : values) {
				if (e.isFP()) fp = true;
				if (e.longValue().orElse(-1) == 0) continue e;

				OptionalDouble d = e.doubleValue();

				if (d.isPresent()) {
					constant += d.getAsDouble();
				} else {
					operands.add(e);
				}
			}

			if (constant != 0.0) {
				Expression c = fp ? new DoubleConstant(constant) : ExpressionFeatures.getInstance().e((long) constant);
				operands.add(c);
			}
		} else {
			operands = Stream.of(values).filter(v -> v.intValue().orElse(-1) != 0).collect(Collectors.toList());
			fp = operands.stream().anyMatch(Expression::isFP);
		}

		if (operands.size() > 1 && operands.size() < maxDistinctDetectionWidth && operands.stream().distinct().count() == 1) {
			return (Expression) Product.of(operands.get(0), new IntegerConstant(operands.size()));
		}

		i: if (operands.size() == 2) {
			// A sum which contains a value and its opposite can be replaced with zero
			if (checkOpposite(operands.get(0), operands.get(1))) {
				return (Expression) new IntegerConstant(0);
			}

			// Detect the presence of an index child
			int index[] = IntStream.range(0, 2).filter(i -> operands.get(i) instanceof DefaultIndex &&
					!(operands.get(i) instanceof KernelIndex)).toArray();
			if (index.length != 1) break i;

			DefaultIndex idx = (DefaultIndex) operands.get(index[0]);
			Expression p = operands.get(index[0] == 0 ? 1 : 0);
			if (!(p instanceof Product)) break i;

			List<Expression> args = p.getChildren();
			if (args.size() != 2) break i;

			index = IntStream.range(0, 2).filter(i -> args.get(i) instanceof Index).toArray();
			if (index.length != 1) break i;

			Expression k = args.get(index[0] == 0 ? 1 : 0);
			OptionalInt v = k.intValue();
			if (!v.isPresent()) break i;

			OptionalLong r = idx.getLimit();
			if (!r.isPresent()) break i;

			if (v.getAsInt() == r.getAsLong()) {
				return (Expression) Index.child((Index) args.get(index[0]), idx);
			}
		}

		if (operands.size() > 1) {
			if (enableCoefficientExtraction) {
				// Combine any like terms
				List<Expression<?>> combinedOperands = new ArrayList<>();

				int operandIndex = 0;

				i: for (int i = 0; i < maxCoefficientExtractionWidth && operandIndex < operands.size(); i++) {
					Expression<?> t = operands.get(operandIndex);
					Optional<Term> d = extractCoefficients(t, operandIndex, operands, true);

					if (d.isPresent()) {
						combinedOperands.add(d.get().result(fp));
					} else {
						// The target should always at least match itself
						throw new UnsupportedOperationException();
					}
				}

				// Add all the non-zero terms as operands
				combinedOperands.stream()
						.filter(e -> e.doubleValue().orElse(1.0) != 0.0)
						.forEach(operands::add);
			} else {
				// Combine terms if all terms are like the first term
				Expression<?> t = operands.get(0).getChildren().isEmpty() ?
						operands.get(0) : operands.get(0).getChildren().get(0);
				Optional<Term> d = extractCoefficients(t, -1, operands, false);

				if (d.isPresent()) {
					if (fp) {
						return (Expression) t.multiply(d.get().getCoefficient());
					} else {
						return (Expression) t.multiply((long) d.get().getCoefficient());
					}
				}
			}
		}

		if (operands.isEmpty()) return (Expression) new IntegerConstant(0);
		return operands.size() == 1 ? (Expression) operands.get(0) : (Expression) new Sum(operands);
	}

	private static Optional<Term> extractCoefficients(Expression<?> target, int targetIndex, List<Expression<?>> children, boolean prune) {
		double constant = 0.0;

		List<Expression<?>> processed = new ArrayList<>();

		// Check if the target has a coefficient of its own
		if (targetIndex >= 0 && target instanceof Product && target.getChildren().size() == 2) {
			if (target.getChildren().get(0).doubleValue().isPresent()) {
				constant += target.getChildren().get(0).doubleValue().getAsDouble();
				processed.add(target);
				target = target.getChildren().get(1);
			} else if (target.getChildren().get(1).doubleValue().isPresent()) {
				constant += target.getChildren().get(1).doubleValue().getAsDouble();
				processed.add(target);
				target = target.getChildren().get(0);
			}
		}

		// If the target has not been processed already,
		// then there is no need to skip over it during
		// the actual extraction process
		if (processed.isEmpty()) {
			targetIndex = -1;
		}

		i: for (int i = 0; i < children.size(); i++) {
			if (i == targetIndex) {
				// The target was already processed
				continue i;
			}

			Expression<?> e = children.get(i);

			if (Objects.equals(target, e)) {
				constant += 1.0;
				processed.add(e);
			} else if (e instanceof Product && e.getChildren().size() == 2 && e.getChildren().contains(target)) {
				if (Objects.equals(target, e.getChildren().get(0)) && e.getChildren().get(1).doubleValue().isPresent()) {
					constant += e.getChildren().get(1).doubleValue().getAsDouble();
					processed.add(e);
				} else if (Objects.equals(target, e.getChildren().get(1)) && e.getChildren().get(0).doubleValue().isPresent()) {
					constant += e.getChildren().get(0).doubleValue().getAsDouble();
					processed.add(e);
				} else if (!prune) {
					return Optional.empty();
				}
			} else if (!prune) {
				return Optional.empty();
			}
		}

		if (prune) {
			children.removeAll(processed);
		}

		return Optional.of(new Term(constant, target));
	}

	private static boolean checkOpposite(Expression a, Expression b) {
		if (a.treeDepth() > maxOppositeDetectionDepth || b.treeDepth() > maxOppositeDetectionDepth) {
			return false;
		}

		if (a instanceof Minus) {
			if (a.getChildren().get(0).equals(b)) return true;
		} else if (b instanceof Minus) {
			if (b.getChildren().get(0).equals(a)) return true;
		}

		return false;
	}

	private static class Term {
		private double coefficient;
		private Expression<?> expression;

		public Term(double coefficient, Expression<?> expression) {
			this.coefficient = coefficient;
			this.expression = expression;
		}

		public double getCoefficient() { return coefficient; }
		public Expression<?> getExpression() { return expression; }

		public Expression<?> result(boolean fp) {
			if (fp) {
				return expression.multiply(coefficient);
			} else {
				return expression.multiply((long) coefficient);
			}
		}
	}
}
