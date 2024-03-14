/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.RelativeTraversableExpression;
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
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AggregatedProducerComputation<T extends PackedCollection<?>> extends TraversableRepeatedProducerComputation<T> {
	public static boolean enableTransitiveDelta = true;

	private BiFunction<Expression, Expression, Expression> expression;

	public AggregatedProducerComputation(TraversalPolicy shape, int count,
										 BiFunction<TraversableExpression[], Expression, Expression> initial,
										 BiFunction<Expression, Expression, Expression> expression,
										 Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(shape, count, initial, null, arguments);
		this.expression = expression;
		this.count = count;
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
	protected Expression<?> getExpression(Expression globalIndex, Expression localIndex) {
		TraversableExpression[] args = getTraversableArguments(globalIndex);
		Expression currentValue = ((CollectionVariable) ((RelativeTraversableExpression) args[0]).getExpression())
									.referenceRelative(new IntegerConstant(0));
		return expression.apply(currentValue, args[1].getValueRelative(localIndex));
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (enableTransitiveDelta && getInputs().size() == 2 && getInputs().get(1) instanceof CollectionProducer) {
			int outLength = ((CollectionProducer<T>) getInputs().get(1)).getShape().getTotalSize();
			int inLength = shape(target).getTotalSize();

			CollectionProducer<?> delta = ((CollectionProducer) getInputs().get(1)).delta(target);
			delta = delta.reshape(outLength, inLength);
			delta = delta.enumerate(1, 1);
			delta = delta.enumerate(1, count).traverse(2);
			return new AggregatedProducerComputation<>(shape(delta).replace(shape(1)),
						count, initial, expression, (Supplier) delta)
					.reshape(getShape().append(shape(target)));
		} else {
			CollectionProducer<T> delta = super.delta(target);
			if (delta instanceof ConstantRepeatedDeltaComputation) {
				TraversableDeltaComputation<T> traversable = TraversableDeltaComputation.create(getShape(), shape(target),
						args -> CollectionExpression.create(getShape(), this::getValueAt), target,
						getInputs().stream().skip(1).toArray(Supplier[]::new));
				traversable.addDependentLifecycle(this);
				((ConstantRepeatedDeltaComputation) delta).setFallback(traversable);
			}

			return delta;
		}
	}

	@Override
	public AggregatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new AggregatedProducerComputation<>(getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
