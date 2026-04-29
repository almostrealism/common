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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * A {@link BinaryGroupExpression} that uses the same index generator for both operands.
 *
 * <p>Provides two convenience constructors:
 * <ul>
 *   <li>One that accepts an {@link IntFunction} producing a common index expression for each
 *       group member, and wraps it in a {@link GroupExpression.MemberIndexGenerator} that
 *       ignores the operand index.</li>
 *   <li>One that accepts a pre-built list of member index expressions, one per group member.</li>
 * </ul>
 * </p>
 */
public class UniformBinaryGroupExpression extends BinaryGroupExpression {
	/**
	 * Creates a uniform binary group expression using a single index generator for all operands.
	 *
	 * @param shape                the output shape
	 * @param memberCount          the number of group members per output index
	 * @param a                    the first operand expression
	 * @param b                    the second operand expression
	 * @param combiner             the function combining member arrays from both operands into a value
	 * @param memberIndexGenerator a function from member index to the shared index expression
	 */
	public UniformBinaryGroupExpression(TraversalPolicy shape, int memberCount,
										TraversableExpression a,
										TraversableExpression b,
										BiFunction<Expression[], Expression[], Expression<?>> combiner,
										IntFunction<TraversableExpression<?>> memberIndexGenerator) {
		super(null, shape, memberCount, a, b, combiner,
				(memberIndex, operandIndex) ->
						memberIndexGenerator.apply(memberIndex));
	}

	/**
	 * Creates a uniform binary group expression using a pre-built list of member index expressions.
	 *
	 * @param name         a descriptive name for this expression
	 * @param shape        the output shape
	 * @param a            the first operand expression
	 * @param b            the second operand expression
	 * @param combiner     the function combining member arrays from both operands into a value
	 * @param memberIndices the index expressions, one per group member
	 */
	public UniformBinaryGroupExpression(String name, TraversalPolicy shape,
										TraversableExpression a,
										TraversableExpression b,
										BiFunction<Expression[], Expression[], Expression<?>> combiner,
										TraversableExpression<? extends Number>... memberIndices) {
		super(name, shape, a, b, List.of(memberIndices), combiner);
	}
}
