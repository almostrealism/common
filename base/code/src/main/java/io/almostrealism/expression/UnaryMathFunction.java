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

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * A single-operand mathematical function expression, rendered as a library call of the
 * form {@code name(operand)} — for example {@code sin(x)}, {@code exp(x)}, or {@code log(x)}.
 *
 * <p>This base captures the structure shared by every such function: the rendering shape,
 * single-operand {@link #evaluate(Number...) evaluation} delegating to a {@link Math} call,
 * and the {@code recreate} contract. Concrete functions supply only what distinguishes
 * them — the function name, the backing {@link Math} operation, and how a node of their
 * own type is constructed — through {@link #getFunctionName()}, {@link #applyToDouble(double)}
 * and {@link #createNode(Expression)}. Behavior that genuinely varies between functions (the
 * compute cost, the upper bound, and the derivative) remains a per-function override of the
 * corresponding {@link Expression} method.</p>
 *
 * @see Sine
 * @see Cosine
 * @see ArcCosine
 * @see Tangent
 * @see Exp
 * @see Logarithm
 */
public abstract class UnaryMathFunction extends Expression<Double> {
	/**
	 * Constructs a math-function expression over the given operand.
	 *
	 * @param input the operand the function is applied to
	 */
	protected UnaryMathFunction(Expression<Double> input) {
		super(Double.class, input);
	}

	/**
	 * Returns the name of the library function emitted by {@link #getExpression(LanguageOperations)},
	 * such as {@code "sin"} or {@code "log"}.
	 *
	 * @return the function name used in the rendered call
	 */
	protected abstract String getFunctionName();

	/**
	 * Applies the backing mathematical function to a single operand value, as used by
	 * {@link #evaluate(Number...)}.
	 *
	 * @param operand the operand value
	 * @return the function's value at {@code operand}
	 */
	protected abstract double applyToDouble(double operand);

	/**
	 * Builds a new node of this function's type around the given operand, as used by
	 * {@link #recreate(List)}.
	 *
	 * @param input the operand for the new node
	 * @return a new expression of this function's type
	 */
	protected abstract Expression<Double> createNode(Expression<?> input);

	@Override
	public String getExpression(LanguageOperations lang) {
		return getFunctionName() + "(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public Number evaluate(Number... children) {
		return applyToDouble(children[0].doubleValue());
	}

	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return createNode(children.get(0));
	}

	/**
	 * Creates a math-function expression, folding a constant operand to a {@link DoubleConstant}.
	 *
	 * <p>When the operand has a known double value the result is the function applied to it as a
	 * constant and no node is built; otherwise {@code node} builds the expression. The operand is
	 * widened with {@link Expression#toDouble()} before being handed to {@code node} so the emitted
	 * call always has a floating-point argument — Metal's math overloads are FP-only and an integer
	 * operand triggers a compile ambiguity.</p>
	 *
	 * @param fold  the {@link Math} operation applied when the operand is a compile-time constant
	 * @param input the operand
	 * @param node  builds the function node from the widened operand when folding does not apply
	 * @return a {@link DoubleConstant} when the operand is constant, otherwise {@code node}'s result
	 */
	protected static Expression<Double> of(DoubleUnaryOperator fold, Expression<Double> input,
										   Function<Expression<Double>, ? extends Expression<Double>> node) {
		OptionalDouble d = input.doubleValue();

		if (d.isPresent()) {
			return new DoubleConstant(fold.applyAsDouble(d.getAsDouble()));
		}

		return node.apply(input.toDouble());
	}
}
