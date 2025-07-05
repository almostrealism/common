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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class ConstantRepeatedDeltaComputation<T extends PackedCollection<?>> extends ConstantRepeatedProducerComputation<T> implements TraversableExpression<Double> {
	private TraversalPolicy deltaShape, targetShape;
	private BiFunction<TraversableExpression[], Expression, Expression> expression;

	private Producer<?> target;
	private CollectionVariable<?> targetVariable;

	private Expression row;

	private TraversableExpression<Double> fallback;

	@SafeVarargs
	public ConstantRepeatedDeltaComputation(TraversalPolicy deltaShape, TraversalPolicy targetShape, int count,
											BiFunction<TraversableExpression[], Expression, Expression> expression,
											Producer<?> target,
											Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(deltaShape, targetShape, 1, count, expression, target, args);
	}

	@SafeVarargs
	public ConstantRepeatedDeltaComputation(TraversalPolicy deltaShape, TraversalPolicy targetShape, int size, int count,
											BiFunction<TraversableExpression[], Expression, Expression> expression,
											Producer<?> target,
											Supplier<Evaluable<? extends PackedCollection<?>>>... inputs) {
		super(null, deltaShape.append(targetShape), size, count, null, null, inputs);
		this.deltaShape	= deltaShape;
		this.targetShape = targetShape;
		this.expression = expression;
		this.target = target;
	}

	protected void setFallback(TraversableDeltaComputation<T> fallback) {
		addDependentLifecycle(fallback);
		this.fallback = fallback;
	}


	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		targetVariable = (CollectionVariable<?>) manager.argumentForInput(getNameProvider()).apply((Supplier) target);
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		targetVariable = null;
	}

	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		return expression.apply(args, row.add(localIndex))
				.delta(targetVariable)
				.getValueRelative(row.add(localIndex));
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return fallback == null ? null : fallback.getValueAt(index);
	}

	@Override
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset) {
		row = globalIndex.multiply(e(count));
		return super.getDestination(globalIndex, localIndex, row.add(localIndex));
	}

	@Override
	public ConstantRepeatedDeltaComputation<T> generate(List<Process<?, ?>> children) {
		return new ConstantRepeatedDeltaComputation<>(
				deltaShape, targetShape,
				getMemLength(), count,
				expression, target,
				children.stream().skip(1).toArray(Supplier[]::new));
	}

	public static <T extends PackedCollection<?>> ConstantRepeatedDeltaComputation<T> create(
			TraversalPolicy deltaShape, TraversalPolicy targetShape, int count,
			BiFunction<TraversableExpression[], Expression, Expression> expression,
			Producer<?> target,
			Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		return new ConstantRepeatedDeltaComputation<>(
				deltaShape, targetShape, count, expression,
				target, arguments);
	}
}
