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
import io.almostrealism.expression.IntegerConstant;

/**
 * A {@link BinaryGroupExpression} that computes a weighted sum (dot product) of two operands.
 *
 * <p>For each output index, {@code memberCount} elements are gathered from the input ({@code a})
 * and weight ({@code b}) operands and accumulated as {@code sum(input[i] * weight[i])}. Each
 * element pair is simplified before multiplication to keep the expression tree manageable when
 * the group size is large.</p>
 */
public class WeightedSumExpression extends BinaryGroupExpression {
	/**
	 * Whether to use a {@link CollectionExpression}-based implementation when available.
	 */
	public static boolean enableCollectionExpression = true;

	/**
	 * Node-count threshold at which each gathered element expression is simplified
	 * before being included in the sum.
	 */
	public static int simplifyThreshold = 1 << 16;

	/**
	 * Creates a weighted sum expression with the given shape, group size, operands, and index generator.
	 *
	 * @param shape                the output shape
	 * @param memberCount          the number of elements to sum per output index
	 * @param a                    the input operand (values to be weighted)
	 * @param b                    the weight operand
	 * @param memberIndexGenerator the generator producing input/weight indices for each group member
	 */
	public WeightedSumExpression(TraversalPolicy shape, int memberCount,
								 TraversableExpression a, TraversableExpression b,
								 MemberIndexGenerator memberIndexGenerator) {
		super("weightedSum", shape, memberCount, a, b, (input, weights) -> {
			Expression<?> result = new IntegerConstant(0);
			for (int i = 0; i < input.length; i++) {
				input[i] = simplify(simplifyThreshold, input[i]);
				weights[i] = simplify(simplifyThreshold, weights[i]);

				result = result.add(input[i].multiply(weights[i]));
				result = result.generate(result.flatten());
			}
			return result;
		}, memberIndexGenerator);
	}
}
