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

/**
 * A specialized {@link GroupExpression} that combines exactly two
 * {@link TraversableExpression} operands using a binary combiner function.
 *
 * <p>{@link BinaryGroupExpression} simplifies the creation of group expressions
 * that operate on pairs of expressions, such as element-wise operations,
 * dot products, or other binary computations over grouped elements.
 *
 * <h2>How It Works</h2>
 *
 * <p>For each output index:
 * <ol>
 *   <li>The member index generator determines which indices to access from each operand</li>
 *   <li>Values are retrieved from both operands at the generated member indices</li>
 *   <li>The combiner function receives arrays of values from both operands and
 *       produces a single output value</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create a binary group expression for element-wise multiplication
 * TraversableExpression a = ...;  // First operand
 * TraversableExpression b = ...;  // Second operand
 * TraversalPolicy shape = new TraversalPolicy(10);
 *
 * // Multiply corresponding elements
 * BinaryGroupExpression multiply = new BinaryGroupExpression(
 *     "multiply", shape, 1,
 *     a, b,
 *     (aValues, bValues) -> aValues[0].multiply(bValues[0]),
 *     (memberIndex, operandIndex) -> index -> index  // Identity mapping
 * );
 *
 * // Dot product: sum of element-wise products
 * int vectorSize = 4;
 * BinaryGroupExpression dot = new BinaryGroupExpression(
 *     "dot", shape, vectorSize,
 *     a, b,
 *     (aValues, bValues) -> {
 *         Expression sum = e(0.0);
 *         for (int i = 0; i < vectorSize; i++) {
 *             sum = sum.add(aValues[i].multiply(bValues[i]));
 *         }
 *         return sum;
 *     },
 *     (memberIndex, operandIndex) -> memberIndices.get(memberIndex)
 * );
 * }</pre>
 *
 * <h2>Member Index Generation</h2>
 *
 * <p>Two constructors are provided for different index generation strategies:
 * <ul>
 *   <li>{@link MemberIndexGenerator} - A function that generates indices based on
 *       member index and operand index, allowing different access patterns per operand</li>
 *   <li>Pre-computed list of {@link TraversableExpression} - When the same indices
 *       are used for both operands</li>
 * </ul>
 *
 * @see GroupExpression
 * @see TraversableExpression
 * @author Michael Murray
 */
public class BinaryGroupExpression extends GroupExpression {

	/**
	 * Constructs a {@link BinaryGroupExpression} with a custom member index generator.
	 *
	 * <p>This constructor allows for flexible index generation where the indices
	 * accessed from each operand can depend on both the member position and which
	 * operand is being accessed.
	 *
	 * @param name the descriptive name for this expression
	 * @param shape the {@link TraversalPolicy} defining the output dimensions
	 * @param memberCount the number of members (elements) to combine for each output
	 * @param a the first {@link TraversableExpression} operand
	 * @param b the second {@link TraversableExpression} operand
	 * @param combiner a function that takes arrays of values from both operands
	 *                 and produces a single output expression
	 * @param memberIndexGenerator a generator that produces index expressions for
	 *                             accessing member values from each operand
	 */
	public BinaryGroupExpression(String name, TraversalPolicy shape, int memberCount,
								 TraversableExpression a,
								 TraversableExpression b,
								 BiFunction<Expression[], Expression[], Expression<?>> combiner,
								 MemberIndexGenerator memberIndexGenerator) {
		super(name, shape, memberCount, memberIndexGenerator,
				members -> combiner.apply(members.get(0), members.get(1)), a, b);
	}

	/**
	 * Constructs a {@link BinaryGroupExpression} with pre-computed member indices.
	 *
	 * <p>This constructor is useful when the same index expressions are used for
	 * both operands and the indices can be pre-computed as a list.
	 *
	 * @param name the descriptive name for this expression
	 * @param shape the {@link TraversalPolicy} defining the output dimensions
	 * @param a the first {@link TraversableExpression} operand
	 * @param b the second {@link TraversableExpression} operand
	 * @param memberIndices a list of {@link TraversableExpression} objects that
	 *                      generate the indices for accessing members from both operands
	 * @param combiner a function that takes arrays of values from both operands
	 *                 and produces a single output expression
	 */
	public BinaryGroupExpression(String name, TraversalPolicy shape,
								 TraversableExpression a,
								 TraversableExpression b,
								 List<TraversableExpression<?>> memberIndices,
								 BiFunction<Expression[], Expression[], Expression<?>> combiner) {
		super(name, shape, memberIndices, members ->
				combiner.apply(members.get(0), members.get(1)), a, b);
	}
}
