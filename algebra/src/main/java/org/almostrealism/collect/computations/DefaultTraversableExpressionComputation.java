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

import io.almostrealism.collect.ConditionalIndexExpression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class DefaultTraversableExpressionComputation<T extends PackedCollection<?>>
		extends TraversableExpressionComputation<T> {
	public static boolean enableChainRule = true;

	private Function<TraversableExpression[], CollectionExpression> expression;

	@SafeVarargs
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   Function<TraversableExpression[], CollectionExpression> expression,
												   Producer<? extends PackedCollection<?>>... args) {
		this(name, shape, MultiTermDeltaStrategy.NONE, expression, args);
	}

	@SafeVarargs
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   Function<TraversableExpression[], CollectionExpression> expression,
												   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(name, shape, MultiTermDeltaStrategy.NONE, expression, args);
	}

	@SafeVarargs
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   MultiTermDeltaStrategy deltaStrategy,
												   Function<TraversableExpression[], CollectionExpression> expression,
												   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(name, shape, deltaStrategy, validateArgs(args));
		this.expression = expression;
	}

	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   CollectionExpression expression) {
		this(name, shape, MultiTermDeltaStrategy.NONE, expression);
	}

	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   MultiTermDeltaStrategy deltaStrategy,
												   CollectionExpression expression) {
		super(name, shape, deltaStrategy);
		this.expression = (arguments) -> expression;
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return expression.apply(args);
	}

	@Override
	public boolean isChainRuleSupported() {
		return enableChainRule || super.isChainRuleSupported();
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (DefaultTraversableExpressionComputation<T>) new DefaultTraversableExpressionComputation(getName(), getShape(),
						getDeltaStrategy(), expression,
					children.stream().skip(1).toArray(Supplier[]::new))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	public static <T extends PackedCollection<?>> DefaultTraversableExpressionComputation<T> fixed(T value) {
		return fixed(value, null);
	}

	public static <T extends PackedCollection<?>> DefaultTraversableExpressionComputation<T> fixed(
			T value, BiFunction<MemoryData, Integer, T> postprocessor) {
		return (DefaultTraversableExpressionComputation<T>)
				new DefaultTraversableExpressionComputation<T>("constant", value.getShape(),
						args -> new ConditionalIndexExpression(value.getShape(), value))
						.setDescription(children -> value.describe())
						.setPostprocessor(postprocessor).setShortCircuit(args -> {
							PackedCollection v = new PackedCollection(value.getShape());
							v.setMem(value.toArray(0, value.getMemLength()));
							return postprocessor == null ? (T) v : postprocessor.apply(v, 0);
						});
	}
}
