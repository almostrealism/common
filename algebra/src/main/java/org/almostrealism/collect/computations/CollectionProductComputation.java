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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class CollectionProductComputation<T extends PackedCollection<?>> extends DefaultTraversableExpressionComputation<T> {
	public static boolean enableAttemptDelta = false;

	public CollectionProductComputation(TraversalPolicy shape, Producer<? extends PackedCollection<?>>... arguments) {
		this("multiply", shape, arguments);
	}

	public CollectionProductComputation(TraversalPolicy shape,
										Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		this("multiply", shape, arguments);
	}

	protected CollectionProductComputation(String name, TraversalPolicy shape,
										   Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(name, shape, MultiTermDeltaStrategy.NONE,
				args ->
						ExpressionFeatures.getInstance().product(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				arguments);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProductComputation<T>) new CollectionProductComputation(getName(), getShape(),
				children.stream().skip(1).toArray(Supplier[]::new))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (enableAttemptDelta) {
			CollectionProducer<T> delta = attemptDelta(target);
			if (delta != null) return delta;
		}

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

	// TODO  It seems like this should be something that is just
	// TODO  part of MatrixFeatures, or even an option for matmul
	protected <V extends PackedCollection<?>> CollectionProducer<V> expandAndMultiply(
			CollectionProducer<V> vector, CollectionProducer<V> matrix) {
		if (vector.getShape().getDimensions() != 1) {
			throw new IllegalArgumentException();
		} else if (Algebraic.isIdentity(shape(vector).length(0), matrix)) {
			return diagonal(vector);
		} else {
			CollectionProducer<V> expanded = vector.traverse(1).repeat(matrix.getShape().length(1));
			return multiply(expanded, matrix);
		}
	}
}
