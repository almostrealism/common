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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.function.Supplier;

public abstract class KernelProducerComputationAdapter<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends CollectionProducerComputationBase<I, O>
		implements TraversableExpression<Double> {

	public KernelProducerComputationAdapter(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		super(outputShape, arguments);
	}

	@Override
	public Scope<O> getScope() {
		Scope<O> scope = super.getScope();
		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		for (int i = 0; i < getMemLength(); i++) {
			Expression index = new KernelIndex();
			if (getMemLength() > 1) index = index.multiply(getMemLength()).add(i);

			scope.getVariables().add(output.ref(i).assign(getValueAt(index)));
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
