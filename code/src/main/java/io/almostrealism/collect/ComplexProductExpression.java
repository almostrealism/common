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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;

public class ComplexProductExpression extends ConditionalExpressionBase {
	public ComplexProductExpression(TraversalPolicy shape,
									TraversableExpression a,
									TraversableExpression b) {
		super(shape, new ArithmeticSequenceExpression(shape),
				realPart(shape, a, b), imagPart(shape, a, b));
	}

	private static UniformBinaryGroupExpression realPart(TraversalPolicy shape,
														 TraversableExpression a,
														 TraversableExpression b) {
		return new UniformBinaryGroupExpression(shape, a, b, (left, right) -> {
				Expression p = left[0]; Expression q = left[1];
				Expression r = right[0]; Expression s = right[1];
				return Sum.of(Product.of(p, r), Minus.of(Product.of(q, s)));
			},
				idx -> idx.toInt().divide(2).multiply(2),
				idx -> idx.toInt().divide(2).multiply(2).add(1));
	}

	private static UniformBinaryGroupExpression imagPart(TraversalPolicy shape,
														 TraversableExpression a,
														 TraversableExpression b) {
		return new UniformBinaryGroupExpression(shape, a, b, (left, right) -> {
				Expression p = left[0]; Expression q = left[1];
				Expression r = right[0]; Expression s = right[1];
				return Sum.of(Product.of(p, s), Product.of(q, r));
			},
				idx -> idx.toInt().divide(2).multiply(2),
				idx -> idx.toInt().divide(2).multiply(2).add(1));
	}
}
