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

import io.almostrealism.c.OpenCLPrintWriter;
import io.almostrealism.code.MultiExpression;
import io.almostrealism.code.Variable;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public abstract class DynamicAcceleratedMultiProducer<T extends MemWrapper> extends DynamicAcceleratedProducer<T> implements KernelizedProducer<T>, MultiExpression<Double> {
	private int memLength;

	public DynamicAcceleratedMultiProducer(int memLength, Producer<T> result, Producer<?> inputArgs[], Object additionalArguments[]) {
		this(memLength, result, AcceleratedProducer.producers(inputArgs, additionalArguments));
	}

	public DynamicAcceleratedMultiProducer(int memLength, Producer<T> result, Producer<?>... inputArgs) {
		this(memLength, true, result, inputArgs);
	}

	public DynamicAcceleratedMultiProducer(int memLength, boolean kernel, Producer<T> result, Producer<?> inputArgs[], Object additionalArguments[]) {
		this(memLength, kernel, result, AcceleratedProducer.producers(inputArgs, additionalArguments));
	}

	public DynamicAcceleratedMultiProducer(int memLength, boolean kernel, Producer<T> result, Producer<?>... inputArgs) {
		super(kernel, result, inputArgs);
		this.memLength = memLength;
	}

	public int getMemLength() { return memLength; }

	public String getBody(Variable outputVariable, List<Variable> existingVariables) {
		StringBuffer buf = new StringBuffer();
		writeVariables(buf::append, existingVariables);
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(outputVariable))
				.map(v -> OpenCLPrintWriter.renderAssignment((Variable) v))
				.map(s -> s + "\n")
				.forEach(buf::append);
		return buf.toString();
	}
}
