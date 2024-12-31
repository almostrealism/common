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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Exponent;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class CollectionExponentComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	public static boolean enableCustomDelta = false;

	public CollectionExponentComputation(TraversalPolicy shape,
										 Producer<? extends PackedCollection<?>> base,
										 Producer<? extends PackedCollection<?>> exponent) {
		this("pow", shape, (Supplier) base, (Supplier) exponent);
	}

	public CollectionExponentComputation(TraversalPolicy shape,
										 Supplier<Evaluable<? extends PackedCollection<?>>> base,
										 Supplier<Evaluable<? extends PackedCollection<?>>> exponent) {
		this("pow", shape, base, exponent);
	}

	protected CollectionExponentComputation(String name, TraversalPolicy shape,
											Supplier<Evaluable<? extends PackedCollection<?>>> base,
											Supplier<Evaluable<? extends PackedCollection<?>>> exponent) {
		super(name, shape, MultiTermDeltaStrategy.NONE, base, exponent);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return CollectionExpression.create(getShape(),
				index -> Exponent.of(args[1].getValueAt(index), args[2].getValueAt(index)));
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
		if (!enableCustomDelta) {
			return super.delta(target);
		}

		Optional<Producer<T>> match = AlgebraFeatures.matchInput(this, target);

		if (match == null || match.orElse(null) != getInputs().get(1)) {
			// If there are multiple matches, or the match is
			// not the base, then there is not currently a
			// shortcut for computing the delta
			return super.delta(target);
		} else if (getChildren().stream().anyMatch(o -> !Countable.isFixedCount(o))) {
			warn("Exponent delta not implemented for variable count operands");
			return super.delta(target);
		}

		List<Supplier<Evaluable<? extends T>>> operands =
				getInputs().stream().skip(1).collect(Collectors.toList());

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);

		CollectionProducer<PackedCollection<?>> u = (CollectionProducer) operands.get(0);
		CollectionProducer<PackedCollection<?>> v = (CollectionProducer) operands.get(1);
		CollectionProducer<PackedCollection<?>> uDelta = u.delta(target);
		CollectionProducer<PackedCollection<?>> scale = v.multiply(u.pow(v.add(c(-1.0))));

		scale = scale.flatten();
		uDelta = uDelta.reshape(v.getShape().getTotalSize(), -1).traverse(0);
		return (CollectionProducer) expandAndMultiply(scale, uDelta).reshape(shape);
	}
}
