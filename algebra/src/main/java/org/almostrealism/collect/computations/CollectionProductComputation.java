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
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class CollectionProductComputation<T extends PackedCollection<?>> extends DefaultTraversableExpressionComputation<T> {
	public CollectionProductComputation(TraversalPolicy shape, Producer<? extends PackedCollection<?>>... arguments) {
		this("multiply", shape, arguments);
	}

	public CollectionProductComputation(TraversalPolicy shape,
										Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		this("multiply", shape, arguments);
	}

	protected CollectionProductComputation(String name, TraversalPolicy shape,
										   Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super("multiply", shape, MultiTermDeltaStrategy.COMBINE,
				args ->
						ExpressionFeatures.getInstance().product(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				arguments);
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = attemptDelta(target);
		if (delta != null) return delta;

		TraversalPolicy targetShape = shape(target);

		List<CollectionProducer<PackedCollection<?>>> operands = List.of(
				getChildren().stream().skip(1)
					.filter(p -> p instanceof CollectionProducer)
					.toArray(CollectionProducer[]::new));

		if (operands.size() != getChildren().size() - 1) {
			return super.delta(target);
		}

		TraversalPolicy shape = getShape().append(targetShape);

		if (operands.size() == 2) {
			int finalLength = shape.getTotalSize();
			int outLength = getShape().getTotalSize();
			int inLength = targetShape.getTotalSize();

			CollectionProducer<PackedCollection<?>> u = operands.get(0);
			CollectionProducer<PackedCollection<?>> v = operands.get(1);
			CollectionProducer<PackedCollection<?>> uDelta = u.delta(target);
			CollectionProducer<PackedCollection<?>> vDelta = v.delta(target);

//			u = u.flatten().each().repeat(inLength);
//			v = v.flatten().each().repeat(inLength);

			u = diagonal(u.flatten());
			v = diagonal(v.flatten());

			return (CollectionProducer) matmul(u, vDelta).add(matmul(v, uDelta)).reshape(shape);
		}

		warn("Product delta not implemented for more than two operands");
		return super.delta(target);
	}
}
