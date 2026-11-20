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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.lang.LanguageOperations;

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
 * Expression<Double> absValue = Absolute.of(value);
 * // The constant operand is folded: absValue is a DoubleConstant with value 5.0
 * }</pre>
 *
 * <h2>Expression Tree Structure</h2>
 * <p>
 * This is a unary expression with exactly one child expression. The child expression
 * must be of type {@code Double}. The result type is also {@code Double}. Evaluation,
 * {@code recreate} and the constant-folding factory are inherited from
 * {@link UnaryMathFunction}; only the rendering differs, because the target language
 * names its absolute value function ({@link LanguageOperations#abs(String)}).
 * </p>
 *
 * @see UnaryMathFunction
 * @see Math#abs(double)
 *
 * @author Michael Murray
 */
public class Absolute extends UnaryMathFunction {

	/**
	 * Constructs a new absolute value expression wrapping the specified input expression.
	 *
	 * @param input the expression whose absolute value should be computed;
	 *              must not be {@code null} and must be of type {@code Double}
	 */
	public Absolute(Expression<Double> input) {
		super(input);
	}

	/**
	 * Generates the string representation of this expression for the target language.
	 * <p>
	 * Delegates to {@link LanguageOperations#abs(String)} for the appropriate function
	 * name (e.g., {@code fabs()} for C/CUDA/OpenCL/Metal).
	 * </p>
	 *
	 * @param lang the language operations provider used to generate language-specific syntax
	 * @return a string in the form {@code "fabs(childExpression)"} or equivalent for the target language
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.abs(getChildren().get(0).getExpression(lang));
	}

	/** {@inheritDoc} Returns {@code "abs"}; the rendered call uses the language's own name. */
	@Override
	protected String getFunctionName() { return "abs"; }

	@Override
	protected double applyToDouble(double operand) { return Math.abs(operand); }

	@Override
	protected Expression<Double> createNode(Expression<?> input) {
		return of(input);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Since {@code abs(u) = max(u, -u)}, its derivative is {@code sign(u) * du/dx}:
	 * the derivative of the operand where it is positive and its negation where it is
	 * negative. The kink at {@code u == 0} is not differentiable; there the strict
	 * comparison selects the negated branch, mirroring how {@link Max#delta(CollectionExpression)}
	 * resolves a tie.</p>
	 */
	@Override
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		Expression<?> input = getChildren().get(0);
		CollectionExpression<?> childDelta = input.delta(target);
		return conditional(target.getShape(),
				input.greaterThan(new DoubleConstant(0.0)),
				childDelta,
				minus(target.getShape(), childDelta));
	}

	/**
	 * Creates an absolute value expression, folding a constant operand to a {@link DoubleConstant}.
	 *
	 * <p>When the operand has a known numeric value the result is its absolute value as a
	 * constant and no node is built. Otherwise a non-FP operand is widened with
	 * {@link Expression#toDouble()} so the emitted call always has a floating-point argument:
	 * OpenCL C's {@code fabs} has only floating-point overloads, so an integer operand such as
	 * {@code fabs(0)} is ambiguous and fails kernel compilation.</p>
	 *
	 * @param input the expression whose absolute value should be computed
	 * @return a {@link DoubleConstant} when the operand is constant, otherwise a new {@code Absolute}
	 */
	public static Expression<Double> of(Expression<?> input) {
		return of(Math::abs, input, Absolute::new);
	}
}
