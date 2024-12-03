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

import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ExpressionCache;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Equals extends Comparison {
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
		return ExpressionCache.match(create(left, right));
	}

	protected static Expression create(Expression<?> left, Expression<?> right) {
		if (left.longValue().isPresent() && right.longValue().isPresent()) {
			return new BooleanConstant(left.longValue().getAsLong() == right.longValue().getAsLong());
		} else if (Objects.equals(left, right)) {
			return new BooleanConstant(true);
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

		return new Equals(left, right);
	}
}
