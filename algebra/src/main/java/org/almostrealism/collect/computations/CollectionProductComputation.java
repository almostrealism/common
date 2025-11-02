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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Stream;

public class CollectionProductComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {

	public CollectionProductComputation(TraversalPolicy shape,
										Producer<PackedCollection<?>>... arguments) {
		this("multiply", shape, arguments);
	}

	protected CollectionProductComputation(String name, TraversalPolicy shape,
										   Producer<PackedCollection<?>>... arguments) {
		super(name, shape, MultiTermDeltaStrategy.NONE, arguments);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return product(getShape(), Stream.of(args).skip(1).toArray(TraversableExpression[]::new));
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProductComputation<T>) new CollectionProductComputation(getName(), getShape(),
				children.stream().skip(1).toArray(Producer[]::new))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		TraversalPolicy targetShape = shape(target);

		List<CollectionProducer<PackedCollection<?>>> operands = List.of(
				getChildren().stream().skip(1)
					.filter(p -> p instanceof CollectionProducer)
					.toArray(CollectionProducer[]::new));

		boolean supported = true;

		if (operands.size() != getChildren().size() - 1) {
			supported = false;
		} else if (operands.size() != 2) {
			warn("Product delta not implemented for more than two operands");
			supported = false;
		} else if (operands.stream().anyMatch(o -> !o.isFixedCount())) {
			warn("Product delta not implemented for variable count operands");
			supported = false;
		}

		if (!supported) {
			return super.delta(target);
		}

		TraversalPolicy shape = getShape().append(targetShape);

		CollectionProducer<PackedCollection<?>> u = operands.get(0);
		CollectionProducer<PackedCollection<?>> v = operands.get(1);
		CollectionProducer<PackedCollection<?>> uDelta = u.delta(target);
		CollectionProducer<PackedCollection<?>> vDelta = v.delta(target);

		uDelta = uDelta.reshape(v.getShape().getTotalSize(), -1).traverse(0);
		vDelta = vDelta.reshape(u.getShape().getTotalSize(), -1).traverse(0);
		return (CollectionProducer) expandAndMultiply(u.flatten(), vDelta)
				.add(expandAndMultiply(v.flatten(), uDelta)).reshape(shape);
	}
}
