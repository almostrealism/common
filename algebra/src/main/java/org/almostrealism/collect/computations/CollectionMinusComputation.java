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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Supplier;

public class CollectionMinusComputation<T extends PackedCollection<?>> extends TransitiveDeltaExpressionComputation<T> {

	public CollectionMinusComputation(TraversalPolicy shape, Producer<? extends PackedCollection<?>>... arguments) {
		this("minus", shape, arguments);
	}

	public CollectionMinusComputation(TraversalPolicy shape,
									Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		this("minus", shape, arguments);
	}

	protected CollectionMinusComputation(String name, TraversalPolicy shape,
									   Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(name, shape,
				args ->
						ExpressionFeatures.getInstance().minus(shape, args[1]),
				arguments);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		if (children.size() != 2) {
			throw new IllegalArgumentException();
		}

		return (CollectionProducerParallelProcess) minus((Producer) children.stream().skip(1).findFirst().orElseThrow());
	}
}
