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

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;

/**
 * An inequality comparison expression ({@code !=}) between two sub-expressions.
 *
 * <p>For non-floating-point operands this delegates to {@link Equals} (negated), inheriting
 * the full set of equality simplifications. In particular it benefits from the
 * canonicalization that moves all non-constant terms to one side with a constant on the
 * other (so that, for example, {@code A + B - B == 0} reduces to {@code A == 0}); that
 * canonical form exposes optimization opportunities that are otherwise hard to recognize,
 * and it is sound in exact (integer) arithmetic.</p>
 *
 * <p>For floating-point operands a direct {@code !=} is emitted instead. The canonical
 * {@code (A - B) == 0} form is <em>not</em> bit-equivalent to {@code A != B} once a backend
 * compiler is permitted to contract the subtraction of two products into a fused
 * multiply-add (e.g. native clang with {@code -ffp-contract=on}): the fused result rounds
 * differently than the separately-rounded operands of a direct comparison. Preserving the
 * literal {@code !=} avoids introducing that precision drift.</p>
 *
 * @see Equals
 */
public class NotEquals extends Comparison {
	/**
	 * Constructs an inequality comparison between the given operands.
	 *
	 * @param left  the left-hand side
	 * @param right the right-hand side
	 */
	protected NotEquals(Expression<?> left, Expression<?> right) {
		super(left, right);
	}

	/** {@inheritDoc} Returns {@code left != right}. */
	@Override
	public String getExpression(LanguageOperations lang) {
		return getChildren().get(0).getWrappedExpression(lang) + " != " + getChildren().get(1).getWrappedExpression(lang);
	}

	@Override
	protected boolean compare(Number left, Number right) {
		return left.doubleValue() != right.doubleValue();
	}

	@Override
	public Expression<Boolean> recreate(List<Expression<?>> children) {
		if (children.size() != 2) throw new UnsupportedOperationException();
		return NotEquals.of(children.get(0), children.get(1));
	}

	/**
	 * Creates and post-processes an inequality expression.
	 *
	 * @param left  the left-hand side
	 * @param right the right-hand side
	 * @return a simplified or constant-folded expression
	 */
	public static Expression of(Expression<?> left, Expression<?> right) {
		return Expression.process(create(left, right));
	}

	/**
	 * Creates an inequality expression.
	 *
	 * <p>When both operands are non-floating-point, delegates to {@link Equals#create}
	 * (negated) so equality's canonicalization and simplification tactics apply. When either
	 * operand is floating-point, a direct {@code !=} node is produced so the comparison is not
	 * lowered to the {@code (A - B) == 0} canonical form, which is not bit-equivalent under
	 * floating-point contraction.</p>
	 *
	 * @param left  the left-hand side
	 * @param right the right-hand side
	 * @return the simplified expression
	 */
	protected static Expression create(Expression<?> left, Expression<?> right) {
		if (!left.isFP() && !right.isFP()) {
			return Equals.create(left, right).not();
		}

		OptionalDouble ld = left.doubleValue();
		OptionalDouble rd = right.doubleValue();
		if (ld.isPresent() && rd.isPresent()) {
			return new BooleanConstant(ld.getAsDouble() != rd.getAsDouble());
		}

		return new NotEquals(left, right);
	}
}
