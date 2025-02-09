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
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Logarithm;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Supplier;

public class CollectionLogarithmComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	public static boolean enableCustomDelta = true;

	public CollectionLogarithmComputation(TraversalPolicy shape,
											Producer<? extends PackedCollection<?>> input) {
		this(shape, (Supplier) input);
	}

	public CollectionLogarithmComputation(TraversalPolicy shape,
											Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		this("log", shape, input);
	}

	protected CollectionLogarithmComputation(String name, TraversalPolicy shape,
											   Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		super(name, shape, MultiTermDeltaStrategy.NONE, input);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new UniformCollectionExpression("log", getShape(), in -> Logarithm.of(in[0]), args[1]);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new CollectionLogarithmComputation<>(getName(), getShape(),
				(Supplier) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (!enableCustomDelta) {
			return super.delta(target);
		}

		CollectionProducer<T> delta = MatrixFeatures.getInstance().attemptDelta(this, target);
		if (delta != null) {
			return delta;
		}

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);
		CollectionProducer<T> input = (CollectionProducer) getInputs().get(1);

		CollectionProducer<T> d = input.delta(target);
		d = d.reshape(getShape().getTotalSize(), -1).traverse(0);

		CollectionProducer<T> scale = pow(input, c(-1));
		scale = scale.flatten();

		return expandAndMultiply(scale, d).reshape(shape);
	}
}
