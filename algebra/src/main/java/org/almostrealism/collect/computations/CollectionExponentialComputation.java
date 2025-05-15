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
import io.almostrealism.collect.ConditionalFilterExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Supplier;

public class CollectionExponentialComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	private final boolean ignoreZero;

	public CollectionExponentialComputation(TraversalPolicy shape,
											Producer<? extends PackedCollection<?>> input) {
		this(shape, false, (Supplier) input);
	}

	public CollectionExponentialComputation(TraversalPolicy shape, boolean ignoreZero,
										 	Producer<? extends PackedCollection<?>> input) {
		this(shape, ignoreZero, (Supplier) input);
	}

	public CollectionExponentialComputation(TraversalPolicy shape,
											Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		this(shape, false, input);
	}

	public CollectionExponentialComputation(TraversalPolicy shape, boolean ignoreZero,
										 	Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		this(ignoreZero ? "expIgnoreZero" : "exp", shape, ignoreZero, input);
	}

	protected CollectionExponentialComputation(String name, TraversalPolicy shape, boolean ignoreZero,
											Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		super(name, shape, MultiTermDeltaStrategy.NONE, input);
		this.ignoreZero = ignoreZero;
	}

	public boolean isIgnoreZero() {
		return ignoreZero;
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		if (isIgnoreZero()) {
			return new ConditionalFilterExpression("expIgnoreZero", getShape(),
						Expression::eqZero, Exp::of, false, args[1]);
		} else {
			return new UniformCollectionExpression("exp", getShape(),
						in -> Exp.of(in[0]), args[1]);
		}
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new CollectionExponentialComputation<>(getName(), getShape(), isIgnoreZero(),
				(Supplier) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = MatrixFeatures.getInstance().attemptDelta(this, target);
		if (delta != null) {
			return delta;
		}

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);
		CollectionProducer<T> input = (CollectionProducer) getInputs().get(1);

		CollectionProducer<T> d = input.delta(target);
		d = d.reshape(getShape().getTotalSize(), -1).traverse(0);

		CollectionProducer<T> scale = isIgnoreZero() ? expIgnoreZero((Supplier) input) : exp((Supplier) input);
		scale = scale.flatten();

		return expandAndMultiply(scale, d).reshape(shape);
	}
}
