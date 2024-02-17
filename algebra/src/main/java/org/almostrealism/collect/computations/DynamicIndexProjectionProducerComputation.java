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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class DynamicIndexProjectionProducerComputation<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	private BiFunction<TraversableExpression, Expression, Expression> indexExpression;

	public DynamicIndexProjectionProducerComputation(TraversalPolicy shape, Producer<?> collection,
											BiFunction<TraversableExpression, Expression, Expression> indexExpression) {
		super(shape, collection, null);
		this.indexExpression = indexExpression;
	}

	@Override
	protected Expression<?> projectIndex(TraversableExpression<?> input, Expression<?> index) {
		return indexExpression.apply(input, index);
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		return (DynamicIndexProjectionProducerComputation)
				new DynamicIndexProjectionProducerComputation<>(getShape(), (Producer<?>) children.get(1), indexExpression)
						.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (enableChainDelta) {
			TraversableDeltaComputation<T> delta =
					TraversableDeltaComputation.create(getShape(), shape(target),
								args -> CollectionExpression.create(getShape(),
										(idx) -> args[1].getValueAt(projectIndex(args[1], idx))),
							target, getInputs().stream().skip(1).toArray(Supplier[]::new));
			return delta;
		} else {
			return super.delta(target);
		}
	}
}