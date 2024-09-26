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

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class GroupExpression extends OperandCollectionExpression {
	private final List<UnaryOperator<Expression<?>>> memberIndices;
	private final Function<List<Expression[]>, Expression> combiner;

	public GroupExpression(TraversalPolicy shape,
						   List<UnaryOperator<Expression<?>>> memberIndices,
						   Function<List<Expression[]>, Expression> combiner,
						   TraversableExpression... operands) {
		super(shape, operands);
		this.memberIndices = memberIndices;
		this.combiner = combiner;
	}

	protected List<Expression[]> getMembers(Expression<?> index) {
		return getOperands().stream()
				.map(operand -> {
					Expression opMembers[] = new Expression[memberIndices.size()];

					for (int i = 0; i < memberIndices.size(); i++) {
						opMembers[i] = operand.getValueAt(memberIndices.get(i).apply(index));
					}

					return opMembers;
				}).collect(Collectors.toUnmodifiableList());
	}

	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		return combiner.apply(getMembers(index));
	}
}
