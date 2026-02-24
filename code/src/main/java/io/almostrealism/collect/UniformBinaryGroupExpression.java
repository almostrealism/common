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

public class UniformBinaryGroupExpression extends BinaryGroupExpression {
	public UniformBinaryGroupExpression(TraversalPolicy shape, int memberCount,
										TraversableExpression a,
										TraversableExpression b,
										BiFunction<Expression[], Expression[], Expression<?>> combiner,
										IntFunction<TraversableExpression<?>> memberIndexGenerator) {
		super(null, shape, memberCount, a, b, combiner,
				(memberIndex, operandIndex) ->
						memberIndexGenerator.apply(memberIndex));
	}

	public UniformBinaryGroupExpression(String name, TraversalPolicy shape,
										TraversableExpression a,
										TraversableExpression b,
										BiFunction<Expression[], Expression[], Expression<?>> combiner,
										TraversableExpression<? extends Number>... memberIndices) {
		super(name, shape, a, b, List.of(memberIndices), combiner);
	}
}
