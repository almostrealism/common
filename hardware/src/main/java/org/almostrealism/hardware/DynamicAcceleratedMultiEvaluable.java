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

import org.almostrealism.c.OpenCLPrintWriter;
import io.almostrealism.code.expressions.MultiExpression;
import io.almostrealism.code.Variable;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

@Deprecated
public abstract class DynamicAcceleratedMultiEvaluable<I extends MemWrapper, O extends MemWrapper>
		extends DynamicAcceleratedEvaluable<I, O>
		implements KernelizedEvaluable<O>, MultiExpression<Double> {
	private int memLength;

	public DynamicAcceleratedMultiEvaluable(int memLength, Supplier<O> destination,
											IntFunction<MemoryBank<O>> kernelDestination,
											Supplier<Evaluable<? extends I>> inputArgs[],
											Object additionalArguments[]) {
		this(memLength, destination, kernelDestination, AcceleratedEvaluable.producers(inputArgs, additionalArguments));
	}

	@SafeVarargs
	public DynamicAcceleratedMultiEvaluable(int memLength, Supplier<O> destination,
											IntFunction<MemoryBank<O>> kernelDestination,
											Supplier<Evaluable<? extends I>>... inputArgs) {
		this(memLength, true, destination, kernelDestination, inputArgs);
	}

	public DynamicAcceleratedMultiEvaluable(int memLength, boolean kernel, Supplier<O> destination,
											IntFunction<MemoryBank<O>> kernelDestination,
											Supplier<Evaluable<?>> inputArgs[], Object additionalArguments[]) {
		this(memLength, kernel, destination, kernelDestination, AcceleratedEvaluable.producers(inputArgs, additionalArguments));
	}

	@SafeVarargs
	public DynamicAcceleratedMultiEvaluable(int memLength, boolean kernel, Supplier<O> destination,
											IntFunction<MemoryBank<O>> kernelDestination,
											Supplier<Evaluable<? extends I>>... inputArgs) {
		super(kernel, destination, kernelDestination, inputArgs);
		this.memLength = memLength;
	}

	public int getMemLength() { return memLength; }

	@Override
	public String getBody(Variable<MemWrapper, ?> outputVariable) {
		StringBuilder buf = new StringBuilder();
		// TODO  Variables?
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(this, outputVariable))
				.map(v -> OpenCLPrintWriter.renderAssignment((Variable) v))
				.map(s -> s + "\n")
				.forEach(buf::append);
		return buf.toString();
	}
}
