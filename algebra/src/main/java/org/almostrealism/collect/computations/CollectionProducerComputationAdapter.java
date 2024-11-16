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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.function.Supplier;

public abstract class CollectionProducerComputationAdapter<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends CollectionProducerComputationBase<I, O>
		implements TraversableExpression<Double> {

	public static boolean enableContextualKernelIndex = true;

	public CollectionProducerComputationAdapter(String name, TraversalPolicy outputShape,
												Supplier<Evaluable<? extends I>>... arguments) {
		super(name, outputShape, arguments);
	}

	protected boolean isOutputRelative() {
		return true;
	}

	@Override
	public Scope<O> getScope(KernelStructureContext context) {
		Scope<O> scope = super.getScope(context);
		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		for (int i = 0; i < getMemLength(); i++) {
			KernelIndex kernelIndex = enableContextualKernelIndex ? new KernelIndex(context) : new KernelIndex();
			Expression index = kernelIndex;
			if (getMemLength() > 1) index = index.multiply(getMemLength()).add(i);

			if (isOutputRelative()) {
				scope.getStatements().add(output.referenceRelative(e(i), kernelIndex).assign(getValueAt(index)));
			} else {
				scope.getStatements().add(output.referenceAbsolute(kernelIndex).assign(getValueAt(index)));
			}
		}

		return scope;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public CollectionProducer<O> delta(Producer<?> target) {
		CollectionProducer<O> delta = attemptDelta(target);
		if (delta != null) return delta;

		delta = TraversableDeltaComputation.create(getShape(), shape(target),
				args -> CollectionExpression.create(getShape(), idx -> args[1].getValueAt(idx)), target,
				(Supplier) this);
		return delta;
	}

	@Override
	public RepeatedProducerComputationAdapter<O> toRepeated() {
		RepeatedProducerComputationAdapter result = new RepeatedProducerComputationAdapter<>(getShape(), this,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
		result.addDependentLifecycle(this);
		return result;
	}
}
