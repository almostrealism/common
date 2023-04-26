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
import io.almostrealism.code.CollectionUtils;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Variable;
import org.almostrealism.hardware.cl.HardwareOperator;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Deprecated
public class AcceleratedEvaluable<I extends MemoryData, O extends MemoryData> extends AcceleratedOperation implements KernelizedEvaluable<O> {
	private BiFunction<MemoryData, Integer, O> postprocessor;
	private IntFunction<MemoryBank<O>> kernelDestination;

	@SafeVarargs
	public AcceleratedEvaluable(String function, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		this(function, false, result, inputArgs);
	}

	@SafeVarargs
	public AcceleratedEvaluable(String function, boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		super(function, kernel, includeResult(result, inputArgs));
		setArgumentMapping(false);
	}

	public BiFunction<MemoryData, Integer, O> getPostprocessor() { return postprocessor; }
	public void setPostprocessor(BiFunction<MemoryData, Integer, O> postprocessor) { this.postprocessor = postprocessor; }

	public IntFunction<MemoryBank<O>> getKernelDestination() { return kernelDestination; }
	public void setKernelDestination(IntFunction<MemoryBank<O>> kernelDestination) { this.kernelDestination = kernelDestination; }

	@Override
	public Variable getOutputVariable() { return getArgument(0); }

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		// Result should always be first
		ArrayVariable arg = getArgumentForInput((Supplier) getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);
	}

	@Override
	public O evaluate(Object... args) { return postProcessOutput((MemoryData) apply(args)[0], 0); }

	/**
	 * As the result of an {@link AcceleratedEvaluable} is not guaranteed to be
	 * of the correct type of {@link MemoryData}, depending on what optimizations
	 * are used for evaluation, subclasses can override this method to ensure that the
	 * expected type is returned by the {@link #evaluate(Object...)} method.
	 */
	protected O postProcessOutput(MemoryData output, int offset) {
		return postprocessor == null ? (O) output : postprocessor.apply(output, offset);
	}

	/**
	 * If {@link #isKernel()} returns true, this method will pass the
	 * destination and the argument {@link MemoryBank}s to the
	 * {@link HardwareOperator}. Otherwise, {@link #evaluate(Object[])}
	 * will be called sequentially and the result will be added to the
	 * destination.
	 */
	@Override
	public void kernelEvaluate(MemoryBank destination, MemoryData... args) {
		kernelEvaluate(this, destination, args, isKernel());
	}

	@Override
	public MemoryBank<O> createKernelDestination(int size) {
		if (kernelDestination != null) {
			return kernelDestination.apply(size);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	public static void kernelEvaluate(KernelizedOperation operation, MemoryBank destination, MemoryData args[], boolean kernel) {
		if (kernel && enableKernel) {
			operation.kernelOperate(destination, args);
		} else if (operation instanceof Evaluable) {
			for (int i = 0; i < destination.getCount(); i++) {
				final int fi = i;
				destination.set(i,
						((Evaluable<MemoryData>) operation).evaluate(Stream.of(args)
								.map(arg -> ((MemoryBank) arg).get(fi)).toArray()));
			}
		} else {
			// This will produce an error, but that's the correct outcome
			operation.kernelOperate(destination, args);
		}
	}

	public static Supplier[] includeResult(Supplier res, Supplier... p) {
		return CollectionUtils.include(new Supplier[0], res, p);
	}
}
