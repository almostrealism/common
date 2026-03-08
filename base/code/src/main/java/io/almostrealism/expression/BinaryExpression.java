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

/**
 * Abstract base class for expressions that operate on two operands (left and right).
 * <p>
 * This class extends {@link Expression} to provide a foundation for binary operations
 * such as addition, subtraction, multiplication, division, comparisons, and logical
 * operations. Subclasses implement specific operators by overriding the expression
 * generation methods.
 * </p>
 * <p>
 * The two child expressions are accessed via {@link #getLeft()} and {@link #getRight()},
 * which provide type-safe accessors to the underlying children list.
 * </p>
 *
 * <h2>Example Subclasses</h2>
 * <ul>
 *   <li>Sum - addition operation</li>
 *   <li>Product - multiplication operation</li>
 *   <li>Quotient - division operation</li>
 *   <li>Comparison operators - less than, greater than, etc.</li>
 * </ul>
 *
 * @param <T> the result type of the binary expression
 * @see Expression
 * @see UnaryExpression
 */
public abstract class BinaryExpression<T> extends Expression<T> {

	/**
	 * Constructs a new binary expression with the specified operands.
	 *
	 * @param type  the Java class representing the result type
	 * @param left  the left operand expression
	 * @param right the right operand expression
	 */
	public BinaryExpression(Class<T> type, Expression<?> left, Expression<?> right) {
		super(type, left, right);
	}

	/**
	 * Returns the left operand of this binary expression.
	 *
	 * @return the left operand expression
	 */
	public Expression<?> getLeft() { return getChildren().get(0); }

	/**
	 * Returns the right operand of this binary expression.
	 *
	 * @return the right operand expression
	 */
	public Expression<?> getRight() { return getChildren().get(1); }

	/**
	 * Determines whether this expression could potentially produce a negative value.
	 * <p>
	 * For binary expressions, this returns true if either operand could be negative.
	 * Subclasses may override this to provide more precise analysis based on the
	 * specific operation (e.g., absolute value of a subtraction).
	 * </p>
	 *
	 * @return true if either operand could be negative, false otherwise
	 */
	@Override
	public boolean isPossiblyNegative() {
		return getLeft().isPossiblyNegative() || getRight().isPossiblyNegative();
	}
}
