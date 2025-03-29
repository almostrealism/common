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
	public static boolean enableDistributiveSum = true;
	public static boolean enableFpDivisorReplacement = true;
	public static boolean enableExpandedDistributiveSum = true;
	public static boolean enableProductModSimplify = true;
	public static boolean enableDenominatorCollapse = true;
	public static boolean enableRequireNonNegative = true;
	public static boolean enableBoundedNumeratorReplace = true;
	public static boolean enableLowerBoundedNumeratorReplace = true;
	public static boolean enableArithmeticGenerator = true;

	public static long maxCombinedDenominator = Integer.MAX_VALUE;

	protected Quotient(List<Expression<?>> values) {
		super((Class<T>) type(values), "/", values);
	}

	public Expression<?> getNumerator() { return getChildren().get(0); }

	public Expression<?> getDenominator() {
		// TODO  This should be supported
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		return getChildren().get(1);
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

		return KernelSeries.infinite();
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		OptionalLong l = getChildren().get(0).upperBound(context);

		if (l.isPresent()) {
			OptionalLong r = getChildren().get(1).longValue();
			boolean ceil = isFP();

			if (!r.isPresent()) {
				r = getChildren().get(1).lowerBound(context);
				ceil = true;
			}

			if (r.isPresent()) {
				long v;

				if (ceil) {
					v = (long) Math.ceil(l.getAsLong() / (double) r.getAsLong());
				} else {
					v = l.getAsLong() / r.getAsLong();
				}

				// Some of the children may have negative bounds, but that does not
				// guarantee that the resulting quotient will have a negative upper bound
				return OptionalLong.of(Math.abs(v));
			}
		}

		return OptionalLong.empty();
	}

	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		OptionalLong l = getChildren().get(0).lowerBound(context);

		if (l.isPresent()) {
			OptionalLong r = getChildren().get(1).longValue();
			boolean floor = isFP();
			if (!r.isPresent()) {
				r = getChildren().get(1).upperBound(context);
			}

			if (r.isPresent()) {
				long v;

				if (floor) {
					v = (long) Math.floor(l.getAsLong() / (double) r.getAsLong());
				} else {
					v = l.getAsLong() / r.getAsLong();
				}

				if (getChildren().stream().anyMatch(Expression::isPossiblyNegative)) {
					// If any of the children can be negative, the resulting quotient could be
					// negative, so a more conservative lower bound should be negative
					return OptionalLong.of(-Math.abs(v));
				} else {
					if (v < 0) {
						// The result should never be negative if none of the children can be negative
						throw new UnsupportedOperationException();
					}

					return OptionalLong.of(Math.abs(v));
				}
			}
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

		if (isFP()) {
			return numerator.doubleValue() / denominator.doubleValue();
		} else {
			return adjustType(getType(), numerator.longValue() / denominator.longValue());
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
	public Expression<T> recreate(List<Expression<?>> children) {
		return (Expression) Quotient.of(children.toArray(new Expression[0]));
	}

	@Override
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		if (getChildren().size() > 2)
			throw new UnsupportedOperationException();

		Expression numerator = getChildren().get(0);
		Expression denominator = getChildren().get(1);

		CollectionExpression<?> numeratorDelta = numerator.delta(target);
		CollectionExpression<?> denominatorDelta = denominator.delta(target);

		// f'(x)g(x)
		CollectionExpression<?> term1 = product(target.getShape(),
				List.of(numeratorDelta, new ConstantCollectionExpression(target.getShape(), denominator)));
		// f(x)g'(x)
		CollectionExpression<?> term2 = product(target.getShape(),
				List.of(new ConstantCollectionExpression(target.getShape(), numerator), denominatorDelta));

		CollectionExpression<?> derivativeNumerator =
				difference(target.getShape(), List.of(term1, term2)); // f'(x)g(x) - f(x)g'(x)
		CollectionExpression<?> derivativeDenominator =
				new ConstantCollectionExpression(target.getShape(),
//						new Product(List.of(denominator, denominator))); // [g(x)]^2
						Product.of(denominator, denominator)); // [g(x)]^2
		return quotient(target.getShape(), List.of(derivativeNumerator, derivativeDenominator));
	}

	@Override
	public Expression simplify(KernelStructureContext context, int depth) {
		Expression<?> flat = super.simplify(context, depth);
		if (!(flat instanceof Quotient)) return flat;

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
				boolean pos = !enableRequireNonNegative || !children.get(0).isPossiblyNegative();

				if (pos && max.isPresent() && max.getAsLong() <= divisor.getAsLong()) {
					return new IntegerConstant(0);
				} else if (children.get(0) instanceof Sum) {
					Expression simple = trySumSimplify((Sum) children.get(0), divisor.getAsLong());
					if (simple != null) return simple;
				} else if (children.get(0) instanceof Product) {
					Expression simple = tryProductSimplify((Product) children.get(0), divisor.getAsLong());
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
		return Expression.process(create(values));
	}

	protected static Expression<?> create(Expression<?>... values) {
		if (values.length == 0) throw new IllegalArgumentException();
		if (values.length == 1) return values[0];

		boolean fp = values[0].isFP();

		List<Expression<?>> operands = new ArrayList<>();
		operands.add(values[0]);
		for (int i = 1; i < values.length; i++) {
			if (values[i].intValue().orElse(-1) != 1) {
				operands.add(values[i]);
				fp = values[i].isFP();
			} else if (values[i].isFP()) {
				fp = true;
			}
		}

		if (operands.size() == 1) return operands.get(0);
		if (operands.size() > 2) return new Quotient(operands);

		if (values[0] instanceof ArithmeticGenerator) {
			return ((ArithmeticGenerator) values[0]).divide(operands.get(1));
		}

		Expression<?> numerator = operands.get(0);
		Expression<?> denominator = operands.get(1);

		OptionalLong d = denominator.longValue();

		OptionalLong lower = numerator.lowerBound();
		OptionalLong upper = numerator.upperBound();

		if (enableBoundedNumeratorReplace && !numerator.isPossiblyNegative() && d.isPresent() &&
				numerator.upperBound().orElse(Long.MAX_VALUE) < d.getAsLong()) {
			return new IntegerConstant(0);
		} else if (enableLowerBoundedNumeratorReplace && !fp && d.isPresent() && lower.isPresent() && upper.isPresent()) {
			double low = Math.floor(upper.getAsLong() / (double) d.getAsLong());
			double high = Math.floor(lower.getAsLong() / (double) d.getAsLong());

			if (low == high) {
				return ExpressionFeatures.getInstance().e((long) low);
			}
		}

		if (enableDenominatorCollapse && numerator instanceof Quotient && d.isPresent()) {
			if (numerator.getChildren().size() == 2) {
				OptionalLong altDenominator = numerator.getChildren().get(1).longValue();
				if (altDenominator.isPresent() && Math.abs(altDenominator.getAsLong() * d.getAsLong()) <= maxCombinedDenominator) {
					return numerator.getChildren().get(0).divide(
							altDenominator.getAsLong() * d.getAsLong());
				}
			}
		} else if (numerator instanceof Product) {
			if (d.isPresent()) {
				// When dividing a product that includes a constant value,
				// by the same constant value, the result can be simplified
				// to a product of the remaining values without the constant
				long constant = numerator.getChildren().stream()
						.mapToLong(e -> e.longValue().orElse(1))
						.reduce(1, (a, b) -> a * b);

				if (constant == d.getAsLong()) {
					return Product.of(numerator.getChildren().stream()
							.filter(e -> e.longValue().isEmpty()).toArray(Expression[]::new));
				}
			} else if (enableFpDivisorReplacement && denominator.doubleValue().isPresent()) {
				// When dividing a product that includes a floating-point constant value,
				// the result can be simplified to a product of the remaining values and
				// the constant value divided by the divisor
				double constant = numerator.getChildren().stream()
						.mapToDouble(e -> e.doubleValue().orElse(1))
						.reduce(1, (a, b) -> a * b);
				if (constant != 1.0) {
					List<Expression> args = new ArrayList<>();
					numerator.getChildren().stream()
							.filter(e -> e.doubleValue().isEmpty()).forEach(args::add);
					args.add(new DoubleConstant(constant / denominator.doubleValue().getAsDouble()));
					return Product.of(args.toArray(Expression[]::new));
				}
			}
		} else if (numerator instanceof Sum) {
			OptionalLong divisor = denominator.longValue();

			if (enableDistributiveSum && !(numerator instanceof Index) &&
					!numerator.isFP() && divisor.isPresent()) {
				List<Expression<?>> products = new ArrayList<>();
				long total = 0;
				int unknown = 0;

				// Identify all products which include a term that is a multiple
				// of the divisor, and all constant terms
				c: for (Expression<?> child : numerator.getChildren()) {
					if (child.isFP()) {
						throw new IllegalArgumentException("Floating point term discovered in an integer sum");
					}

					if (child.longValue().isPresent()) {
						total += child.longValue().getAsLong();
					} else if (child instanceof Product && findDivisibleTerm(child, divisor.getAsLong()) != null) {
						products.add(child);
					} else {
						unknown++;
					}
				}

				// If all children are integer multiples of the divisor (or constant values)
				// then it is safe to apply the division to each term in the sum
				if (unknown == 0) {
					List<Expression<?>> newChildren = new ArrayList<>();
					newChildren.addAll(products.stream()
							.map(e -> e.divide(divisor.getAsLong()))
							.collect(Collectors.toList()));
					newChildren.add(ExpressionFeatures.getInstance().e(total / divisor.getAsLong()));
					return Sum.of(newChildren.toArray(new Expression[0]));
				}

				// If there is only one term that may not be an integer multiple of the divisor,
				// then it is also safe to apply division to each term in the sum (that term
				// is the only possible source of a remainder, which will be discarded without
				// the chance to combine with other values to exceed the divisor)
				if (enableExpandedDistributiveSum && unknown == 1 && total == 0.0) {
					return Sum.of(numerator.getChildren().stream()
							.map(e -> e.divide(divisor.getAsLong()))
							.toArray(Expression[]::new));
				}
			}
		} else if (enableArithmeticGenerator && !fp && numerator instanceof Mod) {
			Expression<?> u = ((BinaryExpression) numerator).getLeft();
			OptionalLong m = ((BinaryExpression) numerator).getRight().longValue();

			if (u instanceof Index && m.isPresent()) {
				return new ArithmeticGenerator<>(u, 1, d.getAsLong(), m.getAsLong());
			}
		}

		if (numerator.doubleValue().isPresent() && denominator.doubleValue().isPresent()) {
			double r = numerator.doubleValue().getAsDouble() / denominator.doubleValue().getAsDouble();
			return fp ? new DoubleConstant(r) : ExpressionFeatures.getInstance().e((long) r);
		}

		return new Quotient(operands);
	}

	private static Expression findDivisibleTerm(Expression<?> e, long divisor) {
		return e.getChildren().stream()
				.filter(c -> c.longValue().isPresent() && c.longValue().getAsLong() % divisor == 0 && c.longValue().getAsLong() > 0)
				.findFirst().orElse(null);
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

		Expression d = ExpressionFeatures.getInstance().e(divisor / constant);
		return Quotient.of(arg, d);
	}

	private static Expression tryProductSimplify(Product<?> p, long divisor) {
		if (!enableProductModSimplify) return null;
		if (divisor <= 1) return null;
		if (p.isFP() || p.getChildren().size() != 2) return null;
		if (p.getChildren().get(1).longValue().orElse(-1) != (divisor + 1)) return null;

		Expression<?> mod = p.getChildren().get(0);
		if (!(mod instanceof Mod)) return null;

		if (mod.getChildren().get(1).longValue().orElse(-1) != divisor) return null;
		if (mod.getChildren().get(0).isPossiblyNegative()) return null;

		// The expression: ((x % a) * (a + 1)) / a
		// Can be simplified to: (x % a) + (x % a) / a
		// (And since x % a never exceeds a, this is
		// equivalent to just x % a)
		return mod.getChildren().get(0).imod(divisor);
	}
}
