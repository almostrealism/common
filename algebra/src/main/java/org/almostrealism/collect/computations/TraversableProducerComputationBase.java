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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public abstract class TraversableProducerComputationBase<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends CollectionProducerComputationBase<I, O>
		implements TraversableExpression<Double> {

	protected TraversableProducerComputationBase() { }

	public TraversableProducerComputationBase(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		super(outputShape, arguments);
	}

	@Override
	public Scope<O> getScope() {
		Scope<O> scope = super.getScope();

		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		for (int i = 0; i < getMemLength(); i++) {
			scope.getVariables().add(output.ref(i).assign(getValueRelative(e(i))));
		}

		return scope;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public RepeatedProducerComputationAdapter<O> toRepeated() {
		RepeatedProducerComputationAdapter result = new RepeatedProducerComputationAdapter<>(getShape(), this,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
		result.addDependentLifecycle(this);
		return result;
	}
}
