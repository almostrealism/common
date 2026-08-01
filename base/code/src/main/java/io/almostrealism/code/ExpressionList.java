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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;

import java.util.ArrayList;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * An ordered list of {@link Expression} values that supports element-wise arithmetic operations.
 *
 * <p>{@code ExpressionList} extends {@link ArrayList} to provide bulk operations such as
 * element-wise negation, multiplication, summation, maximum, and exponentiation over a
 * collection of typed expressions. It is commonly used during code generation when building
 * intermediate results across multiple expression nodes.</p>
 *
 * @param <T> the numeric type of the expressions in this list
 *
 * @see Expression
 * @see Sum
 * @see Product
 */
public class ExpressionList<T> extends ArrayList<Expression<T>> {

	/**
	 * Returns the expression at the given position.
	 *
	 * @param pos the zero-based index of the expression to retrieve
	 * @return the expression at the specified position
	 */
	// @Override
	public Expression<T> getValue(int pos) {
		return get(pos);
	}

	/**
	 * Returns a new list where each expression has been negated ({@code -x}).
	 *
	 * @return a new {@code ExpressionList} with all elements negated
	 */
	public ExpressionList<T> minus() {
		return stream().map(Expression::minus).collect(collector());
	}

	/**
	 * Returns a new list where each element is the product of the corresponding elements
	 * from this list and the given operand list.
	 *
	 * @param operands the list of operands to multiply element-wise (must have the same size)
	 * @return a new {@code ExpressionList} containing the element-wise products
	 * @throws IllegalArgumentException if the two lists have different sizes
	 */
	public ExpressionList<T> multiply(ExpressionList<T> operands) {
		if (this.size() != operands.size()) {
			throw new IllegalArgumentException("Cannot multiply lists of different sizes");
		}

		ExpressionList result = new ExpressionList<>();

		for (int i = 0; i < operands.size(); i++) {
			result.add(Product.of((Expression) get(i), (Expression) operands.get(i)));
		}

		return result;
	}

	/**
	 * Returns an expression that sums all elements in this list.
	 *
	 * @return a sum expression over all elements
	 */
	public Expression<T> sum() {
		return Sum.of(toArray(Expression[]::new));
	}

	/**
	 * Returns an expression that computes the maximum of all elements in this list.
	 *
	 * @return a max expression over all elements
	 * @throws IllegalArgumentException if this list is empty
	 */
	public Expression<T> max() {
		if (size() <= 0) {
			throw new IllegalArgumentException("Maximum of zero expressions is undefined");
		}

		Expression max = get(0);
		for (int i = 1; i < size(); i++) {
			max = Max.of(max, (Expression) get(i));
		}

		return max;
	}

	/**
	 * Returns a new list where each element is the exponential ({@code e^x}) of the corresponding element.
	 *
	 * @return a new {@code ExpressionList} with all elements exponentiated
	 */
	public ExpressionList<T> exp() {
		ExpressionList result = new ExpressionList();
		for (Expression<T> e : this) result.add(e.exp());
		return result;
	}

	/**
	 * Returns a {@link Collector} that accumulates {@link Expression} elements into an {@code ExpressionList}.
	 *
	 * @return a collector for building an ExpressionList from a stream of expressions
	 */
	public static Collector<Expression, ?, ExpressionList> collector() {
		return Collectors.toCollection(() -> new ExpressionList());
	}
}
