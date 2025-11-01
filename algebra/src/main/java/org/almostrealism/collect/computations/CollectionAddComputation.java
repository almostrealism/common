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
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CollectionAddComputation<T extends PackedCollection<?>> extends TransitiveDeltaExpressionComputation<T> {

	public CollectionAddComputation(TraversalPolicy shape, Producer<PackedCollection<?>>... arguments) {
		this("add", shape, arguments);
	}

	protected CollectionAddComputation(String name, TraversalPolicy shape,
									   Producer<PackedCollection<?>>... arguments) {
		super(name, shape, arguments);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return sum(getShape(), Stream.of(args).skip(1).toArray(TraversableExpression[]::new));
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		List<Producer<?>> args = children.stream().skip(1)
				.map(p -> (Producer<?>) p).collect(Collectors.toList());
		return (CollectionProducerParallelProcess) add(args);
	}
}
