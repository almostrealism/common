/*
 * Copyright 2021 Michael Murray
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Deprecated
public class AcceleratedEvaluable<I extends MemoryData, O extends MemoryData> extends AcceleratedOperation implements KernelizedEvaluable<O> {
	@SafeVarargs
	public AcceleratedEvaluable(String function, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		this(function, false, result, inputArgs, new Object[0]);
	}

	public AcceleratedEvaluable(String function, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>> inputArgs[], Object additionalArguments[]) {
		this(function, false, result, inputArgs, additionalArguments);
	}

	public AcceleratedEvaluable(String function, boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>> inputArgs[], Object additionalArguments[]) {
		this(function, kernel, result, producers(inputArgs, additionalArguments));
	}

	@SafeVarargs
	public AcceleratedEvaluable(String function, boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		super(function, kernel, includeResult(result, inputArgs));
		setArgumentMapping(false);
	}

	@Override
	public ArrayVariable getArgument(int index, Expression<Integer> size) {
		if (getArguments() != null) {
			return getArgumentForInput((Supplier<Evaluable>) getInputs().get(index));
		}

		return super.getArgument(index, size);
	}

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
	public O evaluate(Object... args) { return (O) apply(args)[0]; }

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
		throw new RuntimeException("Not implemented");
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

	public static Supplier[] producers(Supplier inputs[], Object fixedValues[]) {
		Supplier p[] = new Supplier[inputs.length + fixedValues.length];

		for (int i = 0; i < inputs.length; i++) {
			p[i] = inputs[i];
		}

		for (int i = 0; i < fixedValues.length; i++) {
			Object o = fixedValues[i];
			p[inputs.length + i] = fixedValues == null ? null : () -> new Provider<>(o);
		}

		return p;
	}
}
