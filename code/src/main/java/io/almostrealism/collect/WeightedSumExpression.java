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

public class WeightedSumExpression extends BinaryGroupExpression {
	public static boolean enableCollectionExpression = true;
	public static int simplifyThreshold = 1 << 16;

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
