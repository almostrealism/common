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
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.sequence.IndexRange;
import io.almostrealism.sequence.IndexValues;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Base class for binary bit-manipulation operators ({@link And}, {@link Xor},
 * {@link ShiftRight}) over integer operands.
 *
 * <p>The result type follows the operands: when either operand is {@code Long} the operation
 * is performed on 64-bit values, otherwise on 32-bit values. Floating-point operands are
 * rejected; a {@code Boolean} operand is an integer, as in the generated C code. Every
 * host-side evaluation path ({@link #intValue()}, {@link #longValue()}, {@link #computeValue(IndexValues)}, {@link #evaluate(Number...)} and
 * {@link #computeValues(IndexRange)}) applies the same Java operator, {@link #apply(long, long)},
 * so that constant folding and index analysis agree with the operator rendered into generated
 * code. A 32-bit operation truncates each operand to its low 32 bits first, exactly as the
 * rendered {@code int} arithmetic does.</p>
 *
 * <p>For non-negative operands each of these operators produces a non-negative result, which
 * is what {@link #computeLowerBound(KernelStructureContext)} and
 * {@link #computePossiblyNegative()} report; subclasses refine the upper bound.</p>
 *
 * @param <T> the integral result type, {@code Integer} or {@code Long}
 */
public abstract class BitwiseExpression<T extends Number> extends BinaryExpression<T> {
	/** The infix operator rendered between the two operands. */
	private final String operator;

	/**
	 * Constructs a bitwise expression.
	 *
	 * @param operator the infix operator rendered in generated code, e.g. {@code "^"}
	 * @param left     the left operand; must be {@code Integer} or {@code Long} typed
	 * @param right    the right operand; must be {@code Integer} or {@code Long} typed
	 * @throws UnsupportedOperationException if either operand is not integer typed
	 */
	protected BitwiseExpression(String operator, Expression<?> left, Expression<?> right) {
		super((Class<T>) integralType(left, right), left, right);
		this.operator = operator;
	}

	/**
	 * Applies this operator with Java semantics to two 64-bit values. The result must match
	 * the rendered operator for the same operand values.
	 *
	 * @param left  the left operand value
	 * @param right the right operand value
	 * @return the result of the operation
	 */
	protected abstract long apply(long left, long right);

	/**
	 * Renders this expression as {@code left operator right}, with both operands wrapped.
	 *
	 * @param lang the language operations provider used to generate language-specific syntax
	 * @return the rendered infix expression
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		return getLeft().getWrappedExpression(lang) + " " + operator + " " +
				getRight().getWrappedExpression(lang);
	}

	/**
	 * Returns the constant this expression folds to when both operands are constant, keeping
	 * its width, or this expression otherwise. The factory methods of the subclasses return
	 * this, so that constant sub-expressions do not reach the generated code.
	 *
	 * @return a {@link LongConstant} or {@link IntegerConstant}, or this expression
	 */
	public Expression<?> fold() {
		OptionalLong value = longValue();
		if (value.isEmpty()) return this;
		return isLong() ? new LongConstant(value.getAsLong()) : new IntegerConstant((int) value.getAsLong());
	}

	/**
	 * Applies this operator to two evaluated operand values at the width of this expression,
	 * truncating each operand to 32 bits for an {@code Integer} typed expression.
	 *
	 * @param left  the evaluated left operand
	 * @param right the evaluated right operand
	 * @return the result as a {@link Long} or {@link Integer}
	 */
	protected Number compute(Number left, Number right) {
		if (isLong()) return apply(left.longValue(), right.longValue());
		return (int) apply(left.intValue(), right.intValue());
	}

	@Override
	public OptionalLong longValue() {
		OptionalLong a = getLeft().longValue();
		OptionalLong b = getRight().longValue();
		if (a.isEmpty() || b.isEmpty()) return OptionalLong.empty();
		return OptionalLong.of(compute(a.getAsLong(), b.getAsLong()).longValue());
	}

	@Override
	public OptionalInt intValue() {
		OptionalLong l = longValue();
		if (l.isEmpty() || l.getAsLong() < Integer.MIN_VALUE || l.getAsLong() > Integer.MAX_VALUE) {
			return OptionalInt.empty();
		}

		return OptionalInt.of((int) l.getAsLong());
	}

	@Override
	public OptionalDouble doubleValue() {
		OptionalLong l = longValue();
		return l.isPresent() ? OptionalDouble.of(l.getAsLong()) : OptionalDouble.empty();
	}

	@Override
	public boolean isValue(IndexValues values) {
		return getLeft().isValue(values) && getRight().isValue(values);
	}

	@Override
	public Number computeValue(IndexValues indexValues) {
		return compute(getLeft().value(indexValues), getRight().value(indexValues));
	}

	@Override
	public Number evaluate(Number... children) {
		return compute(children[0], children[1]);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Narrows each operand through {@code long} (and then to {@code int} for a 32-bit
	 * expression), matching {@link Number#intValue()}'s low-32-bit truncation in
	 * {@link #computeValue(IndexValues)}. A 64-bit result is routed through
	 * {@link IndexRange#exact(long)}, so a magnitude that a {@code double} cannot hold exactly
	 * raises {@link IndexRange.InexactValueException} and the caller falls back to point
	 * evaluation.</p>
	 */
	@Override
	protected double[] computeValues(IndexRange range) {
		double[] left = getLeft().values(range);
		double[] right = getRight().values(range);
		double[] out = new double[range.getLength()];

		for (int i = 0; i < out.length; i++) {
			if (isLong()) {
				out[i] = IndexRange.exact(apply((long) left[i], (long) right[i]));
			} else {
				out[i] = (int) apply((int) (long) left[i], (int) (long) right[i]);
			}
		}

		return out;
	}

	/**
	 * Returns {@code 0} when both operands are non-negative, since each bitwise operator
	 * preserves non-negativity; otherwise the bound is unknown.
	 */
	@Override
	protected OptionalLong computeLowerBound(KernelStructureContext context) {
		OptionalLong constant = longValue();
		if (constant.isPresent()) return constant;
		return isNonNegative() ? OptionalLong.of(0) : OptionalLong.empty();
	}

	@Override
	protected boolean computePossiblyNegative() {
		OptionalLong constant = longValue();
		if (constant.isPresent()) return constant.getAsLong() < 0;
		return !isNonNegative();
	}

	/**
	 * Returns {@code true} if the result of this operation cannot be negative. By default
	 * that requires that neither operand can be negative.
	 *
	 * @return {@code true} if the result is known to be non-negative
	 */
	protected boolean isNonNegative() {
		return !getLeft().isPossiblyNegative() && !getRight().isPossiblyNegative();
	}

	/**
	 * Determines the result type of a bitwise operation over the two operands.
	 *
	 * @param left  the left operand
	 * @param right the right operand
	 * @return {@code Long} if either operand is {@code Long}, otherwise {@code Integer}
	 * @throws UnsupportedOperationException if either operand is floating point
	 */
	protected static Class<? extends Number> integralType(Expression<?> left, Expression<?> right) {
		if (left.isFP() || right.isFP()) {
			throw new UnsupportedOperationException(
					"Bitwise operations require integer operands, not " +
							left.getType().getSimpleName() + " and " + right.getType().getSimpleName());
		}

		return left.isLong() || right.isLong() ? Long.class : Integer.class;
	}
}
