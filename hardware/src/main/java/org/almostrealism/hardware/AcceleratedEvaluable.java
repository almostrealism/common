/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.CollectionUtils;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.hardware.cl.CLInstructionsManager;
import org.almostrealism.hardware.instructions.DefaultExecutionKey;
import org.almostrealism.hardware.instructions.InstructionSetManager;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;

import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;

@Deprecated
public class AcceleratedEvaluable<I extends MemoryData, O extends MemoryData> extends AcceleratedOperation implements Evaluable<O> {
	private InstructionSetManager<DefaultExecutionKey> instructions;
	private BiFunction<MemoryData, Integer, O> postprocessor;
	private IntFunction<MemoryBank<O>> kernelDestination;

	@SafeVarargs
	public AcceleratedEvaluable(String function, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		this((CLComputeContext) Hardware.getLocalHardware().getComputeContext(ComputeRequirement.CL), function, result, inputArgs);
	}

	@SafeVarargs
	public AcceleratedEvaluable(CLComputeContext context, String function, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		this(context, function, false, result, inputArgs);
	}

	@SafeVarargs
	public AcceleratedEvaluable(String function, boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		this((CLComputeContext) Hardware.getLocalHardware().getComputeContext(ComputeRequirement.CL), function, kernel, result, inputArgs);
	}

	@SafeVarargs
	public AcceleratedEvaluable(CLComputeContext context, String function, boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		super(context, function, kernel, CollectionUtils.include(new Supplier[0], result, inputArgs));
		setArgumentMapping(false);
	}

	public BiFunction<MemoryData, Integer, O> getPostprocessor() { return postprocessor; }
	public void setPostprocessor(BiFunction<MemoryData, Integer, O> postprocessor) { this.postprocessor = postprocessor; }

	public IntFunction<MemoryBank<O>> getKernelDestination() { return kernelDestination; }
	public void setKernelDestination(IntFunction<MemoryBank<O>> kernelDestination) { this.kernelDestination = kernelDestination; }

	@Override
	protected int getOutputArgumentIndex() {
		return 0;
	}

	@Override
	public InstructionSetManager<DefaultExecutionKey> getInstructionSetManager() {
		return instructions;
	}

	@Override
	public DefaultExecutionKey getExecutionKey() {
		return new DefaultExecutionKey(getFunctionName(), getArgsCount());
	}

	@Override
	public Scope<?> compile() {
		instructions = new CLInstructionsManager(getComputeContext(), getSourceClass());
		return super.compile();
	}

	@Override
	public Evaluable<O> into(Object destination) {
		return new DestinationEvaluable(this, (MemoryBank) destination);
	}

	@Override
	public O evaluate(Object... args) {
		AcceleratedProcessDetails process = apply(null, args);
		waitFor(process.getSemaphore());
		return postProcessOutput((MemoryData) process.getOriginalArguments()[0], 0);
	}

	/**
	 * As the result of an {@link AcceleratedEvaluable} is not guaranteed to be
	 * of the correct type of {@link MemoryData}, depending on what optimizations
	 * are used for evaluation, subclasses can override this method to ensure that the
	 * expected type is returned by the {@link #evaluate(Object...)} method.
	 */
	protected O postProcessOutput(MemoryData output, int offset) {
		return postprocessor == null ? (O) output : postprocessor.apply(output, offset);
	}

	@Override
	public Multiple<O> createDestination(int size) {
		if (kernelDestination != null) {
			return kernelDestination.apply(size);
		} else {
			throw new UnsupportedOperationException();
		}
	}
}
