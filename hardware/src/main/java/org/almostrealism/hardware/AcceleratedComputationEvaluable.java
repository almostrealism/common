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

package org.almostrealism.hardware;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.scope.Scope;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;

import java.util.function.IntFunction;

public class AcceleratedComputationEvaluable<T extends MemoryData> extends AcceleratedComputationOperation<T> implements KernelizedEvaluable<T> {
	private IntFunction<Multiple<T>> destinationFactory;

	public AcceleratedComputationEvaluable(ComputeContext<MemoryData> context, Computation<T> c) {
		super(context, c, true);
	}

	@Override
	public ProducerComputation<T> getComputation() {
		return (ProducerComputation<T>) super.getComputation();
	}

	@Override
	public boolean isConstant() { return getComputation().isConstant(); }

	public IntFunction<Multiple<T>> getDestinationFactory() {
		return destinationFactory;
	}

	public void setDestinationFactory(IntFunction<Multiple<T>> destinationFactory) {
		this.destinationFactory = destinationFactory;
	}

	@Override
	public Multiple<T> createDestination(int size) {
		if (getDestinationFactory() == null) {
			return KernelizedEvaluable.super.createDestination(size);
		}

		return getDestinationFactory().apply(size);
	}

	@Override
	public T evaluate(Object... args) {
		if (getArgumentVariables() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		ArrayVariable outputVariable = (ArrayVariable) getOutputVariable();

		// Capture the offset, but ultimately use the root delegate
		int offset = outputVariable.getOffset();
		outputVariable = (ArrayVariable) outputVariable.getRootDelegate();

		if (outputVariable == null) {
			throw new IllegalArgumentException("Cannot capture result, as there is no argument which serves as an output variable");
		}

		int outputArgIndex = getArgumentVariables().indexOf(outputVariable);

		if (outputArgIndex < 0) {
			throw new IllegalArgumentException("An output variable does not appear to be one of the arguments to the Evaluable");
		}

		try {
			AcceleratedProcessDetails process = apply(null, args);
			waitFor(process.getSemaphore());
			return postProcessOutput((MemoryData) process.getOriginalArguments()[outputArgIndex], offset);
		} catch (HardwareException e) {
			throw new HardwareException("Failed to evaluate " + getName(), e);
		}
	}

	/**
	 * As the result of an {@link AcceleratedComputationEvaluable} is not guaranteed to be
	 * of the correct type of {@link MemoryData}, depending on what optimizations
	 * are used during compilation, subclasses can override this method to ensure that the
	 * expected type is returned by the {@link #evaluate(Object...)} method.
	 */
	protected T postProcessOutput(MemoryData output, int offset) {
		return (T) output;
	}
}
