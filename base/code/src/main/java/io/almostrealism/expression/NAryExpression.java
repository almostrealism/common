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
import java.util.stream.Stream;

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
	private String operator;

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
	public boolean isPossiblyNegative() {
		return getChildren().stream().anyMatch(Expression::isPossiblyNegative);
	}

	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().stream().allMatch(expression -> expression.isValue(values));
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return concat(operator, getChildren().stream().map(expression -> expression.getWrappedExpression(lang)));
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.isEmpty()) {
			throw new IllegalArgumentException("NAryExpression must have at least 2 values");
		}

		return new NAryExpression<>(getType(), operator, children);
	}

	@Override
	public boolean compare(Expression e) {
		return super.compare(e) && operator.equals(((NAryExpression) e).operator);
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

	/**
	 * Infers the numeric result type for a set of operand expressions passed as varargs.
	 * Returns {@code Double.class} if any operand is floating-point, {@code Long.class}
	 * if any operand is a non-integer integer type, otherwise {@code Integer.class}.
	 *
	 * @param values the operand expressions (must be {@link Expression} instances)
	 * @return the promoted numeric type
	 */
	protected static Class<? extends Number> type(Object... values) {
		return type(List.of(values));
	}

	/**
	 * Infers the numeric result type for a set of operand expressions in an {@link Iterable}.
	 * Returns {@code Double.class} if any operand is floating-point, {@code Long.class}
	 * if any operand is a non-integer integer type, otherwise {@code Integer.class}.
	 *
	 * @param values an iterable of {@link Expression} instances
	 * @return the promoted numeric type
	 * @throws UnsupportedOperationException if any element is not an {@link Expression}
	 *         or its type is not a {@link Number} subtype
	 */
	protected static Class<? extends Number> type(Iterable values) {
		boolean ln = false;

		for (Object o : values) {
			if (!(o instanceof Expression)) {
				throw new UnsupportedOperationException();
			}

			Expression<?> e = (Expression<?>) o;
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
	 * Concatenates a stream of code strings with the given separator between each element.
	 *
	 * @param separator the operator string to place between elements
	 * @param values    a stream of per-operand code strings
	 * @return the joined expression string
	 */
	private static String concat(String separator, Stream<String> values) {
		StringBuilder buf = new StringBuilder();
		values.map(s -> " " + separator + " " + s).forEach(buf::append);
		return buf.toString().substring(separator.length() + 2);
	}
}
