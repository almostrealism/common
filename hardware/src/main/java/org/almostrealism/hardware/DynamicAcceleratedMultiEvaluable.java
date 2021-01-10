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
import io.almostrealism.code.MultiExpression;
import io.almostrealism.code.Variable;
import io.almostrealism.relation.Evaluable;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

@Deprecated
public abstract class DynamicAcceleratedMultiEvaluable<I extends MemWrapper, O extends MemWrapper>
		extends DynamicAcceleratedEvaluable<I, O>
		implements KernelizedEvaluable<O>, MultiExpression<Double> {
	private int memLength;

	public DynamicAcceleratedMultiEvaluable(int memLength, Supplier<O> destination, Supplier<Evaluable<? extends I>> inputArgs[], Object additionalArguments[]) {
		this(memLength, destination, AcceleratedEvaluable.producers(inputArgs, additionalArguments));
	}

	public DynamicAcceleratedMultiEvaluable(int memLength, Supplier<O> destination, Supplier<Evaluable<? extends I>>... inputArgs) {
		this(memLength, true, destination, inputArgs);
	}

	public DynamicAcceleratedMultiEvaluable(int memLength, boolean kernel, Supplier<O> destination,
											Supplier<Evaluable<?>> inputArgs[], Object additionalArguments[]) {
		this(memLength, kernel, destination, AcceleratedEvaluable.producers(inputArgs, additionalArguments));
	}

	public DynamicAcceleratedMultiEvaluable(int memLength, boolean kernel, Supplier<O> destination, Supplier<Evaluable<? extends I>>... inputArgs) {
		super(kernel, destination, inputArgs);
		this.memLength = memLength;
	}

	public int getMemLength() { return memLength; }

	public String getBody(Variable<MemWrapper> outputVariable) {
		StringBuffer buf = new StringBuffer();
		// TODO  Variables?
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(this, outputVariable))
				.map(v -> OpenCLPrintWriter.renderAssignment((Variable) v))
				.map(s -> s + "\n")
				.forEach(buf::append);
		return buf.toString();
	}
}
