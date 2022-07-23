/*
 * Copyright 2022 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.code.Computation;
import org.almostrealism.hardware.cl.HardwareOperator;

import java.util.function.Consumer;
import java.util.stream.Stream;

public class DefaultCollectionEvaluable<T extends PackedCollection> extends AcceleratedComputationEvaluable<T> implements CollectionEvaluable<T> {
	private TraversalPolicy shape;

	public DefaultCollectionEvaluable(TraversalPolicy shape, Computation<T> c) {
		super(c);
		this.shape = shape;
	}

	@Override
	public synchronized Object[] apply(Object[] args) {
		if (!isKernel() || !enableKernel) return super.apply(args);

		if (getArgumentVariables() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		MemoryData memArgs[] = Stream.of(args).toArray(MemoryData[]::new);

		Consumer<Object[]> operator = getOperator();
		((HardwareOperator) operator).setGlobalWorkOffset(0);
		((HardwareOperator) operator).setGlobalWorkSize(workSize(memArgs[0]));

		if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
		MemoryData input[] = getKernelArgs(null, memArgs);

		if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");

		operator.accept(input);
		return input;
	}

	private int workSize(MemoryData data) {
		if (data instanceof MemoryBank) {
			return ((MemoryBank<?>) data).getCount();
		} else {
			return 1;
		}
	}

	@Override
	protected T postProcessOutput(MemoryData output, int offset) {
		return (T) new PackedCollection(shape, 0, output, offset);
	}

	@Override
	public MemoryBank<T> createKernelDestination(int size) {
		return (MemoryBank) new PackedCollection(shape.prependDimension(size));
	}
}
