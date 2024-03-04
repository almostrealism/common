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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.DeltaFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class ConstantRepeatedDeltaComputation<T extends PackedCollection<?>> extends ConstantRepeatedProducerComputation<T> {
	@SafeVarargs
	public ConstantRepeatedDeltaComputation(TraversalPolicy shape, int count,
											BiFunction<TraversableExpression[], Expression, Expression> initial,
											BiFunction<TraversableExpression[], Expression, Expression> expression,
											Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, count, initial, expression, args);
	}

	@SafeVarargs
	public ConstantRepeatedDeltaComputation(TraversalPolicy shape, int size, int count,
											BiFunction<TraversableExpression[], Expression, Expression> initial,
											BiFunction<TraversableExpression[], Expression, Expression> expression,
											Supplier<Evaluable<? extends PackedCollection<?>>>... inputs) {
		super(shape, size, count, initial, expression, inputs);
	}

	@Override
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset) {
		Expression row = globalIndex.multiply(e(count));
		return super.getDestination(globalIndex, localIndex, row.add(localIndex));
	}

	@Override
	public ConstantRepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new ConstantRepeatedProducerComputation<>(
				getShape(), getMemLength(), count,
				initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}

	public static <T extends PackedCollection<?>> ConstantRepeatedDeltaComputation<T> create(
			TraversalPolicy deltaShape, TraversalPolicy targetShape, int count,
			BiFunction<TraversableExpression[], Expression, Expression> expression,
			Producer<?> target,
			Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		return new ConstantRepeatedDeltaComputation<>(deltaShape.append(targetShape), count,
				null,
				(args, idx) ->
						expression.apply(args, idx)
								.delta(targetShape, DeltaFeatures.matcher(target))
								.getValueRelative(idx),
				arguments);
	}
}
