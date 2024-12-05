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

import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class ConstantRepeatedProducerComputation<T extends PackedCollection<?>>
		extends RepeatedProducerComputation<T> {
	protected int count;

	@SafeVarargs
	public ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
											   BiFunction<TraversableExpression[], Expression, Expression> initial,
											   BiFunction<TraversableExpression[], Expression, Expression> expression,
											   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(name, shape, 1, count, initial, expression, args);
	}

	@SafeVarargs
	public ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int size, int count,
											   BiFunction<TraversableExpression[], Expression, Expression> initial,
											   BiFunction<TraversableExpression[], Expression, Expression> expression,
											   Supplier<Evaluable<? extends PackedCollection<?>>>... inputs) {
		super(name, shape, size, initial, (args, index) -> index.lessThan(new IntegerConstant(count)), expression, inputs);
		this.count = count;
	}

	@Override
	protected OptionalInt getIndexLimit() { return OptionalInt.of(count); }

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = attemptDelta(target);
		if (delta != null) return delta;

		return ConstantRepeatedDeltaComputation.create(
				getShape(), shape(target),
				count, (args, localIndex) -> getExpression(args, null, localIndex), target,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
	}

	@Override
	public ConstantRepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new ConstantRepeatedProducerComputation<>(
				getName(), getShape(), getMemLength(), count,
				initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
