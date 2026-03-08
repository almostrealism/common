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
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;

import java.util.Optional;
import java.util.OptionalInt;

// TODO  Perhaps this should extend UniformCollectionExpression
// TODO  (it is a uniform translation of the input expression)
public class ConditionalIndexExpression extends OperandCollectionExpression {
	public ConditionalIndexExpression(TraversalPolicy shape, TraversableExpression in) {
		super(null, shape, in);
	}

	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		TraversableExpression<Double> value = getOperands().get(0);

		index = index.toInt().imod(getShape().getTotalSize());

		OptionalInt i = index.intValue();

		if (i.isPresent()) {
			return value.getValueAt(index);
		} else {
			Expression v = value.getValueAt(new IntegerConstant(0));

			for (int j = 1; j < getShape().getTotalSize(); j++) {
				v = Conditional.of(index.eq(new IntegerConstant(j)), value.getValueAt(new IntegerConstant(j)), v);
			}

			return v;
		}
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return getOperands().get(0).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	public Optional<Boolean> containsIndex(int index) {
		return getOperands().get(0).containsIndex(index);
	}
}
