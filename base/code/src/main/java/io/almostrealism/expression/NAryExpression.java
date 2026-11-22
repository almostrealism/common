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

import io.almostrealism.sequence.IndexValues;
import io.almostrealism.lang.LanguageOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An n-ary infix expression that joins two or more sub-expressions with the same operator.
 *
 * <p>Generates code of the form {@code a op b op c}. Subclasses specialise this for
 * specific operators such as {@code +}, {@code *}, and {@code &}.</p>
 *
 * @param <T> the result type of the expression
 */
public class NAryExpression<T> extends Expression<T> {

	/** The infix operator string used to join child expressions (e.g. {@code "+"}, {@code "&"}). */
	private final String operator;

	/**
	 * Constructs an n-ary expression from a list of operands.
	 *
	 * @param type     the result type
	 * @param operator the infix operator joining the operands
	 * @param values   the operand expressions; must have at least two elements
	 */
	public NAryExpression(Class<T> type, String operator, List<Expression<?>> values) {
		this(type, operator, values.toArray(new Expression[0]));
	}

	/**
	 * Constructs an n-ary expression from a varargs array of operands.
	 *
	 * @param type     the result type
	 * @param operator the infix operator joining the operands
	 * @param values   the operand expressions; must have at least two elements
	 */
	public NAryExpression(Class<T> type, String operator, Expression<?>... values) {
		super(type, validateExpressions(values));
		this.operator = operator;
	}

	@Override
	protected boolean computePossiblyNegative() {
		for (Expression<?> child : getChildren()) {
			if (child.isPossiblyNegative()) return true;
		}

		return false;
	}

	@Override
	public boolean isValue(IndexValues values) {
		for (Expression<?> child : getChildren()) {
			if (!child.isValue(values)) return false;
		}

		return true;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return getChildren().stream()
				.map(expression -> expression.getWrappedExpression(lang))
				.collect(Collectors.joining(" " + operator + " "));
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		return new NAryExpression<>(getType(), operator, children);
	}

	@Override
	protected boolean compareAttributes(Expression<?> other) {
		return operator.equals(((NAryExpression<?>) other).getOperator());
	}

	/**
	 * Returns the infix operator that joins the operands of this expression.
	 *
	 * @return the operator string, such as {@code "+"} or {@code "&"}
	 */
	public String getOperator() {
		return operator;
	}

	/**
	 * Determines the numeric result type of an arithmetic n-ary operation combining the
	 * given operands, which is also the type of a {@link Conditional} selecting between
	 * them. Returns {@code Double.class} if any operand is floating-point,
	 * {@code Long.class} if any operand is a non-{@code Integer} integral type, otherwise
	 * {@code Integer.class}.
	 *
	 * @param operands the operand expressions
	 * @return the promoted numeric type
	 * @throws UnsupportedOperationException if any operand's type is not a {@link Number} subtype
	 */
	protected static Class<? extends Number> promotedType(List<? extends Expression<?>> operands) {
		boolean ln = false;

		for (Expression<?> e : operands) {
			if (!Number.class.isAssignableFrom(e.getType())) {
				throw new UnsupportedOperationException();
			} else if (e.isFP()) {
				return Double.class;
			} else if (!Objects.equals(e.getType(), Integer.class)) {
				ln = true;
			}
		}

		return ln ? Long.class : Integer.class;
	}

	/**
	 * Returns whether any subset of the operands of this expression may be combined, through
	 * {@link #generate(List)} of that subset, into a single operand that takes their place
	 * without changing the value of this expression. That holds when the operation is
	 * associative and commutative and every operand is combined in the same arithmetic.
	 *
	 * <p>An n-ary expression in general makes no such promise; the arithmetic operations that
	 * do override this method.</p>
	 *
	 * @return {@code true} if operands of this expression may be grouped
	 */
	public boolean isOperandGroupable() {
		return false;
	}

	/**
	 * Returns whether every operand of this expression has the same type as the expression
	 * itself, so that combining any subset of them first leaves the arithmetic unchanged.
	 *
	 * @return {@code true} if all operands share the type of this expression
	 */
	protected boolean hasUniformOperandType() {
		for (Expression<?> operand : getChildren()) {
			if (!Objects.equals(operand.getType(), getType())) return false;
		}

		return true;
	}

	/**
	 * Returns the given operands with every operand that is itself an expression of
	 * {@code kind} replaced, in place, by that operand's own operands, so that an associative
	 * operation applied to the result of the same operation becomes one expression rather
	 * than a nested pair: {@code (a * b) * c} is the single product {@code a * b * c}.
	 *
	 * <p>Only an operand whose class is exactly {@code kind} is merged; a subclass represents
	 * something more specific than the bare operation and is kept whole. An operand is also
	 * kept whole when merging it would change the arithmetic its own operands are combined
	 * in (see {@link #sharesArithmetic(Expression, List)}).</p>
	 *
	 * <p>This is for associative operations only. The caller is the factory of {@code kind}
	 * and is responsible for {@code kind} being one.</p>
	 *
	 * @param kind     the exact class of nested expression to merge
	 * @param operands the operands of the expression being created
	 * @return the merged operands, or {@code operands} itself when there was nothing to merge
	 */
	protected static List<Expression<?>> mergeOperands(Class<? extends NAryExpression> kind,
													   List<Expression<?>> operands) {
		if (operands.stream().noneMatch(e -> e.getClass() == kind)) return operands;

		List<Expression<?>> merged = new ArrayList<>();
		boolean altered = false;

		for (Expression<?> operand : operands) {
			if (operand.getClass() == kind && sharesArithmetic(operand, operands)) {
				merged.addAll(operand.getChildren());
				altered = true;
			} else {
				merged.add(operand);
			}
		}

		return altered ? merged : operands;
	}

	/**
	 * Returns whether the operands of {@code nested} would be combined in the same arithmetic
	 * if they were operands of an expression over {@code operands} directly.
	 *
	 * <p>A nested integer expression is evaluated in integer arithmetic, wrapping on overflow
	 * at its own width, before its result is widened to join a floating-point or wider
	 * integer sibling. Merged, its operands could be combined with that sibling first and
	 * would not wrap, so such an expression is not merged. A nested floating-point expression
	 * always shares the arithmetic of the expression that contains it.</p>
	 *
	 * @param nested   an operand that is a candidate for merging
	 * @param operands every operand of the containing expression, including {@code nested}
	 * @return {@code true} if merging {@code nested} leaves the arithmetic unchanged
	 */
	protected static boolean sharesArithmetic(Expression<?> nested, List<Expression<?>> operands) {
		if (nested.isFP()) return true;

		for (Expression<?> operand : operands) {
			if (operand.isFP()) return false;
			if (!Objects.equals(operand.getType(), nested.getType())) return false;
		}

		return true;
	}

	/**
	 * Validates that the given operand array is non-null and contains at least two elements.
	 *
	 * @param values the array of operand expressions to validate
	 * @return the same array after validation
	 * @throws NullPointerException if {@code values} is null
	 * @throws IllegalArgumentException if fewer than two operands are provided
	 */
	private static Expression<?>[] validateExpressions(Expression<?>[] values) {
		Objects.requireNonNull(values);

		if (values.length < 2) {
			throw new IllegalArgumentException("NAryExpression must have at least 2 values");
		}

		for (int i = 0; i < values.length; i++) {
			Objects.requireNonNull(values[i]);
		}

		return values;
	}
}
