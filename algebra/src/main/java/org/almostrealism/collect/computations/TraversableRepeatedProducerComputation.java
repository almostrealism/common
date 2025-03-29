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

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.RelativeTraversableExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class TraversableRepeatedProducerComputation<T extends PackedCollection<?>>
		extends ConstantRepeatedProducerComputation<T> implements TraversableExpression<Double> {
	public static int isolationCountThreshold = 16; // Integer.MAX_VALUE;

	private BiFunction<TraversableExpression[], Expression, TraversableExpression<Double>> expression;

	@SafeVarargs
	public TraversableRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
												  BiFunction<TraversableExpression[], Expression, Expression> initial,
												  BiFunction<TraversableExpression[], Expression, TraversableExpression<Double>> expression,
												  Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(name, shape, count, initial, null, arguments);
		this.expression = expression;
		this.count = count;
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
			value = expression.apply(args, value).getValueAt(e(i));
			value = value.generate(value.flatten());
		}

		return value;
	}

	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		Expression currentValue = ((CollectionVariable) ((RelativeTraversableExpression) args[0]).getExpression()).referenceRelative(new IntegerConstant(0));
		return expression.apply(args, currentValue).getValueAt(localIndex);
	}

	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		if (getOutputSize() > MemoryProvider.MAX_RESERVATION) return false;
		return super.isIsolationTarget(context) || count > isolationCountThreshold;
	}

	@Override
	public TraversableRepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new TraversableRepeatedProducerComputation<>(getName(), getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
