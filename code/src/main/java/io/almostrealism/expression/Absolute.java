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

import java.util.List;

/**
 * Represents the absolute value mathematical operation within the expression tree.
 * <p>
 * The {@code Absolute} expression wraps another {@link Expression} of type {@code Double}
 * and computes its absolute value. When rendered to target language code (via
 * {@link #getExpression(LanguageOperations)}), it produces an {@code abs(...)} function call.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Expression<Double> value = new DoubleConstant(-5.0);
 * Expression<Double> absValue = new Absolute(value);
 * // When evaluated: absValue.evaluate(new Number[]{-5.0}) returns 5.0
 * }</pre>
 *
 * <h2>Expression Tree Structure</h2>
 * <p>
 * This is a unary expression with exactly one child expression. The child expression
 * must be of type {@code Double}. The result type is also {@code Double}.
 * </p>
 *
 * @see Expression
 * @see Math#abs(double)
 *
 * @author Michael Murray
 */
public class Absolute extends Expression<Double> {

	/**
	 * Constructs a new absolute value expression wrapping the specified input expression.
	 *
	 * @param input the expression whose absolute value should be computed;
	 *              must not be {@code null} and must be of type {@code Double}
	 */
	public Absolute(Expression<Double> input) {
		super(Double.class, input);
	}

	/**
	 * Generates the string representation of this expression for the target language.
	 * <p>
	 * Produces an {@code abs(...)} function call wrapping the child expression.
	 * </p>
	 *
	 * @param lang the language operations provider used to generate language-specific syntax
	 * @return a string in the form {@code "abs(childExpression)"}
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		// Use fabs() for floating-point absolute value in C/C++/CUDA/OpenCL
		// abs() is for integers only in C and can produce wrong results for doubles
		return "fabs(" + getChildren().get(0).getExpression(lang) + ")";
	}

	/**
	 * Evaluates the absolute value of the provided numeric argument.
	 *
	 * @param children an array containing exactly one {@link Number} element
	 *                 representing the evaluated value of the child expression
	 * @return the absolute value of the first element as a {@link Number}
	 */
	@Override
	public Number evaluate(Number... children) {
		return Math.abs(children[0].doubleValue());
	}

	/**
	 * Creates a new {@code Absolute} expression with the specified child expressions.
	 * <p>
	 * This method is used internally when transforming or simplifying expression trees.
	 * It validates that exactly one child expression is provided.
	 * </p>
	 *
	 * @param children a list containing exactly one {@link Expression} of type {@code Double}
	 * @return a new {@code Absolute} expression wrapping the single child
	 * @throws UnsupportedOperationException if the children list does not contain exactly one element
	 */
	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Absolute((Expression<Double>) children.get(0));
	}
}
