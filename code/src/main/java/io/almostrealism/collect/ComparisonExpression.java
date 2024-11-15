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

public class ComparisonExpression extends UniformConditionalExpression {
	public ComparisonExpression(String name, TraversalPolicy shape,
								BiFunction<Expression<?>, Expression<?>, Expression<Boolean>> comparison,
								TraversableExpression a, TraversableExpression b,
								TraversableExpression positive, TraversableExpression negative) {
		super(name, shape,
					args -> Conditional.of(comparison.apply(args[0], args[1]), args[2], args[3]),
				a, b, positive, negative);
	}
}
