/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.relation.Computation;

public class AcceleratedComputationProducer<T extends MemWrapper> extends AcceleratedComputationOperation implements KernelizedEvaluable<T> {
	public AcceleratedComputationProducer(Computation<T> c) {
		this(c, true);
	}

	public AcceleratedComputationProducer(Computation<T> c, boolean kernel) {
		super(c, kernel);
	}

	@Override
	public T evaluate(Object... args) { return (T) apply(args)[0]; }

	/**
	 * If {@link #isKernel()} returns true, this method will pass the
	 * destination and the argument {@link MemoryBank}s to the
	 * {@link HardwareOperator}. Otherwise, {@link #evaluate(Object[])}
	 * will be called sequentially and the result will be added to the
	 * destination.
	 */
	@Override
	public void kernelEvaluate(MemoryBank destination, MemoryBank args[]) {
		AcceleratedProducer.kernelEvaluate(this, destination, args, isKernel());
	}

	@Override
	protected MemoryBank[] getKernelArgs(MemoryBank args[]) {
		return getKernelArgs(getArguments(), args, 1);
	}

	@Override
	public MemoryBank<T> createKernelDestination(int size) {
		throw new RuntimeException("Not implemented");
	}
}
