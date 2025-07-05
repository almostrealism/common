/*
 * Copyright 2022 Michael Murray
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
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.instructions.DefaultExecutionKey;
import org.almostrealism.hardware.instructions.InstructionSetManager;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

@Deprecated
public abstract class DynamicAcceleratedEvaluable<I extends MemoryData, O extends MemoryData>
		extends AcceleratedOperation<MemoryData>
		implements Evaluable<O> {

	public DynamicAcceleratedEvaluable(ComputeContext<MemoryData> context,
									   Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier... inputArgs) {
		this(context, true, destination, kernelDestination, inputArgs);
	}

	@SafeVarargs
	public DynamicAcceleratedEvaluable(ComputeContext<MemoryData> context, boolean kernel,
									   Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier<Evaluable<? extends I>>... inputArgs) {
		super(context, kernel, new Supplier[0]);
		setInputs(AcceleratedEvaluable.includeResult(
				new DynamicProducerForMemoryData(args -> destination.get(), kernelDestination), inputArgs));
		init();
	}

	@Override
	public InstructionSetManager<DefaultExecutionKey> getInstructionSetManager() {
		throw new UnsupportedOperationException();
	}

	@Override
	public DefaultExecutionKey getExecutionKey() {
		return new DefaultExecutionKey(getFunctionName(), getArgsCount());
	}

	@Override
	public O evaluate(Object... args) {
		AcceleratedProcessDetails process = apply(null, args);
		waitFor(process.getSemaphore());
		return (O) process.getOriginalArguments()[0];
	}

	@Override
	public Variable getOutputVariable() { return getArgument(0); }
}
