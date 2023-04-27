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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ProducerComputation;

public class AcceleratedComputationEvaluable<T extends MemoryData> extends AcceleratedComputationOperation implements KernelizedEvaluable<T> {
	public AcceleratedComputationEvaluable(Computation<T> c) {
		this(c, true);
	}

	public AcceleratedComputationEvaluable(Computation<T> c, boolean kernel) {
		super(c, kernel);
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

		return postProcessOutput((MemoryData) apply(null, args)[outputArgIndex], offset);
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

	@Override
	public ProducerComputation<T> getComputation() {
		return (ProducerComputation<T>) super.getComputation();
	}

	@Override
	public MemoryBank<T> createKernelDestination(int size) {
		throw new RuntimeException("Not implemented");
	}
}
