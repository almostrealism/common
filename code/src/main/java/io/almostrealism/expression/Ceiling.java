/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Represents a ceiling function expression that rounds a value up to the nearest integer.
 * <p>
 * This class extends {@link Expression} to provide ceiling (round up) functionality
 * in generated code. It wraps a double expression and generates a {@code ceil()} function
 * call in the output code.
 * </p>
 * <p>
 * When the child expression has a known constant value, the ceiling is computed at
 * compile time for optimization purposes.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Expression<Double> value = new DoubleConstant(3.2);
 * Ceiling ceil = new Ceiling(value);
 * // Generates: ceil(3.2)
 * // doubleValue() returns OptionalDouble.of(4.0)
 * }</pre>
 *
 * @see Expression
 * @see Floor
 */
public class Ceiling extends Expression<Double> {
	/**
	 * Constructs a new ceiling expression.
	 *
	 * @param input the expression to compute the ceiling of
	 */
	public Ceiling(Expression<Double> input) {
		super(Double.class, input);
	}

	/**
	 * Generates the ceiling expression string for the target language.
	 * <p>
	 * If the child has a known constant value, generates {@code ceil(value)}.
	 * Otherwise, generates {@code ceil(childExpression)}.
	 * </p>
	 *
	 * @param lang the language operations context
	 * @return the ceiling expression as a string
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		OptionalDouble v = getChildren().get(0).doubleValue();
		return v.isPresent() ? "ceil(" + v.getAsDouble() + ")" : "ceil(" + getChildren().get(0).getExpression(lang) + ")";
	}

	/**
	 * Returns the expression with appropriate wrapping.
	 * <p>
	 * The ceiling function call does not require additional parentheses.
	 * </p>
	 *
	 * @param lang the language operations context
	 * @return the ceiling expression string (same as getExpression)
	 */
	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	/**
	 * Attempts to compute the double value of this ceiling expression.
	 * <p>
	 * If the child expression has a known constant value, computes and returns
	 * the ceiling of that value. Otherwise, returns empty.
	 * </p>
	 *
	 * @return an OptionalDouble containing the ceiling value if computable, otherwise empty
	 */
	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble v = getChildren().get(0).doubleValue();
		if (v.isPresent()) return OptionalDouble.of(Math.ceil(v.getAsDouble()));
		return OptionalDouble.empty();
	}

	/**
	 * Computes the upper bound for this ceiling expression.
	 * <p>
	 * If a constant value is known, returns its ceiling. If the child has
	 * a known upper bound, returns the ceiling of that bound.
	 * </p>
	 *
	 * @param context the kernel structure context for bound computation
	 * @return an OptionalLong containing the upper bound if computable, otherwise empty
	 */
	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalDouble v = doubleValue();
		if (v.isPresent()) return OptionalLong.of((long) Math.ceil(v.getAsDouble()));

		OptionalLong u = getChildren().get(0).upperBound(context);
		if (u.isPresent()) return OptionalLong.of((long) Math.ceil(u.getAsLong()));

		return OptionalLong.empty();
	}

	/**
	 * Evaluates the ceiling operation on the provided child value.
	 *
	 * @param children the child expression values (expects exactly one)
	 * @return the ceiling of the child value as a Double
	 */
	@Override
	public Number evaluate(Number... children) {
		return Math.ceil(children[0].doubleValue());
	}

	/**
	 * Creates a new Ceiling expression with the specified children.
	 *
	 * @param children the new child expressions (must contain exactly one element)
	 * @return a new Ceiling expression wrapping the child
	 * @throws UnsupportedOperationException if children does not contain exactly one element
	 */
	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Ceiling((Expression<Double>) children.get(0));
	}
}
