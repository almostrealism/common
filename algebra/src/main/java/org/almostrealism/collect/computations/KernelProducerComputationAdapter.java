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
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ScopeInputManager;
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
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		// Result should always be first
		// TODO  This causes cascading issues, as the output variable is reused by the referring
		// TODO  producer and then multiple arguments are sorted to be "first"
		ArrayVariable arg = getArgumentForInput(getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);
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
}
