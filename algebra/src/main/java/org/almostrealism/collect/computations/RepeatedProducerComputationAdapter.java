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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

public class RepeatedProducerComputationAdapter<T extends PackedCollection<?>> extends RepeatedProducerComputation<T> {

	public RepeatedProducerComputationAdapter(TraversalPolicy shape,
											  TraversableExpression expression,
											  Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(shape,
				(args, index) ->
						new DoubleConstant(0.0),
				null,
				(args, index) ->
						expression.getValueAt(index),
				arguments);

		setCondition((args, index) -> index.lessThan(((ArrayVariable) getOutputVariable()).length()));
	}

	@Override
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset) {
		return ((ArrayVariable) getOutputVariable()).valueAt(localIndex);
	}
}
