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
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GroupExpression extends OperandCollectionExpression {
	private final int memberCount;
	private final MemberIndexGenerator memberGenerator;

	private final Function<List<Expression[]>, Expression> combiner;

	public GroupExpression(String name, TraversalPolicy shape,
						   List<TraversableExpression<?>> memberIndices,
						   Function<List<Expression[]>, Expression> combiner,
						   TraversableExpression... operands) {
		this(name, shape, memberIndices.size(), memberIndices::get, combiner, operands);
	}

	public GroupExpression(String name, TraversalPolicy shape,
						   int memberCount,
						   IntFunction<TraversableExpression<?>> memberIndexGenerator,
						   Function<List<Expression[]>, Expression> combiner,
						   TraversableExpression... operands) {
		this(name, shape, memberCount, (memberIndex, operandIndex) ->
						memberIndexGenerator.apply(memberIndex),
				combiner, operands);
	}

	public GroupExpression(String name, TraversalPolicy shape,
						   int memberCount,
						   MemberIndexGenerator memberIndexGenerator,
						   Function<List<Expression[]>, Expression> combiner,
						   TraversableExpression... operands) {
		super(name, shape, operands);
		this.memberCount = memberCount;
		this.memberGenerator = memberIndexGenerator;
		this.combiner = combiner;
	}

	protected List<Expression[]> getMembers(Expression<?> index) {
		return IntStream.range(0, getOperands().size())
				.mapToObj(operand -> {
					Expression opMembers[] = new Expression[memberCount];

					for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
						opMembers[memberIndex] = getOperands().get(operand).getValueAt(
								memberGenerator.indexGenerator(memberIndex, operand).getValueAt(index));
					}

					return opMembers;
				}).collect(Collectors.toUnmodifiableList());
	}

	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		List<Expression[]> members = getMembers(index);
		return combiner.apply(members);
	}

	public interface MemberIndexGenerator {
		TraversableExpression<?> indexGenerator(int memberIndex, int operandIndex);
	}
}
