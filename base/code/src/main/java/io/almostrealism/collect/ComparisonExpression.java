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

package io.almostrealism.collect;

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;

import java.util.function.BiFunction;

/**
 * A {@link UniformConditionalExpression} that implements element-wise comparison
 * operations between two collections, selecting values from positive or negative
 * result collections based on the comparison outcome.
 *
 * <p>For each element index, this expression:</p>
 * <ol>
 *   <li>Evaluates the comparison function on corresponding elements from collections {@code a} and {@code b}</li>
 *   <li>If the comparison is true, returns the value from the {@code positive} collection</li>
 *   <li>If the comparison is false, returns the value from the {@code negative} collection</li>
 * </ol>
 *
 * <p>This is useful for implementing operations like element-wise maximum, minimum,
 * clamping, and conditional selection based on comparisons.</p>
 *
 * @see UniformConditionalExpression
 * @see Conditional
 */
public class ComparisonExpression extends UniformConditionalExpression {

	/**
	 * Constructs a new comparison expression with the specified comparison function
	 * and source collections.
	 *
	 * @param name       a descriptive name for this expression
	 * @param shape      the {@link TraversalPolicy} defining the output shape
	 * @param comparison a function that takes two expressions and returns a boolean
	 *                   expression representing the comparison result
	 * @param a          the first collection to compare (provides first comparison operand)
	 * @param b          the second collection to compare (provides second comparison operand)
	 * @param positive   the collection to select values from when comparison is true
	 * @param negative   the collection to select values from when comparison is false
	 */
	public ComparisonExpression(String name, TraversalPolicy shape,
								BiFunction<Expression<?>, Expression<?>, Expression<Boolean>> comparison,
								TraversableExpression a, TraversableExpression b,
								TraversableExpression positive, TraversableExpression negative) {
		super(name, shape,
					args -> Conditional.of(comparison.apply(args[0], args[1]), args[2], args[3]),
				a, b, positive, negative);
	}
}
