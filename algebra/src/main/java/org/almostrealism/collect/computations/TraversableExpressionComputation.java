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

import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public abstract class TraversableExpressionComputation<T extends PackedCollection<?>>
		extends CollectionProducerComputationAdapter<T, T>
		implements ComputerFeatures {
	public static boolean enableChainRule = true;

	private final MultiTermDeltaStrategy deltaStrategy;

	@SafeVarargs
	public TraversableExpressionComputation(String name, TraversalPolicy shape,
											Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(name, shape, MultiTermDeltaStrategy.NONE, args);
	}

	@SafeVarargs
	public TraversableExpressionComputation(String name, TraversalPolicy shape,
											MultiTermDeltaStrategy deltaStrategy,
											Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(name, shape, validateArgs(args));
		this.deltaStrategy = deltaStrategy;
	}

	protected abstract CollectionExpression getExpression(TraversableExpression... args);

	@Override
	public boolean isConstant() {
		return getInputs().stream().skip(1)
				.map(c -> c instanceof Computable && ((Computable) c).isConstant())
				.reduce(true, (a, b) -> a && b);
	}

	@Override
	public boolean isChainRuleSupported() {
		return enableChainRule || super.isChainRuleSupported();
	}

	@Override
	public MultiTermDeltaStrategy getDeltaStrategy() { return deltaStrategy; }

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = attemptDelta(target);
		if (delta != null) return delta;

		delta = TraversableDeltaComputation.create(
				"\u03B4" + getName(), getShape(), shape(target),
				this::getExpression, target,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
		return delta;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(getTraversableArguments(index)).getValueAt(index);
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(getTraversableArguments(new IntegerConstant(0))).getValueRelative(index);
	}

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return getExpression(getTraversableArguments(index)).containsIndex(index);
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return getExpression(getTraversableArguments(targetIndex))
				.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}
}
