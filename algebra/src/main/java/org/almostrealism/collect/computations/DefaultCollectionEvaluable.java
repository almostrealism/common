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

import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.code.Computation;
import org.almostrealism.hardware.MemoryDataArgumentProcessor;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.hardware.mem.Bytes;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class DefaultCollectionEvaluable<T extends PackedCollection> extends AcceleratedComputationEvaluable<T> implements CollectionEvaluable<T> {
	private TraversalPolicy shape;
	private BiFunction<MemoryData, Integer, T> postprocessor;

	public DefaultCollectionEvaluable(TraversalPolicy shape, Computation<T> c, BiFunction<MemoryData, Integer, T> postprocessor) {
		super(c);
		this.shape = shape;
		this.postprocessor = postprocessor;
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

		return postProcessOutput((MemoryData) apply(outputArgIndex, args)[outputArgIndex], offset);
	}

	@Override
	public synchronized Object[] apply(Object[] args) {
		throw new UnsupportedOperationException();
	}

	public synchronized Object[] apply(int outputArgIndex, Object[] args) {
		if (!isKernel() || !enableKernel) return super.apply(args);

		if (getArgumentVariables() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		MemoryData memArgs[] = Stream.of(args).toArray(MemoryData[]::new);

		Consumer<Object[]> operator = getOperator();

		if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
		MemoryDataArgumentProcessor processor = processKernelArgs(null, memArgs);
		MemoryData input[] = Stream.of(processor.getArguments()).toArray(MemoryData[]::new);
		((HardwareOperator) operator).setGlobalWorkOffset(0);
		((HardwareOperator) operator).setGlobalWorkSize(workSize(input[outputArgIndex]));

		if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");

		runApply(operator, processor, input);
		return processor.getOriginalArguments();
	}

	private int workSize(MemoryData data) {
		if (data instanceof MemoryBank) {
			return ((MemoryBank<?>) data).getCount();
		} else if (data instanceof Bytes) {
			return ((Bytes) data).getCount();
		} else {
			return 1;
		}
	}

	@Override
	protected T postProcessOutput(MemoryData output, int offset) {
		if (postprocessor == null) {
			return (T) new PackedCollection(shape, 0, output, offset);
		} else {
			return postprocessor.apply(output, offset);
		}
	}

	@Override
	public MemoryBank<T> createKernelDestination(int size) {
		return (MemoryBank) new PackedCollection(shape.prependDimension(size));
	}

	@Override
	public boolean isAggregatedInput() { return true; }
}
