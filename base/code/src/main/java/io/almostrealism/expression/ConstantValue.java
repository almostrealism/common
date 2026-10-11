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

import io.almostrealism.lang.LanguageOperations;

import java.util.Objects;

/**
 * A generic constant expression that holds an arbitrary typed value.
 *
 * <p>Used as a fallback by {@link Constant#of} when the value is not one of the
 * primitive-constant subtypes ({@link IntegerConstant}, {@link LongConstant},
 * {@link DoubleConstant}, or {@link BooleanConstant}).</p>
 *
 * @param <T> the type of the constant value
 */
public class ConstantValue<T> extends Constant<T> {
	/** The value held by this constant. */
	private final T value;

	/**
	 * Constructs a constant expression holding the given value.
	 *
	 * @param type  the Java type of the constant
	 * @param value the literal value
	 */
	public ConstantValue(Class<T> type, T value) {
		super(type);
		this.value = value;
		init();
	}

	/**
	 * Renders a numeric value in its own textual form, since a number of a type without a
	 * dedicated constant expression has no language-specific literal format. A value that is
	 * not a number has no literal in generated code at all, and rendering its text (a string
	 * without quotes, an object's {@code toString}) would produce invalid source, so it is
	 * refused instead.
	 *
	 * @param lang the language operations (not consulted)
	 * @return the numeric value as text
	 * @throws UnsupportedOperationException if the value is not a {@link Number}
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		if (!(value instanceof Number)) {
			throw new UnsupportedOperationException("No literal representation for a constant of " + getType());
		}

		return String.valueOf(value);
	}

	/**
	 * Returns the value held by this constant. The inherited implementation only recognizes
	 * integer, double and boolean values, so it would report an arbitrary held value as unknown.
	 *
	 * @return the value held by this constant
	 */
	@Override
	public T getValue() { return value; }

	/**
	 * Compares this constant with another expression by declared type and value.
	 *
	 * @param e the expression to compare against
	 * @return {@code true} if {@code e} is a {@link ConstantValue} of the same declared type
	 *         holding an equal value
	 */
	@Override
	public boolean compare(Expression e) {
		return e instanceof ConstantValue && e.getType() == getType()
				&& Objects.equals(e.getValue(), value);
	}
}
