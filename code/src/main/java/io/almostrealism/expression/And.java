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

import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Represents a bitwise AND operation between two integer expressions.
 * <p>
 * The {@code And} expression performs a bitwise AND ({@code &}) operation on two
 * {@link Expression} instances of type {@code Integer}. This is distinct from logical
 * AND operations (see {@link Conjunction} for boolean logical AND).
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Expression<Integer> a = new IntegerConstant(0b1100);  // 12
 * Expression<Integer> b = new IntegerConstant(0b1010);  // 10
 * Expression<Integer> result = new And(a, b);
 * // When evaluated: result = 12 & 10 = 0b1000 = 8
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li>Bit masking operations for extracting specific bits from integer values</li>
 *   <li>Efficient modulo operations when the divisor is a power of 2
 *       (e.g., {@code x & (n-1)} is equivalent to {@code x % n} when n is a power of 2)</li>
 *   <li>Flag checking and manipulation in packed integer values</li>
 * </ul>
 *
 * <h2>Type Requirements</h2>
 * <p>
 * Both operand expressions must be of type {@code Integer}. The constructor will
 * throw an {@link UnsupportedOperationException} if this constraint is violated.
 * The result type is also {@code Integer}.
 * </p>
 *
 * @see BinaryExpression
 * @see Conjunction for boolean logical AND operations
 * @see Mod for modulo operations that may be optimized to bitwise AND
 *
 * @author Michael Murray
 */
public class And extends BinaryExpression<Integer> {

	/**
	 * Constructs a new bitwise AND expression from two integer expressions.
	 *
	 * @param a the left operand expression; must be of type {@code Integer}
	 * @param b the right operand expression; must be of type {@code Integer}
	 * @throws UnsupportedOperationException if either operand is not of type {@code Integer}
	 */
	public And(Expression<Integer> a, Expression<Integer> b) {
		super(Integer.class, a, b);

		if (a.getType() != Integer.class || b.getType() != Integer.class)
			throw new UnsupportedOperationException();
	}

	/**
	 * Generates the string representation of this bitwise AND expression for the target language.
	 * <p>
	 * Produces an infix expression with the {@code &} operator, with both operands
	 * wrapped in parentheses to ensure correct precedence.
	 * </p>
	 *
	 * @param lang the language operations provider used to generate language-specific syntax
	 * @return a string in the form {@code "(left) & (right)"}
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		return  getChildren().get(0).getWrappedExpression(lang) + " & " +
					getChildren().get(1).getWrappedExpression(lang);
	}

	/**
	 * Attempts to compute the constant integer value of this expression.
	 * <p>
	 * If both operands have known constant integer values, this method computes
	 * and returns their bitwise AND. Otherwise, it delegates to the superclass.
	 * </p>
	 *
	 * @return an {@link OptionalInt} containing the computed value if both operands
	 *         are constant, or empty if the value cannot be determined at compile time
	 */
	@Override
	public OptionalInt intValue() {
		Expression a = getChildren().get(0);
		Expression b = getChildren().get(1);

		if (a.intValue().isPresent() && b.intValue().isPresent()) {
			return OptionalInt.of(a.intValue().getAsInt() & b.intValue().getAsInt());
		}

		return super.intValue();
	}

	/**
	 * Computes the upper bound of this expression's possible values.
	 * <p>
	 * For a bitwise AND operation, the result cannot exceed the smaller of the
	 * two operands. Since the mask (right operand) typically defines the maximum
	 * possible result, this implementation returns the upper bound of the right operand.
	 * </p>
	 *
	 * @param context the kernel structure context for bound computation
	 * @return the upper bound of the right (mask) operand
	 */
	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return getChildren().get(1).upperBound(context);
	}

	/**
	 * Creates a new {@code And} expression with the specified child expressions.
	 * <p>
	 * This method is used internally when transforming or simplifying expression trees.
	 * It validates that exactly two child expressions are provided.
	 * </p>
	 *
	 * @param children a list containing exactly two {@link Expression} elements
	 * @return a new {@code And} expression with the provided operands
	 * @throws UnsupportedOperationException if the children list does not contain exactly two elements
	 */
	@Override
	public Expression<Integer> recreate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new And((Expression) children.get(0), (Expression) children.get(1));
	}

	/**
	 * Determines whether this expression can be evaluated with the given index values.
	 * <p>
	 * The expression can be evaluated if both operands can be evaluated with the
	 * provided index values.
	 * </p>
	 *
	 * @param values the index values to check against
	 * @return {@code true} if both operands can be evaluated, {@code false} otherwise
	 */
	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values) && getChildren().get(1).isValue(values);
	}

	/**
	 * Evaluates this expression with the given index values.
	 * <p>
	 * Computes the bitwise AND of both operands evaluated with the provided index values.
	 * </p>
	 *
	 * @param indexValues the index values to use during evaluation
	 * @return the result of the bitwise AND operation as an {@link Integer}
	 */
	@Override
	public Number value(IndexValues indexValues) {
		return getChildren().get(0).value(indexValues).intValue() & getChildren().get(1).value(indexValues).intValue();
	}

	/**
	 * Evaluates the bitwise AND of the provided numeric arguments.
	 *
	 * @param children an array containing exactly two {@link Number} elements
	 *                 representing the evaluated values of both operands
	 * @return the result of {@code children[0] & children[1]} as an {@link Integer}
	 */
	@Override
	public Number evaluate(Number... children) {
		return children[0].intValue() & children[1].intValue();
	}

	/**
	 * Factory method for creating an {@code And} expression.
	 * <p>
	 * This is a convenience method that handles the type casting of generic
	 * expressions to the required {@code Expression<Integer>} type.
	 * </p>
	 *
	 * @param a the left operand expression
	 * @param b the right operand expression
	 * @return a new {@code And} expression performing bitwise AND on the operands
	 */
	public static Expression<?> of(Expression<?> a, Expression<?> b) {
		return new And((Expression) a, (Expression) b);
	}
}
