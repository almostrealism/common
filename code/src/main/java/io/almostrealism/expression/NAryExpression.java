/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.kernel.IndexValues;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class NAryExpression<T> extends Expression<T> {
	public static boolean enableSimplification = true;
	public static boolean removeIdentities = true;

	private String operator;

	public NAryExpression(String operator, Stream<Expression<?>> values) {
		this(operator, values.toArray(Expression[]::new));
	}

	public NAryExpression(String operator, List<Expression<?>> values) {
		this((Class) type(values), operator, values.toArray(new Expression[0]));
	}

	public NAryExpression(Class<T> type, String operator, List<Expression<?>> values) {
		this(type, operator, values.toArray(new Expression[0]));
	}

	public NAryExpression(String operator, Expression<?>... values) {
		this((Class) type(values), operator, validateExpressions(values));
	}

	public NAryExpression(Class<T> type, String operator, Expression<?>... values) {
		super(type, validateExpressions(values));
		this.operator = operator;
	}

	@Override
	public boolean isKernelValue(IndexValues values) {
		return getChildren().stream().allMatch(expression -> expression.isKernelValue(values));
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return concat(operator, getChildren().stream().map(expression -> expression.getWrappedExpression(lang)));
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		if (children.isEmpty()) {
			throw new IllegalArgumentException("NAryExpression must have at least 2 values");
		}

		return new NAryExpression<>(getType(), operator, children);
	}

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

	protected static Class<? extends Number> type(Object... values) {
		return type(List.of(values));
	}

	protected static Class<? extends Number> type(Iterable values) {
		for (Object o : values) {
			if (!(o instanceof Expression)) {
				throw new UnsupportedOperationException();
			}

			Expression<?> e = (Expression<?>) o;
			if (!Number.class.isAssignableFrom(e.getType())) {
				throw new UnsupportedOperationException();
			} else if (!Objects.equals(e.getType(), Integer.class)) {
				return Double.class;
			}
		}

		return Integer.class;
	}

	private static String concat(String separator, Stream<String> values) {
		StringBuffer buf = new StringBuffer();
		values.map(s -> " " + separator + " " + s).forEach(buf::append);
		return buf.toString().substring(separator.length() + 2);
	}
}
