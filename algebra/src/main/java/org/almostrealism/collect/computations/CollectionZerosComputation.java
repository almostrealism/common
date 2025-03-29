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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

public class CollectionZerosComputation<T extends PackedCollection<?>> extends CollectionConstantComputation<T> {
	public CollectionZerosComputation(TraversalPolicy shape) {
		super("zeros", shape);
	}

	@Override
	public boolean isZero() { return true; }

	@Override
	protected ConstantCollectionExpression getExpression(TraversableExpression... args) {
		return ExpressionFeatures.getInstance().constantZero(getShape());
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		throw new UnsupportedOperationException();
	}

	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new CollectionZerosComputation<>(getShape().traverse(axis));
	}

	@Override
	public CollectionProducerComputation<T> reshape(TraversalPolicy shape) {
		return new CollectionZerosComputation<>(shape);
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		return new CollectionZerosComputation<>(getShape().append(shape(target)));
	}
}

