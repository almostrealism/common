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

import io.almostrealism.lang.LanguageOperations;

/**
 * Represents a unary expression that applies a single operator to one operand.
 * <p>
 * This class extends {@link Expression} to provide a foundation for unary operations
 * such as type casts, negation, logical NOT, and other single-operand expressions.
 * The operator is applied as a prefix to the operand expression.
 * </p>
 * <p>
 * The generated expression format is: {@code operator operand} or {@code operator operand}
 * depending on whether space inclusion is enabled via {@link #isIncludeSpace()}.
 * </p>
 *
 * <h2>Example Subclasses</h2>
 * <ul>
 *   <li>{@link Cast} - type casting expressions</li>
 *   <li>Negation - numeric negation</li>
 *   <li>LogicalNot - boolean negation</li>
 * </ul>
 *
 * @param <T> the result type of the unary expression
 * @see Expression
 * @see BinaryExpression
 * @see Cast
 */
public class UnaryExpression<T> extends Expression<T> {
	/** The operator string to apply to the operand. */
	private String operator;

	/**
	 * Constructs a new unary expression.
	 *
	 * @param type     the Java class representing the result type
	 * @param operator the operator string (e.g., "(int)", "-", "!")
	 * @param value    the operand expression
	 */
	public UnaryExpression(Class<T> type, String operator, Expression<?> value) {
		super(type, value);
		this.operator = operator;
	}

	/**
	 * Determines whether a space should be included between the operator and operand.
	 * <p>
	 * Subclasses may override this to control formatting. For example, cast expressions
	 * may want a space, while negation may not.
	 * </p>
	 *
	 * @return true if a space should be included (default), false otherwise
	 */
	protected boolean isIncludeSpace() { return true; }

	/**
	 * Returns the operator string for the target language.
	 * <p>
	 * Subclasses may override this to provide language-specific operators.
	 * </p>
	 *
	 * @param lang the language operations context
	 * @return the operator string
	 */
	protected String getOperator(LanguageOperations lang) { return operator; }

	/**
	 * Generates the unary expression string for the target language.
	 * <p>
	 * Combines the operator with the wrapped operand expression, optionally
	 * including a space between them based on {@link #isIncludeSpace()}.
	 * </p>
	 *
	 * @param lang the language operations context
	 * @return the complete unary expression as a string
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		if (isIncludeSpace()) {
			return getOperator(lang) + " " + getChildren().get(0).getWrappedExpression(lang);
		} else {
			return getOperator(lang) + getChildren().get(0).getWrappedExpression(lang);
		}
	}

	/**
	 * Compares this unary expression with another expression for structural equality.
	 * <p>
	 * Two unary expressions are equal if they have the same structure (via superclass)
	 * and the same operator string.
	 * </p>
	 *
	 * @param e the expression to compare with
	 * @return true if the expressions are structurally equal, false otherwise
	 */
	@Override
	public boolean compare(Expression e) {
		return super.compare(e) && ((UnaryExpression) e).operator.equals(operator);
	}
}
