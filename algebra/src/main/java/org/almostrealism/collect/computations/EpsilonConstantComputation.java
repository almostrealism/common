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

import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

public class EpsilonConstantComputation<T extends PackedCollection<?>> extends SingleConstantComputation<T> {
	public EpsilonConstantComputation(TraversalPolicy shape) {
		super("epsilon", shape, 0.0);
	}

	@Override
	protected ConstantCollectionExpression getExpression(TraversableExpression... args) {
		return new ConstantCollectionExpression(getShape(), epsilon());
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new EpsilonConstantComputation<>(getShape().traverse(axis));
	}

	@Override
	public CollectionProducerComputation<T> reshape(TraversalPolicy shape) {
		return new EpsilonConstantComputation<>(shape);
	}
}
