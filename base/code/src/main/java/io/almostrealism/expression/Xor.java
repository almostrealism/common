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

import io.almostrealism.kernel.KernelStructureContext;

import java.util.List;
import java.util.OptionalLong;

/**
 * Represents a bitwise exclusive OR ({@code ^}) of two integer expressions.
 *
 * <p>Both operands must be {@code Integer} or {@code Long} typed; the result is {@code Long}
 * if either operand is. Exclusive OR is the mixing step of most integer hash functions
 * (for example the xorshift steps of the PCG hash), which is its primary use in generated
 * kernels.</p>
 *
 * <pre>{@code
 * Expression<?> x = Xor.of(new IntegerConstant(0b1100), new IntegerConstant(0b1010));
 * // x.intValue() == 0b0110
 * }</pre>
 *
 * @param <T> the integral result type
 * @see BitwiseExpression
 * @see And
 * @see ShiftRight
 */
public class Xor<T extends Number> extends BitwiseExpression<T> {

	/**
	 * Constructs a bitwise exclusive OR of two integer expressions.
	 *
	 * @param a the left operand; must be {@code Integer} or {@code Long} typed
	 * @param b the right operand; must be {@code Integer} or {@code Long} typed
	 * @throws UnsupportedOperationException if either operand is not integer typed
	 */
	public Xor(Expression<?> a, Expression<?> b) {
		super("^", a, b);
	}

	/** {@inheritDoc} Returns {@code left ^ right}. */
	@Override
	protected long apply(long left, long right) {
		return left ^ right;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>For non-negative operands no bit above the highest bit of either operand's upper
	 * bound can be set, so the result is bounded by the all-ones value {@code 2^k - 1}
	 * covering that bit.</p>
	 */
	@Override
	protected OptionalLong computeUpperBound(KernelStructureContext context) {
		OptionalLong constant = longValue();
		if (constant.isPresent()) return constant;
		if (!isNonNegative()) return OptionalLong.empty();

		OptionalLong left = getLeft().upperBound(context);
		OptionalLong right = getRight().upperBound(context);
		if (left.isEmpty() || right.isEmpty()) return OptionalLong.empty();

		long bound = Math.max(left.getAsLong(), right.getAsLong());
		return OptionalLong.of(bound <= 0 ? 0 : -1L >>> Long.numberOfLeadingZeros(bound));
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new Xor<>(children.get(0), children.get(1));
	}

	/**
	 * Creates a bitwise exclusive OR of two integer expressions.
	 *
	 * @param a the left operand
	 * @param b the right operand
	 * @return an expression for {@code a ^ b}
	 */
	public static Expression<?> of(Expression<?> a, Expression<?> b) {
		return new Xor<>(a, b).fold();
	}
}
