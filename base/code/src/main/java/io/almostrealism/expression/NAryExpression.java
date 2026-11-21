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

import java.util.List;
import java.util.Objects;

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

	/**
	 * Renders the operands joined by the operator.
	 *
	 * <p>Every operator an n-ary expression renders is left-associative in the generated
	 * languages, so {@code (a op b) op c} and {@code a op b op c} evaluate identically, in
	 * the same order and with the same intermediate rounding. The first operand is therefore
	 * rendered without parentheses when it applies the same operator (see
	 * {@link #continuesChain(Expression)}). This keeps a long left-nested chain, such as a
	 * product built by repeated multiplication, from emitting one bracket per link: the
	 * native compilers reject source nested deeper than a fixed limit (256 for clang).</p>
	 *
	 * @param lang the language to render for
	 * @return the rendered expression
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		List<Expression<?>> children = getChildren();
		StringBuilder rendered = new StringBuilder();

		for (int i = 0; i < children.size(); i++) {
			Expression<?> child = children.get(i);
			if (i > 0) rendered.append(" ").append(operator).append(" ");
			rendered.append(i == 0 && continuesChain(child) ?
					child.getExpression(lang) : child.getWrappedExpression(lang));
		}

		return rendered.toString();
	}

	/**
	 * Returns whether {@code operand}, used as the first operand of this expression, is an
	 * n-ary expression of the same kind applying the same operator, so that rendering it
	 * without parentheses leaves the evaluation unchanged.
	 *
	 * @param operand the candidate first operand
	 * @return {@code true} if the operand continues this expression's operator chain
	 */
	protected boolean continuesChain(Expression<?> operand) {
		return operand.getClass() == getClass()
				&& operator.equals(((NAryExpression<?>) operand).getOperator());
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
