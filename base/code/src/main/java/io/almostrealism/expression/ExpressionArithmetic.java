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
import io.almostrealism.collect.CollectionExpression;

/**
 * Fluent arithmetic, comparison, logical, and conversion operators for {@link Expression}.
 *
 * <p>These default methods build composite expressions by delegating to the static factory
 * methods of the concrete {@link Expression} subtypes (e.g. {@link Sum}, {@link Product},
 * {@link Mod}). They are co-located on this mixin — rather than inline in {@link Expression} —
 * purely to keep that class focused on expression structure, evaluation, and simplification.
 * {@link Expression} implements this interface, so the operators remain available on every
 * expression instance.</p>
 *
 * @param <T> the numeric result type of the implementing {@link Expression}
 */
public interface ExpressionArithmetic<T> {

	/**
	 * Returns this operator mixin as the {@link Expression} it is mixed into, so the default
	 * methods can pass it to the expression factory methods.
	 *
	 * @return this, viewed as an {@link Expression}
	 */
	private Expression<T> self() {
		return (Expression<T>) this;
	}

	/**
	 * Returns the arithmetic negation of this expression.
	 *
	 * @return an expression representing {@code -this}
	 */
	public default Expression minus() { return Minus.of(self()); }

	/**
	 * Returns the sum of this expression and an integer constant.
	 *
	 * @param operand the integer value to add
	 * @return an expression representing {@code this + operand}
	 */
	public default Expression<? extends Number> add(int operand) { return add(new IntegerConstant(operand)); }

	/**
	 * Returns the sum of this expression and another expression.
	 *
	 * @param operand the expression to add
	 * @return an expression representing {@code this + operand}
	 */
	public default Expression<? extends Number> add(Expression<?> operand) { return Sum.of(self(), operand); }

	/**
	 * Returns the difference of this expression and another expression.
	 *
	 * @param operand the expression to subtract
	 * @return an expression representing {@code this - operand}
	 */
	public default Expression<? extends Number> subtract(Expression<? extends Number> operand) { return Difference.of(self(), operand); }

	/**
	 * Returns the difference of this expression and an integer constant.
	 *
	 * @param operand the integer value to subtract
	 * @return an expression representing {@code this - operand}
	 */
	public default Expression<? extends Number> subtract(int operand) { return Difference.of(self(), new IntegerConstant(operand)); }

	/**
	 * Returns the product of this expression and an integer constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the integer value to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public default Expression<? extends Number> multiply(int operand) {
		return operand == 1 ? (Expression) self() : multiply(new IntegerConstant(operand));
	}

	/**
	 * Returns the product of this expression and a long constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the long value to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public default Expression<? extends Number> multiply(long operand) {
		return operand == 1.0 ? (Expression) self() : multiply(ExpressionFeatures.getInstance().e(operand));
	}

	/**
	 * Returns the product of this expression and a double constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1.0, returns this expression unchanged.</p>
	 *
	 * @param operand the double value to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public default Expression<? extends Number> multiply(double operand) {
		return operand == 1.0 ? (Expression) self() : multiply(Constant.of(operand));
	}

	/**
	 * Returns the product of this expression and another expression.
	 *
	 * @param operand the expression to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public default Expression<? extends Number> multiply(Expression<?> operand) {
		return (Expression) Product.of(self(), operand);
	}

	/**
	 * Returns the quotient of this expression divided by an integer constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the integer divisor
	 * @return an expression representing {@code this / operand}
	 */
	public default Expression<? extends Number> divide(int operand) {
		return operand == 1 ? (Expression) self() : divide(new IntegerConstant(operand));
	}

	/**
	 * Returns the quotient of this expression divided by a long constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the long divisor
	 * @return an expression representing {@code this / operand}
	 */
	public default Expression<? extends Number> divide(long operand) {
		return operand == 1 ? (Expression) self() : divide(ExpressionFeatures.getInstance().e(operand));
	}

	/**
	 * Returns the quotient of this expression divided by a double constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1.0, returns this expression unchanged.</p>
	 *
	 * @param operand the double divisor
	 * @return an expression representing {@code this / operand}
	 */
	public default Expression<? extends Number> divide(double operand) {
		return operand == 1.0 ? (Expression) self() : divide(Constant.of(operand));
	}

	/**
	 * Returns the quotient of this expression divided by another expression.
	 *
	 * @param operand the expression divisor
	 * @return an expression representing {@code this / operand}
	 */
	public default Expression<? extends Number> divide(Expression<?> operand) {
		return (Expression)Quotient.of(self(), operand);
	}

	/**
	 * Returns the reciprocal (multiplicative inverse) of this expression.
	 *
	 * @return an expression representing {@code 1.0 / this}
	 */
	public default Expression<? extends Number> reciprocal() { return (Expression) Quotient.of(new DoubleConstant(1.0), self()); }

	/**
	 * Returns this expression raised to the power of another expression.
	 *
	 * @param operand the exponent expression
	 * @return an expression representing {@code this ^ operand}
	 */
	public default Expression<Double> pow(Expression<Double> operand) { return Exponent.of((Expression) self(), operand); }

	/**
	 * Returns the exponential function (e^x) applied to this expression.
	 *
	 * @return an expression representing {@code e^this}
	 */
	public default Expression<Double> exp() { return Exp.of(self()); }

	/**
	 * Returns the natural logarithm of this expression.
	 *
	 * @return an expression representing {@code ln(this)}
	 */
	public default Expression<Double> log() { return Logarithm.of(self()); }

	/**
	 * Returns the floor (largest integer not greater than) of this expression.
	 *
	 * <p>For integer expressions, returns this expression unchanged.
	 * For constant double expressions, computes the floor at compile time.</p>
	 *
	 * @return an expression representing {@code floor(this)}
	 */
	public default Expression floor() { return Floor.of(self()); }

	/**
	 * Returns the ceiling (smallest integer not less than) of this expression.
	 *
	 * <p>For integer expressions, returns this expression unchanged.
	 * For constant double expressions, computes the ceiling at compile time.</p>
	 *
	 * @return an expression representing {@code ceil(this)}
	 */
	public default Expression ceil() { return Ceiling.of(self()); }

	/**
	 * Returns the floating-point modulo of this expression by another.
	 *
	 * @param operand the divisor expression
	 * @return an expression representing {@code this % operand}
	 */
	public default Expression mod(Expression<Double> operand) { return Mod.of(self(), operand); }

	/**
	 * Returns the modulo of this expression by another, with configurable floating-point behavior.
	 *
	 * @param operand the divisor expression
	 * @param fp if {@code true}, uses floating-point modulo; otherwise uses integer modulo
	 * @return an expression representing {@code this % operand}
	 */
	public default Expression mod(Expression<?> operand, boolean fp) { return Mod.of(self(), operand, fp); }

	/**
	 * Returns the integer modulo of this expression by another.
	 *
	 * @param operand the divisor expression
	 * @return an expression representing {@code this % operand} with integer semantics
	 */
	public default Expression<Integer> imod(Expression<? extends Number> operand) { return mod(operand, false); }

	/**
	 * Returns the integer modulo of this expression by an integer constant.
	 *
	 * @param operand the integer divisor
	 * @return an expression representing {@code this % operand}
	 */
	public default Expression<Integer> imod(int operand) { return imod(new IntegerConstant(operand)); }

	/**
	 * Returns the integer modulo of this expression by a long constant.
	 *
	 * <p>If the operand fits in an integer, uses integer modulo; otherwise uses long modulo.</p>
	 *
	 * @param operand the long divisor
	 * @return an expression representing {@code this % operand}
	 */
	public default Expression<Integer> imod(long operand) {
		if (operand > Integer.MAX_VALUE) {
			return imod(new LongConstant(operand));
		} else {
			return imod((int) operand);
		}
	}

	/**
	 * Returns the sine of this expression.
	 *
	 * @return an expression representing {@code sin(this)}
	 */
	public default Expression<Double> sin() { return Sine.of((Expression) self()); }

	/**
	 * Returns the cosine of this expression.
	 *
	 * @return an expression representing {@code cos(this)}
	 */
	public default Expression<Double> cos() { return Cosine.of((Expression) self()); }

	/**
	 * Returns the tangent of this expression.
	 *
	 * @return an expression representing {@code tan(this)}
	 */
	public default Expression<Double> tan() { return Tangent.of((Expression) self()); }

	/**
	 * Returns the hyperbolic tangent of this expression.
	 *
	 * @return an expression representing {@code tanh(this)}
	 */
	public default Expression<Double> tanh() { return Tangent.of((Expression) self(), true); }

	/**
	 * Returns the logical negation of this boolean expression.
	 *
	 * @return an expression representing {@code !this}
	 * @throws IllegalArgumentException if this expression is not boolean-typed
	 */
	public default Expression not() {
		if (self().getType() != Boolean.class)
			throw new IllegalArgumentException();

		return Negation.of(self());
	}

	/**
	 * Returns an equality comparison of this expression with zero.
	 *
	 * @return an expression representing {@code this == 0.0}
	 */
	public default Expression eqZero() { return eq(0.0); }

	/**
	 * Returns an equality comparison of this expression with an integer constant.
	 *
	 * @param operand the integer value to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public default Expression eq(int operand) { return eq(new IntegerConstant(operand)); }

	/**
	 * Returns an equality comparison of this expression with a long constant.
	 *
	 * @param operand the long value to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public default Expression eq(long operand) { return eq(ExpressionFeatures.getInstance().e(operand)); }

	/**
	 * Returns an equality comparison of this expression with a double constant.
	 *
	 * @param operand the double value to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public default Expression eq(double operand) { return eq(new DoubleConstant(operand)); }

	/**
	 * Returns an equality comparison of this expression with another expression.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public default Expression eq(Expression<?> operand) { return Equals.of(self(), operand); }

	/**
	 * Returns an inequality comparison of this expression with another expression.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this != operand}
	 */
	public default Expression neq(Expression<?> operand) {
		return Equals.of(self(), operand).not();
	}

	/**
	 * Returns the logical conjunction (AND) of this expression with another boolean expression.
	 *
	 * @param operand the boolean expression to AND with
	 * @return an expression representing {@code this && operand}
	 */
	public default Expression and(Expression<Boolean> operand) { return Conjunction.of((Expression) self(), operand); }

	/**
	 * Returns a conditional expression (ternary operator) using this boolean as the condition.
	 *
	 * @param positive the expression to return if this condition is true
	 * @param negative the expression to return if this condition is false
	 * @return an expression representing {@code this ? positive : negative}
	 * @throws IllegalArgumentException if this expression is not boolean-typed
	 */
	public default Expression conditional(Expression<?> positive, Expression<?> negative) {
		if (self().getType() != Boolean.class) throw new IllegalArgumentException();
		return Conditional.of((Expression<Boolean>) self(), positive, negative);
	}

	/**
	 * Returns a greater-than comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this > operand}
	 */
	public default Expression<Boolean> greaterThan(Expression<?> operand) { return Greater.of(self(), operand); }

	/**
	 * Returns a greater-than-or-equal comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this >= operand}
	 */
	public default Expression<Boolean> greaterThanOrEqual(Expression<?> operand) { return Greater.of(self(), operand, true); }

	/**
	 * Returns a greater-than-or-equal comparison of this expression with an integer constant.
	 *
	 * @param operand the integer value to compare against
	 * @return an expression representing {@code this >= operand}
	 */
	public default Expression<Boolean> greaterThanOrEqual(int operand) { return Greater.of(self(), new IntegerConstant(operand), true); }

	/**
	 * Returns a less-than comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this < operand}
	 */
	public default Expression<Boolean> lessThan(Expression<?> operand) { return Less.of(self(), operand); }

	/**
	 * Returns a less-than comparison of this expression with an integer constant.
	 *
	 * @param operand the integer value to compare against
	 * @return an expression representing {@code this < operand}
	 */
	public default Expression<Boolean> lessThan(int operand) { return Less.of(self(), new IntegerConstant(operand)); }

	/**
	 * Returns a less-than-or-equal comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this <= operand}
	 */
	public default Expression<Boolean> lessThanOrEqual(Expression<?> operand) { return Less.of(self(), operand, true); }

	/**
	 * Casts this expression to a double-precision floating-point type.
	 *
	 * <p>If this expression is already double-typed, returns it unchanged.</p>
	 *
	 * @return an expression representing {@code (double) this}
	 */
	public default Expression<Double> toDouble() {
		if (self().getType() == Double.class) return (Expression<Double>) self();
		return Cast.of(Double.class, Cast.FP_NAME, self());
	}

	/**
	 * Casts this expression to a 32-bit integer type.
	 *
	 * <p>Only applies a cast if this expression is floating-point typed.</p>
	 *
	 * @return an expression representing {@code (int) this}
	 */
	// TODO  This should also return Expression<? extends Number>
	public default Expression<Integer> toInt() {
		return (Expression) toInt(false);
	}

	/**
	 * Casts this expression to a 32-bit integer type with configurable strictness.
	 *
	 * @param require32 if {@code true}, always applies cast unless already Integer;
	 *                  if {@code false}, only casts floating-point types
	 * @return an expression representing {@code (int) this}, or this expression if no cast needed
	 */
	public default Expression<? extends Number> toInt(boolean require32) {
		boolean cast = require32 ? self().getType() != Integer.class : self().isFP();
		return cast ? Cast.of(Integer.class, Cast.INT_NAME, self()) : (Expression) self();
	}

	/**
	 * Casts this expression to a 64-bit long integer type.
	 *
	 * <p>Only applies a cast if this expression is floating-point typed.</p>
	 *
	 * @return an expression representing {@code (long) this}
	 */
	public default Expression<? extends Number> toLong() {
		return toLong(false);
	}

	/**
	 * Casts this expression to a 64-bit long integer type with configurable strictness.
	 *
	 * @param require64 if {@code true}, always applies cast unless already Long;
	 *                  if {@code false}, only casts floating-point types
	 * @return an expression representing {@code (long) this}, or this expression if no cast needed
	 */
	public default Expression<? extends Number> toLong(boolean require64) {
		boolean cast = require64 ? self().getType() != Long.class : self().isFP();
		return cast ? Cast.of(Long.class, Cast.LONG_NAME, self()) : (Expression) self();
	}

	/**
	 * Computes the derivative of this expression with respect to the target collection expression.
	 *
	 * <p>This method is used for automatic differentiation. The default implementation
	 * throws {@link UnsupportedOperationException}; subclasses that support differentiation
	 * should override this method.</p>
	 *
	 * @param target the collection expression to differentiate with respect to
	 * @return the derivative expression
	 * @throws UnsupportedOperationException if this expression type does not support differentiation
	 */
	public default CollectionExpression<?> delta(CollectionExpression<?> target) {
		throw new UnsupportedOperationException(getClass().getSimpleName());
	}

}
