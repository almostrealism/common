/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.List;

public class KernelTraversalOperation<T extends MemoryData> extends ProducerComputationBase<T, T>
		implements MemoryDataComputation<T>, ExpressionFeatures {
	private List<Expression> expressions;
	private MemoryDataDestinationProducer destination;

	public KernelTraversalOperation() {
		this.expressions = new ArrayList<>();
		this.destination = new MemoryDataDestinationProducer<>(this, i -> new Bytes(expressions.size()));
		setInputs(destination);
		init();
	}

	protected List<Expression> getExpressions() { return expressions; }

	@Override
	public int getMemLength() { return expressions.size(); }

	@Override
	public long getCountLong() { return 1; }

	@Override
	public boolean isFixedCount() { return true; }

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Scope<T> scope = super.getScope(context);
		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		for (int i = 0; i < getMemLength(); i++) {
			scope.getVariables().add(output.reference(e(i)).assign(expressions.get(i)));
		}

		return scope;
	}

	@Override
	public Evaluable<T> get() {
		ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(this);
		AcceleratedComputationEvaluable<T> ev = new AcceleratedComputationEvaluable<>(ctx, this);
		ev.setDestinationFactory(destination.getDestinationFactory());
		ev.compile();
		return ev;
	}
}
