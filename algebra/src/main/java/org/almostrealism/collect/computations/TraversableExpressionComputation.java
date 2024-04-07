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

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class TraversableExpressionComputation<T extends PackedCollection<?>>
		extends CollectionProducerComputationAdapter<T, T>
		implements ComputerFeatures {
	public static boolean enableChainRule = true;

	private Function<TraversableExpression[], CollectionExpression> expression;

	@SafeVarargs
	public TraversableExpressionComputation(TraversalPolicy shape,
										BiFunction<TraversableExpression[], Expression, Expression> expression,
										Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = vars -> CollectionExpression.create(shape, index -> expression.apply(vars, index));
	}

	@SafeVarargs
	public TraversableExpressionComputation(TraversalPolicy shape,
										Function<TraversableExpression[], CollectionExpression> expression,
										Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = expression;
	}

	protected CollectionExpression getExpression(Expression index) {
		return expression.apply(getTraversableArguments(index));
	}

	@Override
	public boolean isChainRuleSupported() {
		return enableChainRule || super.isChainRuleSupported();
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = attemptDelta(this, target);
		if (delta != null) return delta;

		delta = TraversableDeltaComputation.create(getShape(), shape(target),
				expression, target, getInputs().stream().skip(1).toArray(Supplier[]::new));
		return delta;
	}

	@Override
	public TraversableExpressionComputation<T> generate(List<Process<?, ?>> children) {
		return (TraversableExpressionComputation<T>) new TraversableExpressionComputation(getShape(), expression,
					children.stream().skip(1).toArray(Supplier[]::new))
				.setPostprocessor(getPostprocessor())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(index).getValueAt(index);
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(new IntegerConstant(0)).getValueRelative(index);
	}

	public static <T extends PackedCollection<?>> TraversableExpressionComputation<T> fixed(T value) {
		return fixed(value, null);
	}

	public static <T extends PackedCollection<?>> TraversableExpressionComputation<T> fixed(T value, BiFunction<MemoryData, Integer, T> postprocessor) {
		BiFunction<TraversableExpression[], Expression, Expression> comp = (args, index) -> {
			index = index.toInt().mod(new IntegerConstant(value.getShape().getTotalSize()), false);

			OptionalInt i = index.intValue();

			if (i.isPresent()) {
				return value.getValueAt(index);
			} else {
				Expression v = value.getValueAt(new IntegerConstant(0));

				for (int j = 1; j < value.getShape().getTotalSize(); j++) {
					v = Conditional.of(index.eq(new IntegerConstant(j)), value.getValueAt(new IntegerConstant(j)), v);
				}

				return v;
			}
		};

		return (TraversableExpressionComputation<T>) new TraversableExpressionComputation<T>(value.getShape(), comp).setPostprocessor(postprocessor).setShortCircuit(args -> {
			PackedCollection v = new PackedCollection(value.getShape());
			v.setMem(value.toArray(0, value.getMemLength()));
			return postprocessor == null ? (T) v : postprocessor.apply(v, 0);
		});
	}
}
