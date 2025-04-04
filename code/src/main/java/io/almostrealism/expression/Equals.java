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
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.lang.LanguageOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Equals extends Comparison {
	public static boolean enableBoundedComparison = true;
	public static boolean enableConsolidateConstants = true;
	public static boolean enableExpandQuotient = false;

	protected Equals(Expression<?> left, Expression<?> right) {
		super(left, right);
	}

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

	public static Expression of(Expression<?> left, Expression<?> right) {
		return Expression.process(create(left, right));
	}

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

	protected static List<Expression<?>> extractTerms(Expression<?> exp) {
		if (exp instanceof Sum) {
			return ((Sum) exp).getChildren();
		} else {
			return List.of(exp);
		}
	}

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
