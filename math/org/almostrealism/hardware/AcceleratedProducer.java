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

import io.almostrealism.code.ArrayVariable;
import org.almostrealism.util.CollectionUtils;
import org.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.almostrealism.util.Ops.*;

public class AcceleratedProducer<I extends MemWrapper, O extends MemWrapper> extends AcceleratedOperation implements KernelizedEvaluable<O> {
	public AcceleratedProducer(String function, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		this(function, false, result, inputArgs, new Object[0]);
	}

	public AcceleratedProducer(String function, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>> inputArgs[], Object additionalArguments[]) {
		this(function, false, result, inputArgs, additionalArguments);
	}

	public AcceleratedProducer(String function, boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>> inputArgs[], Object additionalArguments[]) {
		this(function, kernel, result, producers(inputArgs, additionalArguments));
	}

	public AcceleratedProducer(String function, boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		super(function, kernel, includeResult(result, inputArgs));
	}

	@Override
	public O evaluate(Object[] args) { return (O) apply(args)[0]; }

	/**
	 * If {@link #isKernel()} returns true, this method will pass the
	 * destination and the argument {@link MemoryBank}s to the
	 * {@link HardwareOperator}. Otherwise, {@link #evaluate(Object[])}
	 * will be called sequentially and the result will be added to the
	 * destination.
	 */
	@Override
	public void kernelEvaluate(MemoryBank destination, MemoryBank args[]) {
		kernelEvaluate(this, destination, args, isKernel());
	}

	@Override
	protected MemoryBank[] getKernelArgs(MemoryBank args[]) {
		return getKernelArgs(getArguments(), args, 1);
	}

	@Override
	public MemoryBank<O> createKernelDestination(int size) {
		throw new RuntimeException("Not implemented");
	}

	public static void kernelEvaluate(KernelizedOperation operation, MemoryBank destination, MemoryBank args[], boolean kernel) {
		if (kernel && enableKernel) {
			operation.kernelOperate(includeResult(destination, args));
		} else if (operation instanceof Evaluable) {
			for (int i = 0; i < destination.getCount(); i++) {
				final int fi = i;
				destination.set(i,
						((Evaluable<MemWrapper>) operation).evaluate(Stream.of(args)
								.map(arg -> arg.get(fi))
								.collect(Collectors.toList()).toArray()));
			}
		} else {
			// This will produce an error, but thats the correct outcome
			operation.kernelOperate(includeResult(destination, args));
		}
	}

	public static Supplier[] includeResult(Supplier res, Supplier... p) {
		return CollectionUtils.include(new Supplier[0], res, p);
	}

	public static MemoryBank[] includeResult(MemoryBank res, MemoryBank... p) {
		return CollectionUtils.include(new MemoryBank[0], res, p);
	}

	public static <T> List<ArrayVariable<? extends T>> excludeResult(List<ArrayVariable<? extends T>> p) {
		List<ArrayVariable<? extends T>> r = new ArrayList<>();
		for (int i = 1; i < p.size(); i++) r.add(p.get(i));
		return r;
	}

	public static ArrayVariable[] excludeResult(ArrayVariable... p) {
		ArrayVariable q[] = new ArrayVariable[p.length - 1];
		for (int i = 1; i < p.length; i++) q[i - 1] = p[i];
		return q;
	}

	public static Supplier[] producers(Supplier inputs[], Object fixedValues[]) {
		Supplier p[] = new Supplier[inputs.length + fixedValues.length];

		for (int i = 0; i < inputs.length; i++) {
			p[i] = inputs[i];
		}

		for (int i = 0; i < fixedValues.length; i++) {
			Object o = fixedValues[i];
			p[inputs.length + i] = fixedValues == null ? null : ops().v(o);
		}

		return p;
	}
}
