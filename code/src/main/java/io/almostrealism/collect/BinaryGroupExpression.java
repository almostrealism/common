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

public class BinaryGroupExpression extends GroupExpression {
	public BinaryGroupExpression(String name, TraversalPolicy shape, int memberCount,
								 TraversableExpression a,
								 TraversableExpression b,
								 BiFunction<Expression[], Expression[], Expression<?>> combiner,
								 MemberIndexGenerator memberIndexGenerator) {
		super(name, shape, memberCount, memberIndexGenerator,
				members -> combiner.apply(members.get(0), members.get(1)), a, b);
	}

	public BinaryGroupExpression(String name, TraversalPolicy shape,
								 TraversableExpression a,
								 TraversableExpression b,
								 List<TraversableExpression<?>> memberIndices,
								 BiFunction<Expression[], Expression[], Expression<?>> combiner) {
		super(name, shape, memberIndices, members ->
				combiner.apply(members.get(0), members.get(1)), a, b);
	}
}
