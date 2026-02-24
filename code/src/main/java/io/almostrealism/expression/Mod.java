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
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ScopeSettings;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Mod<T extends Number> extends BinaryExpression<T> {
	public static boolean enableMod2Optimization = false;
	public static boolean enableInnerSumSimplify = true;
	public static boolean enableRedundantModReplacement = true;
	public static boolean enableRemoveMultiples = true;
	public static boolean enableSpanUpperBound = true;

	private boolean fp;

	protected Mod(Expression<T> a, Expression<T> b, boolean fp) {
		super(a.getType(), a, b);
		this.fp = fp;

		if (fp && !a.isFP() && !b.isFP()) {
			throw new UnsupportedOperationException();
		}

		if (!fp && (a.isFP() || b.isFP()))
			throw new UnsupportedOperationException();

		if (b.intValue().isPresent() && b.intValue().getAsInt() == 0) {
			warn("Module zero encountered while creating expression");
		}
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return fp ? "fmod(" + getChildren().get(0).getExpression(lang) + ", " + getChildren().get(1).getExpression(lang) + ")" :
				getChildren().get(0).getWrappedExpression(lang) + " % " + getChildren().get(1).getWrappedExpression(lang);
	}

	@Override
	public OptionalInt intValue() {
		Expression input = getChildren().get(0);
		Expression mod = getChildren().get(1);

		if (input.intValue().isPresent() && mod.intValue().isPresent() && mod.intValue().getAsInt() != 0) {
			return OptionalInt.of(input.intValue().getAsInt() % mod.intValue().getAsInt());
		}

		return super.intValue();
	}

	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values) && getChildren().get(1).isValue(values);
	}

	@Override
	public Number value(IndexValues indexValues) {
		if (fp) {
			return getChildren().get(0).value(indexValues).doubleValue() % getChildren().get(1).value(indexValues).doubleValue();
		} else {
			return adjustType(getType(),
					getChildren().get(0).value(indexValues).longValue()
							% getChildren().get(1).value(indexValues).longValue());
		}
	}

	@Override
	public Number evaluate(Number... children) {
		if (fp) {
			return children[0].doubleValue() % children[1].doubleValue();
		} else {
			return children[0].intValue() % children[1].intValue();
		}
	}

	@Override
	public KernelSeries kernelSeries() {
		KernelSeries input = getChildren().get(0).kernelSeries();
		OptionalDouble mod = getChildren().get(1).doubleValue();

		if (mod.isPresent() && mod.getAsDouble() == Math.floor(mod.getAsDouble())) {
			return input.loop((int) mod.getAsDouble());
		}

		return KernelSeries.infinite();
	}

	@Override
	public Optional<Set<Integer>> getIndexOptions(Index index) {
		int m = (int) getRight().longValue().orElse(Integer.MAX_VALUE);

		if (m > ScopeSettings.indexOptionLimit || !getLeft().equals(index)) {
			return super.getIndexOptions(index);
		}

		return Optional.of(IntStream.range(0, m).boxed().collect(Collectors.toSet()));
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong lower = getLeft().lowerBound(context);
		OptionalLong upper = getLeft().upperBound(context);
		OptionalLong m = getRight().longValue();

		if (!isFP() && m.isPresent()) {
			if (enableSpanUpperBound && lower.isPresent() && upper.isPresent()) {
				long top = upper.getAsLong();
				long bottom = lower.getAsLong();
				long ml = m.getAsLong();

				boolean contained = ml > bottom && ml < top;
				long span = top - bottom;
				if (!contained && span < ml) {
					// (1) The modulus is not between the lower and upper bound
					//     of the dividend,
					// and
					// (2) The span of the bounds is no greater than the modulo;
					//
					// then the upper bound of the result must obtain at
					// either the upper or lower bound of the dividend
					return OptionalLong.of(Math.max(lower.getAsLong() % ml, upper.getAsLong() % ml));
				}
			}

			return OptionalLong.of(m.getAsLong() - 1);
		}

		return getChildren().get(1).upperBound(context);
	}

	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		if (isFP() || getLeft().isPossiblyNegative() || getRight().isPossiblyNegative())
			return super.lowerBound(context);

		OptionalLong lower = getLeft().lowerBound(context);
		OptionalLong upper = getLeft().upperBound(context);
		OptionalLong m = getRight().longValue();

		if (lower.isPresent() && upper.isPresent() && m.isPresent()) {
			long span = upper.getAsLong() - lower.getAsLong();
			long ml = m.getAsLong();

			if (span < ml) {
				return OptionalLong.of(Math.min(lower.getAsLong() % ml, upper.getAsLong() % ml));
			}
		}

		return OptionalLong.of(0);
	}

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (!isInt() || getChildren().get(1).intValue().isEmpty())
			return super.sequence(index, len, limit);

		IndexSequence seq = getChildren().get(0).sequence(index, len, limit);
		if (seq == null) return null;

		return seq.mod(getChildren().get(1).intValue().getAsInt());
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return Mod.of(children.get(0), children.get(1), fp);
	}

	public static Expression of(Expression... inputs) {
		if (inputs.length != 2) {
			throw new UnsupportedOperationException();
		}

		return of(inputs[0], inputs[1], inputs[0].isFP() || inputs[1].isFP());
	}

	public static Expression of(Expression input, Expression mod) {
		return of(input, mod, true);
	}

	public static Expression of(Expression input, Expression mod, boolean fp) {
		return Expression.process(create(input, mod, fp));
	}

	protected static Expression create(Expression<?> input, Expression mod, boolean fp) {
		if (fp || (input.longValue().isEmpty() && mod.longValue().isEmpty())) {
			// There are no possible optimizations
			return new Mod(input, mod, fp);
		}

		OptionalLong id = input.longValue();

		if (mod.longValue().isEmpty()) {
			if (id.orElse(1) == 0) {
				return new IntegerConstant(0);
			}

			return new Mod(input, mod, fp);
		}

		long m = mod.longValue().getAsLong();
		if (m == 1) return new IntegerConstant(0);

		if (id.isPresent()) {
			return ExpressionFeatures.getInstance().e(id.getAsLong() % m);
		}

		if (input instanceof Mod && !input.isFP()) {
			Mod<Long> innerMod = (Mod) input;
			OptionalLong inMod = innerMod.getChildren().get(1).longValue();

			if (inMod.isPresent()) {
				long n = inMod.getAsLong();

				if (n == m || (enableRedundantModReplacement && m % n == 0)) {
					return innerMod;
				} else if (n % m == 0) {
					return new Mod(innerMod.getChildren().get(0),
							ExpressionFeatures.getInstance().e(m),
							false);
				}
			}
		} else if (enableRemoveMultiples) {
			int count = input.countNodes();

			w: while (input instanceof Sum && !input.isFP()) {
				input = Sum.of(input.getChildren().stream()
						.filter(e -> !e.isMultiple(mod).orElse(false))
						.toArray(Expression[]::new));
				if (input.countNodes() == count) break w;
				count = input.countNodes();
			}
		}

		OptionalLong u = input.upperBound();
		if (!input.isPossiblyNegative() && u.isPresent() && u.getAsLong() < m) {
			return input;
		}

		if (!input.isFP()) {
			if (input instanceof Mod) {
				Expression simple = tryModSimplify((Mod) input, m);
				if (simple != null)
					return simple;
			} else if (input instanceof Sum) {
				Expression simple = trySumSimplify((Sum) input, m);
				if (simple != null)
					return simple;
			}

			if (enableMod2Optimization && isPowerOf2(m) && m < Integer.MAX_VALUE) {
				return And.of(input, new IntegerConstant((int) m - 1));
			}
		}

		return new Mod(input, mod, fp);
	}

	private static Expression tryModSimplify(Mod<?> innerMod, long m) {
		OptionalLong inMod = innerMod.getChildren().get(1).longValue();

		if (inMod.isPresent()) {
			long n = inMod.getAsLong();

			if (n == m) {
				return innerMod;
			} else if (n % m == 0) {
				return new Mod(innerMod.getChildren().get(0), Constant.of(m), false);
			}
		}

		return null;
	}

	private static Expression trySumSimplify(Sum<?> innerSum, long m) {
		if (!enableInnerSumSimplify || innerSum.getChildren().size() != 2) return null;

		Product<?> product = (Product) innerSum.getChildren().stream()
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
		if (m % constant != 0) return null;
		if (constant > m) return null;

		Mod<?> mod = (Mod) innerSum.getChildren().stream()
				.filter(e -> e instanceof Mod)
				.findFirst().orElse(null);
		if (mod == null) return null;
		if (mod.isFP()) return null;
		if (!mod.getChildren().get(0).equals(arg)) return null;
		if (mod.getChildren().get(1).longValue().isEmpty()) return null;
		if (mod.getChildren().get(1).longValue().getAsLong() != constant) return null;

		if (constant == m) {
			Expression m0 = ExpressionFeatures.getInstance().e(constant);
			return new Mod(arg, m0, false);
		} else if (constant * constant == m) {
			Expression m0 = ExpressionFeatures.getInstance().e(constant);
			Expression m1 = ExpressionFeatures.getInstance().e(constant + 1);
			return Product.of(Mod.of(arg, m0, false), m1);
		} else {
			System.out.println("WARN: Inner sum simplify failed because " + constant + " * " + constant + " != " + m);
			return null;
		}
	}

	private static boolean isPowerOf2(long number) {
		return number > 0 && (number & (number - 1)) == 0;
	}
}
