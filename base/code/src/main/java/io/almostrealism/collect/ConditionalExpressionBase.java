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

import java.util.OptionalInt;

public class ConditionalExpressionBase extends OperandCollectionExpression {
	private TraversableExpression<Double> choice;

	public ConditionalExpressionBase(TraversalPolicy shape,
									 TraversableExpression choice,
									 TraversableExpression... choices) {
		super(null, shape, choices);
		this.choice = choice;
	}

	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		Expression ch = choice.getValueAt(index).toInt().imod(getOperands().size());

		OptionalInt i = ch.intValue();

		if (i.isPresent()) {
			return getOperands().get(i.getAsInt()).getValueAt(index);
		}

		Expression v = getOperands().get(0).getValueAt(index);

		for (int j = 1; j < getOperands().size(); j++) {
			v = Conditional.of(ch.eq(e(j)),
					getOperands().get(j).getValueAt(index), v);
		}

		return v;
	}

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		// TODO
		return super.containsIndex(index);
	}
}
