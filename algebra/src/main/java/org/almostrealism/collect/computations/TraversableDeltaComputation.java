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

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.DeltaFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import io.almostrealism.relation.Evaluable;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class TraversableDeltaComputation<T extends PackedCollection<?>>
		extends CollectionProducerComputationAdapter<T, T>
		implements ComputerFeatures {
	public static boolean enableOptimization = true;

	private Function<TraversableExpression[], CollectionExpression> expression;

	@SafeVarargs
	protected TraversableDeltaComputation(TraversalPolicy shape,
											Function<TraversableExpression[], CollectionExpression> expression,
											Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = expression;
	}

	protected CollectionExpression getExpression(Expression index) {
		return expression.apply(getTraversableArguments(index));
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> optimize(ProcessContext ctx) {
		if (!enableOptimization) return this;
		return super.optimize(ctx);
	}

	@Override
	public TraversableDeltaComputation<T> generate(List<Process<?, ?>> children) {
		TraversableDeltaComputation<T> result =
				(TraversableDeltaComputation<T>) new TraversableDeltaComputation(getShape(), expression,
					children.stream().skip(1).toArray(Supplier[]::new))
					.setPostprocessor(getPostprocessor()).setShortCircuit(getShortCircuit());
		getDependentLifecycles().forEach(result::addDependentLifecycle);
		return result;
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

	public static <T extends PackedCollection<?>> TraversableDeltaComputation<T> create(
																TraversalPolicy deltaShape, TraversalPolicy targetShape,
														  	 	Function<TraversableExpression[], CollectionExpression> expression,
															  	Producer<?> target,
														  		Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		return new TraversableDeltaComputation<>(deltaShape.append(targetShape),
				exp ->
						expression.apply(exp).delta(targetShape, DeltaFeatures.matcher(target),
								// TODO  This should be done in prepareScope so that the name related values are available
								(CollectionExpression) CollectionVariable.create(null, null, target)),
				args);
	}
}
