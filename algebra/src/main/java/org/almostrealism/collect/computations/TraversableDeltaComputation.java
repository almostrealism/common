/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import io.almostrealism.relation.Evaluable;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class TraversableDeltaComputation<T extends PackedCollection<?>>
		extends KernelProducerComputationAdapter<T, T>
		implements ComputerFeatures {
	private Function<TraversableExpression[], CollectionExpression> expression;

	@SafeVarargs
	public TraversableDeltaComputation(TraversalPolicy shape,
											Function<TraversableExpression[], CollectionExpression> expression,
											Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = expression;
	}

	protected CollectionExpression getExpression(Expression index) {
		return expression.apply(getTraversableArguments(index));
	}

	@Override
	public TraversableExpressionComputation<T> generate(List<Process<?, ?>> children) {
		return (TraversableExpressionComputation<T>) new TraversableDeltaComputation(getShape(), expression,
				children.stream().skip(1).toArray(Supplier[]::new))
				.setPostprocessor(getPostprocessor()).setShortCircuit(getShortCircuit());
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

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		throw new UnsupportedOperationException();
	}

	private static Supplier[] validateArgs(Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		Stream.of(args).forEach(Objects::requireNonNull);
		return args;
	}

	public static <T extends PackedCollection<?>> TraversableDeltaComputation<T> create(
																TraversalPolicy shape,
														  	 	Function<TraversableExpression[], CollectionExpression> expression,
															  	Producer<?> target,
														  		Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		return new TraversableDeltaComputation<>(shape, exp -> expression.apply(exp).delta(matcher(target)), args);
	}

	private static Predicate<Expression> matcher(Producer<?> p) {
		return exp -> {
			if (exp instanceof InstanceReference) {
				return ((InstanceReference) exp).getReferent().getProducer() == p;
			} else {
				return false;
			}
		};
	}
}
