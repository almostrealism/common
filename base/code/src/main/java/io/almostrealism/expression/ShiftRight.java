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
 * Represents a right bit shift ({@code >>}) of an integer expression by an integer amount.
 *
 * <p>The left operand is the value being shifted and the right operand is the shift amount;
 * both must be {@code Integer} or {@code Long} typed, and the result is {@code Long} if either
 * is. On the host the shift has Java's arithmetic ({@code >>}) semantics, which for a
 * non-negative value is identical to the logical shift every backend performs, so values
 * are expected to be non-negative. The shift amount must lie in {@code [0, width)} for the
 * operand width; outside that range the generated C code is undefined.</p>
 *
 * <pre>{@code
 * Expression<?> x = ShiftRight.of(new IntegerConstant(0b1100), new IntegerConstant(2));
 * // x.intValue() == 0b11
 * }</pre>
 *
 * @param <T> the integral result type
 * @see BitwiseExpression
 * @see Xor
 */
public class ShiftRight<T extends Number> extends BitwiseExpression<T> {

	/**
	 * Constructs a right shift of {@code value} by {@code amount} bits.
	 *
	 * @param value  the value to shift; must be {@code Integer} or {@code Long} typed
	 * @param amount the number of bits to shift by; must be {@code Integer} or {@code Long} typed
	 * @throws UnsupportedOperationException if either operand is not integer typed
	 */
	public ShiftRight(Expression<?> value, Expression<?> amount) {
		super(">>", value, amount);
	}

	/** {@inheritDoc} Returns {@code left >> right}. */
	@Override
	protected long apply(long left, long right) {
		return left >> right;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>For non-negative operands the result is at most the value's upper bound shifted by
	 * the smallest possible shift amount.</p>
	 */
	@Override
	protected OptionalLong computeUpperBound(KernelStructureContext context) {
		OptionalLong constant = longValue();
		if (constant.isPresent()) return constant;
		if (!isNonNegative()) return OptionalLong.empty();

		OptionalLong value = getLeft().upperBound(context);
		OptionalLong amount = getRight().lowerBound(context);
		if (value.isEmpty() || amount.isEmpty()) return OptionalLong.empty();

		return OptionalLong.of(value.getAsLong() >> Math.min(amount.getAsLong(), 63));
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new ShiftRight<>(children.get(0), children.get(1));
	}

	/**
	 * Creates a right shift of {@code value} by {@code amount} bits.
	 *
	 * @param value  the value to shift
	 * @param amount the number of bits to shift by
	 * @return an expression for {@code value >> amount}
	 */
	public static Expression<?> of(Expression<?> value, Expression<?> amount) {
		return new ShiftRight<>(value, amount).fold();
	}
}
