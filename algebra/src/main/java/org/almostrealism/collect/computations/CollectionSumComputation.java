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

public class CollectionSumComputation<T extends PackedCollection<?>> extends DefaultTraversableExpressionComputation<T> {

	public CollectionSumComputation(TraversalPolicy shape, Producer<? extends PackedCollection<?>>... arguments) {
		this("add", shape, arguments);
	}

	public CollectionSumComputation(TraversalPolicy shape,
										Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		this("add", shape, arguments);
	}

	protected CollectionSumComputation(String name, TraversalPolicy shape,
										   Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super("add", shape, MultiTermDeltaStrategy.IGNORE,
				args ->
						ExpressionFeatures.getInstance().sum(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
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

		boolean supported = true;

		if (operands.size() != getChildren().size() - 1) {
			supported = false;
		} else if (operands.stream().anyMatch(o -> !o.isFixedCount())) {
			warn("Product delta not implemented for variable count operands");
			supported = false;
		}

		if (!supported) {
			return super.delta(target);
		}

		TraversalPolicy shape = getShape().append(targetShape);

		List<CollectionProducer<PackedCollection<?>>> deltas = new ArrayList<>();

		for (CollectionProducer<PackedCollection<?>> operand : operands) {
			deltas.add(operand.delta(target));
		}

		return new CollectionSumComputation<>(shape, deltas.toArray(CollectionProducer[]::new));
	}
}
