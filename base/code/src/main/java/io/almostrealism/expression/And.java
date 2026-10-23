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

import io.almostrealism.kernel.KernelStructureContext;

import java.util.List;
import java.util.OptionalLong;

/**
 * Represents a bitwise AND operation between two integer expressions.
 * <p>
 * The {@code And} expression performs a bitwise AND ({@code &}) operation on two
 * {@link Expression} instances of type {@code Integer} or {@code Long}. This is distinct from
 * logical AND operations (see {@link Conjunction} for boolean logical AND).
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Expression<Integer> a = new IntegerConstant(0b1100);  // 12
 * Expression<Integer> b = new IntegerConstant(0b1010);  // 10
 * Expression<?> result = And.of(a, b);
 * // When evaluated: result = 12 & 10 = 0b1000 = 8
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li>Bit masking operations for extracting specific bits from integer values</li>
 *   <li>Efficient modulo operations when the divisor is a power of 2
 *       (e.g., {@code x & (n-1)} is equivalent to {@code x % n} when n is a power of 2)</li>
 *   <li>Reducing a 64-bit intermediate modulo 2^32 with the mask {@code 0xFFFFFFFF}, which
 *       is how 32-bit unsigned wraparound is expressed in the expression tree</li>
 *   <li>Flag checking and manipulation in packed integer values</li>
 * </ul>
 *
 * <h2>Type Requirements</h2>
 * <p>
 * Both operand expressions must be integer typed. The result is {@code Long} if either
 * operand is {@code Long}, otherwise {@code Integer}; a 32-bit AND truncates each operand to
 * its low 32 bits. The constructor throws an {@link UnsupportedOperationException} for any
 * other operand type.
 * </p>
 *
 * @param <T> the integral result type
 * @see BitwiseExpression
 * @see Conjunction for boolean logical AND operations
 * @see Mod for modulo operations that may be optimized to bitwise AND
 *
 * @author Michael Murray
 */
public class And<T extends Number> extends BitwiseExpression<T> {

	/**
	 * Constructs a new bitwise AND expression from two integer expressions.
	 *
	 * @param a the left operand expression; must be {@code Integer} or {@code Long} typed
	 * @param b the right operand expression; must be {@code Integer} or {@code Long} typed
	 * @throws UnsupportedOperationException if either operand is not integer typed
	 */
	public And(Expression<?> a, Expression<?> b) {
		super("&", a, b);
	}

	/** {@inheritDoc} Returns {@code left & right}. */
	@Override
	protected long apply(long left, long right) {
		return left & right;
	}

	/**
	 * Computes the upper bound of this expression's possible values.
	 * <p>
	 * For a bitwise AND of non-negative operands, the result cannot exceed the smaller of
	 * the two operands' upper bounds. Otherwise, since the mask (right operand) typically
	 * defines the maximum possible result, this implementation returns the upper bound of
	 * the right operand.
	 * </p>
	 *
	 * @param context the kernel structure context for bound computation
	 * @return the upper bound of the result
	 */
	@Override
	protected OptionalLong computeUpperBound(KernelStructureContext context) {
		OptionalLong right = getRight().upperBound(context);
		OptionalLong left = getLeft().upperBound(context);

		boolean leftNonNegative = !getLeft().isPossiblyNegative();
		boolean rightNonNegative = !getRight().isPossiblyNegative();

		if (leftNonNegative && rightNonNegative && left.isPresent() && right.isPresent()) {
			return OptionalLong.of(Math.min(left.getAsLong(), right.getAsLong()));
		} else if (leftNonNegative && !rightNonNegative) {
			return left;
		}

		// TODO(review): when both operands may be negative (or right spans both signs), right's bound is not conservative
		return right;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The AND of any value with a non-negative value has a clear sign bit, so one
	 * non-negative operand is enough.</p>
	 */
	@Override
	protected boolean isNonNegative() {
		return !getLeft().isPossiblyNegative() || !getRight().isPossiblyNegative();
	}

	/**
	 * Creates a new {@code And} expression with the specified child expressions.
	 *
	 * @param children a list containing exactly two {@link Expression} elements
	 * @return a new {@code And} expression with the provided operands
	 * @throws UnsupportedOperationException if the children list does not contain exactly two elements
	 */
	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new And<>(children.get(0), children.get(1));
	}

	/**
	 * Factory method for creating an {@code And} expression.
	 *
	 * @param a the left operand expression
	 * @param b the right operand expression
	 * @return a new {@code And} expression performing bitwise AND on the operands
	 */
	public static Expression<?> of(Expression<?> a, Expression<?> b) {
		return new And<>(a, b).fold();
	}
}
