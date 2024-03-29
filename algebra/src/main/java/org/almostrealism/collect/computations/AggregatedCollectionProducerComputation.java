/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AggregatedCollectionProducerComputation<T extends PackedCollection<?>> extends RepeatedCollectionProducerComputation<T> implements TraversableExpression<Double> {
	private BiFunction<Expression, Expression, Expression> expression;
	private int count;

	public AggregatedCollectionProducerComputation(TraversalPolicy shape, int count,
												   BiFunction<TraversableExpression[], Expression, Expression> initial,
												   BiFunction<Expression, Expression, Expression> expression,
												   Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(shape, initial,
				(args, index) -> index.lessThan(new IntegerConstant(count)),
				null, arguments);
		this.expression = expression;
		this.count = count;

		setExpression((args, index) ->
				expression.apply(
						getCollectionArgumentVariable(0).referenceRelative(new IntegerConstant(0)),
						args[1].getValueRelative(index)));
	}

	@Override
	protected OptionalInt getIndexLimit() {
		return OptionalInt.of(count);
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversableExpression args[] = getTraversableArguments(index);

		Expression value = initial.apply(args, e(0));

		for (int i = 0; i < count; i++) {
			value = expression.apply(value, args[1].getValueRelative(e(i)));
			value = value.generate(value.flatten());
		}

		return value;
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		TraversableDeltaComputation<T> delta = TraversableDeltaComputation.create(getShape(), shape(target),
				args -> CollectionExpression.create(getShape(), this::getValueAt), target,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
		delta.addDependentLifecycle(this);
		return delta;
	}

	@Override
	public RepeatedCollectionProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new AggregatedCollectionProducerComputation<>(getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
