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
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.lang.LanguageOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * An equality comparison expression ({@code ==}) between two sub-expressions.
 *
 * <p>Provides several simplification passes: constant folding, bounds-based static
 * resolution, Mask propagation, quotient expansion, and constant consolidation across
 * both sides of the equality.</p>
 */
public class Equals extends Comparison {
	/**
	 * When {@code true}, bounds information is used to statically resolve comparisons
	 * where the operands cannot be equal given their known ranges.
	 */
	public static boolean enableBoundedComparison = true;

	/**
	 * When {@code true}, constant terms are moved to the right-hand side and
	 * non-constant terms are consolidated on the left before constructing the node.
	 */
	public static boolean enableConsolidateConstants = true;

	/**
	 * When {@code true}, a quotient on the left side is expanded by multiplying both
	 * sides by the denominator before creating the equality node.
	 */
	public static boolean enableExpandQuotient = false;

	/**
	 * Constructs an equality comparison between the given operands.
	 *
	 * @param left  the left-hand side
	 * @param right the right-hand side
	 */
	protected Equals(Expression<?> left, Expression<?> right) {
		super(left, right);
	}

	/** {@inheritDoc} Returns {@code left == right}. */
	public String getExpression(LanguageOperations lang) {
		return getChildren().get(0).getWrappedExpression(lang) + " == " + getChildren().get(1).getWrappedExpression(lang);
	}

	@Override
	public boolean isSingleIndex() {
		if (getLeft() instanceof KernelIndex) {
			return getRight().doubleValue().isPresent();
		} else if (getRight() instanceof KernelIndex) {
			return getLeft().doubleValue().isPresent();
		}

		return false;
	}

	@Override
	protected boolean compare(Number left, Number right) {
		return left.doubleValue() == right.doubleValue();
	}

	@Override
	protected IndexSequence compare(IndexSequence left, IndexSequence right, long len) {
		return left.eq(right);
	}

	@Override
	protected int[] checkSingle(Expression left, Expression right, int len) {
		if (left instanceof KernelIndex) {
			OptionalInt i = right.intValue();
			OptionalDouble d = right.doubleValue();

			if (i.isPresent()) {
				int val = i.getAsInt();
				if (val >= 0 && val < len) {
					int seq[] = new int[len];
					seq[val] = 1;
					return seq;
				}
			} else if (d.isPresent()) {
				double val = d.getAsDouble();
				if (val == Math.floor(val)) {
					int seq[] = new int[len];
					seq[(int) val] = 1;
				}
			}
		}

		return null;
	}

	@Override
	public Expression<Boolean> recreate(List<Expression<?>> children) {
		if (children.size() != 2) throw new UnsupportedOperationException();
		return Equals.of(children.get(0), children.get(1));
	}

	/**
	 * Creates and post-processes an equality expression.
	 *
	 * @param left  the left-hand side
	 * @param right the right-hand side
	 * @return a simplified or constant-folded expression
	 */
	public static Expression of(Expression<?> left, Expression<?> right) {
		return Expression.process(create(left, right));
	}

	/**
	 * Creates an equality expression, applying constant folding, bounds checking,
	 * Mask propagation, quotient expansion, and constant consolidation.
	 *
	 * @param left  the left-hand side
	 * @param right the right-hand side
	 * @return the simplified expression
	 */
	protected static Expression create(Expression<?> left, Expression<?> right) {
		if (left.longValue().isPresent() && right.longValue().isPresent()) {
			return new BooleanConstant(left.longValue().getAsLong() == right.longValue().getAsLong());
		} else if (Objects.equals(left, right)) {
			return new BooleanConstant(true);
		}

		if (enableBoundedComparison) {
			Expression<?> result = checkBounds(left, right);
			if (result != null) return result;

			result = checkBounds(right, left);
			if (result != null) return result;
		}

		if (left instanceof Mask && right.doubleValue().isPresent()) {
			OptionalDouble masked = ((Mask) left).getMaskedValue().doubleValue();
			if (masked.isPresent() && masked.getAsDouble() == right.doubleValue().getAsDouble()) {
				return left;
			}
		}

		if (enableExpandQuotient && !left.isFP() && !right.isFP() && left instanceof Quotient) {
			Quotient q = (Quotient) left;
			Expression<?> d = q.getDenominator();

			if (d instanceof Constant) {
				return create(q.getNumerator(), right.multiply(d));
			}
		}

		if (enableConsolidateConstants) {
			List<Expression<?>> lTerms = extractTerms(left);
			List<Expression<?>> rTerms = extractTerms(right);

			List<Expression<?>> terms = new ArrayList<>();
			long constant = 0;

			for (Expression<?> term : lTerms) {
				OptionalLong v = term.longValue();

				if (v.isPresent()) {
					// Move constants to the right
					constant -= term.longValue().getAsLong();
				} else {
					// Keep non-constants on the left
					terms.add(term);
				}
			}

			for (Expression<?> term : rTerms) {
				OptionalLong v = term.longValue();

				if (v.isPresent()) {
					// Keep constants on the right
					constant += term.longValue().getAsLong();
				} else {
					// Move non-constants to the left
					terms.add(term.minus());
				}
			}

			return new Equals(Sum.of(terms.toArray(Expression[]::new)),
							ExpressionFeatures.getInstance().e(constant));
		}

		return new Equals(left, right);
	}

	/**
	 * Extracts the additive terms from an expression: if it is a {@link Sum} the
	 * children are returned; otherwise a singleton list containing the expression is returned.
	 *
	 * @param exp the expression to decompose
	 * @return the list of additive terms
	 */
	protected static List<Expression<?>> extractTerms(Expression<?> exp) {
		if (exp instanceof Sum) {
			return ((Sum) exp).getChildren();
		} else {
			return List.of(exp);
		}
	}

	/**
	 * Uses the known bounds of {@code value} to determine statically whether it can
	 * equal {@code anchor}. Returns a {@link BooleanConstant}(false) when the bounds
	 * rule out equality; returns {@code null} when bounds are insufficient to decide.
	 *
	 * @param value  the expression whose bounds are checked
	 * @param anchor the constant anchor expression
	 * @return a false-constant expression if equality is impossible, or {@code null}
	 */
	protected static Expression<?> checkBounds(Expression<?> value, Expression<?> anchor) {
		OptionalLong a = anchor.longValue();
		if (!a.isPresent()) return null;

		OptionalLong high = value.upperBound();
		if (high.isPresent() && high.getAsLong() < a.getAsLong()) {
			return new BooleanConstant(false);
		}

		OptionalLong low = value.lowerBound();
		if (low.isPresent() && low.getAsLong() > a.getAsLong()) {
			return new BooleanConstant(false);
		}

		return null;
	}
}
