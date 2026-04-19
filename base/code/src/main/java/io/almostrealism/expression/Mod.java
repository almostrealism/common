/*
 * Copyright 2026 Michael Murray
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
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.sequence.KernelSeries;
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

/**
 * A modulo expression ({@code a % b}) for both integer and floating-point operands.
 *
 * <p>Provides multiple simplification passes including constant folding, redundant-mod
 * elimination, inner-sum rewriting, and power-of-two optimisation. Integer and
 * floating-point modes are distinguished by the {@code fp} flag.</p>
 *
 * @param <T> the numeric result type
 */
public class Mod<T extends Number> extends BinaryExpression<T> {
	/**
	 * When {@code true}, {@code input % 2^n} is replaced with a bitwise-AND
	 * {@code input & (2^n - 1)} for integer expressions.
	 */
	public static boolean enableMod2Optimization = false;

	/**
	 * When {@code true}, mod applied to a sum of the form {@code (k * x + (x % k)) % k^2}
	 * is rewritten to a simpler product expression.
	 */
	public static boolean enableInnerSumSimplify = true;

	/**
	 * When {@code true}, a redundant outer mod is replaced by its inner mod operand
	 * when the outer modulus is a multiple of the inner modulus.
	 */
	public static boolean enableRedundantModReplacement = true;

	/**
	 * When {@code true}, multiples of the modulus are removed from integer sum operands.
	 */
	public static boolean enableRemoveMultiples = true;

	/**
	 * When {@code true}, the upper bound of a mod expression is reported as
	 * {@code modulus - 1} when the modulus is a known constant.
	 */
	public static boolean enableSpanUpperBound = true;

	/** Whether this is a floating-point modulo ({@code true}) or integer modulo ({@code false}). */
	private boolean fp;

	/**
	 * Constructs a modulo expression.
	 *
	 * @param a  the dividend
	 * @param b  the divisor
	 * @param fp {@code true} for floating-point modulo, {@code false} for integer modulo
	 */
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

	/** {@inheritDoc} Returns 8, reflecting the cost of the {@code fmod()}/{@code %} operation. */
	@Override
	public int getComputeCost() { return 8; }

	@Override
	public String getExpression(LanguageOperations lang) {
		if (fp) {
			return "fmod(" + getChildren().get(0).getExpression(lang) + ", " +
					getChildren().get(1).getExpression(lang) + ")";
		}

		String a = getChildren().get(0).getWrappedExpression(lang);
		String b = getChildren().get(1).getWrappedExpression(lang);

		if (getChildren().get(0).isPossiblyNegative()) {
			return lang.floorMod(a, b);
		}

		return a + " % " + b;
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

	/**
	 * Creates a modulo expression for the given pair of operands, inferring the
	 * floating-point flag from the operand types.
	 *
	 * @param inputs exactly two expressions: the dividend and the divisor
	 * @return the simplified modulo expression
	 * @throws UnsupportedOperationException if the number of inputs is not 2
	 */
	public static Expression of(Expression... inputs) {
		if (inputs.length != 2) {
			throw new UnsupportedOperationException();
		}

		return of(inputs[0], inputs[1], inputs[0].isFP() || inputs[1].isFP());
	}

	/**
	 * Creates a floating-point modulo expression.
	 *
	 * @param input the dividend
	 * @param mod   the divisor
	 * @return the simplified modulo expression
	 */
	public static Expression of(Expression input, Expression mod) {
		return of(input, mod, true);
	}

	/**
	 * Creates a modulo expression with the given floating-point flag.
	 *
	 * @param input the dividend
	 * @param mod   the divisor
	 * @param fp    {@code true} for floating-point modulo, {@code false} for integer modulo
	 * @return the simplified modulo expression
	 */
	public static Expression of(Expression input, Expression mod, boolean fp) {
		return Expression.process(create(input, mod, fp));
	}

	/**
	 * Creates a modulo expression, applying the full suite of simplification passes.
	 *
	 * @param input the dividend expression
	 * @param mod   the divisor expression
	 * @param fp    {@code true} for floating-point modulo, {@code false} for integer modulo
	 * @return the simplified expression or a new {@link Mod}
	 */
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

	/**
	 * Attempts to simplify {@code (innerMod) % m} when the inner modulus is a
	 * multiple of or equal to {@code m}.
	 *
	 * @param innerMod the inner mod expression
	 * @param m        the outer modulus
	 * @return the simplified expression, or {@code null} if no simplification applies
	 */
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

	/**
	 * Attempts to simplify {@code (k * x + (x % k)) % m} patterns when {@code k}
	 * divides {@code m} and structural conditions are met.
	 *
	 * @param innerSum the sum expression inside the mod
	 * @param m        the outer modulus
	 * @return the simplified expression, or {@code null} if no simplification applies
	 */
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
			warn("Inner sum simplify failed because " + constant + " * " + constant + " != " + m);
			return null;
		}
	}

	/**
	 * Returns {@code true} if the given number is a positive power of two.
	 *
	 * @param number the value to test
	 * @return {@code true} if {@code number} is a positive power of two
	 */
	private static boolean isPowerOf2(long number) {
		return number > 0 && (number & (number - 1)) == 0;
	}
}
