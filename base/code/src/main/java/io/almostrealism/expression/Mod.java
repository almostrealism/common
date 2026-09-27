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
import io.almostrealism.sequence.ArithmeticIndexSequence;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexRange;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.sequence.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.io.Console;

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
	 * When {@code true}, an integer mod by a known positive constant whose dividend
	 * is non-negative with known bounds reports the tighter range-based upper bound
	 * ({@code upper % modulus} when the whole range falls inside one multiple of the
	 * modulus). Otherwise, and whenever this is {@code false}, the upper bound for a
	 * known constant modulus is {@code |modulus| - 1}.
	 */
	public static boolean enableSpanUpperBound = true;

	/** Whether this is a floating-point modulo ({@code true}) or integer modulo ({@code false}). */
	private boolean fp;

	/** Cached result of {@link #dividendPossiblyNegative()}; {@code null} until first computed. */
	private Boolean dividendPossiblyNegative;

	/**
	 * Constructs a modulo expression.
	 *
	 * <p>A floating-point modulo is always {@link Double} typed, regardless of the
	 * dividend type, because {@code fmod} produces a floating-point result. Typing
	 * it after the dividend would let an integer-typed expression render as a
	 * floating-point value, so consumers (integer modulo, casts, array subscripts)
	 * would generate invalid code around it.</p>
	 *
	 * @param a  the dividend
	 * @param b  the divisor
	 * @param fp {@code true} for floating-point modulo, {@code false} for integer modulo
	 */
	protected Mod(Expression<T> a, Expression<T> b, boolean fp) {
		super((Class<T>) (fp ? Double.class : a.getType()), a, b);
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

	/**
	 * Computes integer modulo consistent with the generated code.
	 *
	 * <p>{@link #getExpression} emits {@link LanguageOperations#floorMod} when the dividend
	 * may be negative, and a plain {@code %} otherwise. Constant folding must make the same
	 * choice so that a folded result matches what the compiled kernel would compute;
	 * otherwise {@code imod}, which the framework relies on to keep indices in
	 * {@code [0, divisor)}, can fold a negative dividend to a negative result.</p>
	 *
	 * @param dividend the left operand value
	 * @param divisor  the right operand value (must be non-zero)
	 * @return the modulo result matching the generated code
	 */
	private long intMod(long dividend, long divisor) {
		return foldIntMod(dividend, divisor, dividendPossiblyNegative());
	}

	/**
	 * Returns whether the dividend may be negative, computed once and cached.
	 *
	 * <p>{@link Expression#isPossiblyNegative()} walks the dividend subtree
	 * (via {@link Expression#lowerBound}), which is recursive and not memoized.
	 * Because an {@link Expression} is immutable, the result is invariant for this
	 * node, so it is cached to keep it out of the per-index constant-folding loop
	 * ({@link #intValue()}, {@link #computeValue}, {@link #evaluate}).</p>
	 *
	 * @return {@code true} if the dividend may be negative
	 */
	private boolean dividendPossiblyNegative() {
		if (dividendPossiblyNegative == null) {
			dividendPossiblyNegative = getChildren().get(0).isPossiblyNegative();
		}

		return dividendPossiblyNegative;
	}

	/**
	 * Folds an integer modulo to the value the generated code would compute.
	 *
	 * @param dividend                 the left operand value
	 * @param divisor                  the right operand value (must be non-zero)
	 * @param dividendPossiblyNegative whether the dividend may be negative, mirroring the
	 *                                 condition under which {@link #getExpression} emits
	 *                                 {@link LanguageOperations#floorMod}
	 * @return {@code Math.floorMod(dividend, divisor)} when the dividend may be negative,
	 *         otherwise {@code dividend % divisor}
	 */
	static long foldIntMod(long dividend, long divisor, boolean dividendPossiblyNegative) {
		return dividendPossiblyNegative ? Math.floorMod(dividend, divisor) : dividend % divisor;
	}

	@Override
	public OptionalInt intValue() {
		Expression input = getChildren().get(0);
		Expression mod = getChildren().get(1);

		if (input.intValue().isPresent() && mod.intValue().isPresent() && mod.intValue().getAsInt() != 0) {
			return OptionalInt.of((int) intMod(input.intValue().getAsInt(), mod.intValue().getAsInt()));
		}

		return super.intValue();
	}

	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values) && getChildren().get(1).isValue(values);
	}

	@Override
	public Number computeValue(IndexValues indexValues) {
		if (fp) {
			return getChildren().get(0).value(indexValues).doubleValue() % getChildren().get(1).value(indexValues).doubleValue();
		} else {
			return adjustType(getType(),
					intMod(getChildren().get(0).value(indexValues).longValue(),
							getChildren().get(1).value(indexValues).longValue()));
		}
	}

	@Override
	public Number evaluate(Number... children) {
		if (fp) {
			return children[0].doubleValue() % children[1].doubleValue();
		} else {
			return (int) intMod(children[0].intValue(), children[1].intValue());
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An integer modulus by a positive constant follows a progression when the
	 * dividend does and the reduction is exact under
	 * {@link ArithmeticIndexSequence#modExactly(long)}.</p>
	 */
	@Override
	public ArithmeticIndexSequence arithmeticSequence(Index index, long len) {
		if (fp) return null;

		OptionalLong m = getChildren().get(1).longValue();
		if (m.isEmpty() || m.getAsLong() <= 0) return null;

		List<ArithmeticIndexSequence> terms = getChildren().get(0).arithmeticTerms(index, len);
		if (terms == null) return null;

		// A term that is a multiple of the modulus at every position cannot affect the
		// remainder, provided the remaining terms are never negative (so that truncating
		// and flooring remainders agree), and is dropped before the reduction
		List<ArithmeticIndexSequence> remaining = terms.stream()
				.filter(t -> !t.isMultipleOf(m.getAsLong()))
				.collect(Collectors.toList());

		if (remaining.isEmpty()) {
			return new ArithmeticIndexSequence(0, 0, 1, len, len);
		} else if (remaining.size() < terms.size() && remaining.stream().anyMatch(t -> t.min() < 0)) {
			return null;
		}

		ArithmeticIndexSequence dividend = Sum.combine(remaining);
		return dividend == null ? null : dividend.modExactly(m.getAsLong());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The integer branch folds through {@link #foldIntMod(long, long, boolean)} using
	 * the same {@link #dividendPossiblyNegative()} decision as {@link #computeValue(IndexValues)},
	 * so the block and point paths agree on the choice between {@code %} and
	 * {@link Math#floorMod(long, long)}.</p>
	 */
	@Override
	protected double[] computeValues(IndexRange range) {
		double[] dividend = getChildren().get(0).values(range);
		double[] divisor = getChildren().get(1).values(range);
		double[] out = new double[range.getLength()];

		if (fp) {
			for (int i = 0; i < out.length; i++) {
				out[i] = dividend[i] % divisor[i];
			}
		} else {
			boolean negative = dividendPossiblyNegative();

			for (int i = 0; i < out.length; i++) {
				out[i] = IndexRange.exact(foldIntMod((long) dividend[i], (long) divisor[i], negative));
			}
		}

		return out;
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

	/**
	 * Computes a sound upper bound for {@code (dividend % modulus)}.
	 *
	 * <p>For a non-negative dividend whose value range is known, the result is
	 * bounded tightly: when the whole range falls within a single multiple-of-
	 * {@code modulus} block the maximum residue is the residue of the range's
	 * upper bound, and when the range crosses a multiple of {@code modulus} the
	 * residue reaches {@code modulus - 1} just before the wrap. Deriving the
	 * bound from the residues of the range endpoints alone is unsound, because a
	 * range that straddles a multiple of {@code modulus} — even one narrower than
	 * {@code modulus} itself — reaches {@code modulus - 1} at an interior point
	 * that is neither endpoint.</p>
	 *
	 * <p>Outside that tight case the bound falls back to {@code |modulus| - 1},
	 * which is sound for any dividend range and either sign of modulus, since a
	 * residue's magnitude is always strictly less than the modulus magnitude.
	 * The sentinel {@code modulus == Long.MIN_VALUE} is also covered: its
	 * magnitude is not representable, so {@code Math.abs} returns
	 * {@code Long.MIN_VALUE} and the decrement wraps to {@code Long.MAX_VALUE},
	 * which is exactly the largest residue a non-negative dividend can take
	 * modulo {@code Long.MIN_VALUE} — still a sound bound.</p>
	 */
	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong lower = getLeft().lowerBound(context);
		OptionalLong upper = getLeft().upperBound(context);
		OptionalLong m = getRight().longValue();

		if (!isFP() && m.isPresent()) {
			if (enableSpanUpperBound && lower.isPresent() && upper.isPresent()
					&& !getLeft().isPossiblyNegative()) {
				long top = upper.getAsLong();
				long bottom = lower.getAsLong();
				long ml = m.getAsLong();

				if (ml > 0 && bottom >= 0) {
					// Within one ml-block the residue is monotonic; crossing a
					// multiple of ml the value just before the wrap reaches ml - 1.
					if (bottom / ml == top / ml) {
						return OptionalLong.of(top % ml);
					}

					return OptionalLong.of(ml - 1);
				}
			}

			// |modulus| - 1 stays sound for a negative modulus, where the raw
			// value would report a negative (unsound) bound.
			return OptionalLong.of(Math.abs(m.getAsLong()) - 1);
		}

		return getChildren().get(1).upperBound(context);
	}

	/**
	 * Computes a sound lower bound for {@code (dividend % modulus)}.
	 *
	 * <p>For a non-negative dividend whose value range falls within a single
	 * multiple-of-{@code modulus} block, the minimum residue is the residue of
	 * the range's lower bound. When the range crosses a multiple of
	 * {@code modulus} the residue wraps through {@code 0}, so {@code 0} is the
	 * tightest sound bound. Deriving the bound from the residues of the range
	 * endpoints alone is unsound, because such a straddling range attains
	 * {@code 0} at an interior point that is neither endpoint.</p>
	 */
	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		if (isFP() || getLeft().isPossiblyNegative() || getRight().isPossiblyNegative())
			return super.lowerBound(context);

		OptionalLong lower = getLeft().lowerBound(context);
		OptionalLong upper = getLeft().upperBound(context);
		OptionalLong m = getRight().longValue();

		if (lower.isPresent() && upper.isPresent() && m.isPresent()) {
			long bottom = lower.getAsLong();
			long top = upper.getAsLong();
			long ml = m.getAsLong();

			// Within one ml-block the residue is monotonic, so the minimum is the
			// residue of the lower bound; crossing a multiple of ml it wraps
			// through 0, which is the default below.
			if (ml > 0 && bottom >= 0 && bottom / ml == top / ml) {
				return OptionalLong.of(bottom % ml);
			}
		}

		return OptionalLong.of(0);
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
	 * <p>When one operand is a floating-point expression whose value a {@code long}
	 * reports without loss and the other operand is integer typed, the operand is
	 * replaced with the equivalent integer constant and an integer modulo is
	 * produced instead, so the generated code uses integer arithmetic rather than
	 * the floating-point modulo function. The integer dividend keeps the
	 * framework's integer modulo semantics.</p>
	 *
	 * @param input the dividend expression
	 * @param mod   the divisor expression
	 * @param fp    {@code true} for floating-point modulo, {@code false} for integer modulo
	 * @return the simplified expression or a new {@link Mod}
	 */
	protected static Expression create(Expression<?> input, Expression mod, boolean fp) {
		if (fp) {
			boolean demoteMod = !input.isFP() && mod.isFP() && mod.longValue().isPresent();
			boolean demoteInput = !mod.isFP() && input.isFP() && input.longValue().isPresent();

			if (demoteMod || demoteInput) {
				long l = (demoteMod ? mod : (Expression) input).longValue().getAsLong();
				Expression<? extends Number> converted = Constant.exactInteger(l);
				return demoteMod ? create(input, converted, false)
						: create(converted, mod, false);
			}
		}

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
			// The dividend is a constant here, so it is possibly-negative exactly when it is
			// negative — no need for the recursive isPossiblyNegative() bounds walk.
			return ExpressionFeatures.getInstance().e(foldIntMod(id.getAsLong(), m, id.getAsLong() < 0));
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
			Console.root().warn("Inner sum simplify failed because " + constant + " * " + constant + " != " + m);
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
