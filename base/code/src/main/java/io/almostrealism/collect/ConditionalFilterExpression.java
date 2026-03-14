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

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;

import java.util.function.Function;

public class ConditionalFilterExpression extends UniformConditionalExpression {
	public ConditionalFilterExpression(String name, TraversalPolicy shape,
									   Function<Expression<?>, Expression<Boolean>> condition,
									   Function<Expression<?>, Expression<?>> filter,
									   boolean positive,
									   TraversableExpression input) {
		super(name, shape,
				args -> Conditional.of(condition.apply(args[0]),
						positive ? filter.apply(args[0]) : args[0],
						positive ? args[0] : filter.apply(args[0])),
				input);
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		// TODO  This should return a ConditionalFilterExpression
		// TODO  that chooses between the delta of the filtered input
		// TODO  and the delta of the unfiltered input based on
		// TODO  the same condition as is used by this expression
		return super.delta(target);
	}
}
