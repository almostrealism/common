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

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A less-than ({@code <}) or less-than-or-equal ({@code <=}) comparison expression.
 *
 * <p>Generates {@code left < right} or {@code left <= right} depending on
 * the {@code orEqual} flag. Constant operands are folded at construction time.</p>
 */
public class Less extends Comparison {
	/** When {@code true} this comparison is {@code <=}; otherwise {@code <}. */
	private boolean orEqual;

	/**
	 * Constructs a strict less-than comparison ({@code left < right}).
	 *
	 * @param left  the left operand
	 * @param right the right operand
	 */
	public Less(Expression<?> left, Expression<?> right) {
		this(left, right, false);
	}

	/**
	 * Constructs a less-than or less-than-or-equal comparison.
	 *
	 * @param left    the left operand
	 * @param right   the right operand
	 * @param orEqual {@code true} for {@code <=}, {@code false} for {@code <}
	 */
	public Less(Expression<?> left, Expression<?> right, boolean orEqual) {
		super(left, right);
		this.orEqual = orEqual;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		if (orEqual) {
			return getChildren().get(0).getWrappedExpression(lang) + " <= " + getChildren().get(1).getWrappedExpression(lang);
		} else{
			return getChildren().get(0).getWrappedExpression(lang) + " < " + getChildren().get(1).getWrappedExpression(lang);
		}
	}

	@Override
	protected boolean compare(Number left, Number right) {
		return orEqual ?
				(left.doubleValue() <= right.doubleValue()) :
				(left.doubleValue() < right.doubleValue());
	}

	@Override
	public Expression<Boolean> recreate(List<Expression<?>> children) {
		if (children.size() != 2) throw new UnsupportedOperationException();
		return new Less(children.get(0), children.get(1), orEqual);
	}

	/**
	 * Creates a strict less-than expression ({@code left < right}), folding constants.
	 *
	 * @param left  the left operand
	 * @param right the right operand
	 * @return a boolean constant if both operands are constant, otherwise a {@link Less}
	 */
	public static Expression<Boolean> of(Expression<?> left, Expression<?> right) {
		return Less.of(left, right, false);
	}

	/**
	 * Creates a less-than or less-than-or-equal expression, folding constants.
	 *
	 * @param left    the left operand
	 * @param right   the right operand
	 * @param orEqual {@code true} for {@code <=}, {@code false} for {@code <}
	 * @return a boolean constant if both operands are constant, otherwise a {@link Less}
	 */
	public static Expression<Boolean> of(Expression<?> left, Expression<?> right, boolean orEqual) {
		OptionalDouble ld = left.doubleValue();
		OptionalDouble rd = right.doubleValue();

		if (ld.isPresent() && rd.isPresent()) {
			if (orEqual) {
				return ld.getAsDouble() <= rd.getAsDouble() ? new BooleanConstant(true) : new BooleanConstant(false);
			} else {
				return ld.getAsDouble() < rd.getAsDouble() ? new BooleanConstant(true) : new BooleanConstant(false);
			}
		}


		return new Less(left, right, orEqual);
	}
}
