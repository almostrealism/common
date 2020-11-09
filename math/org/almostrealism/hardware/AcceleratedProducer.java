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

import io.almostrealism.code.Argument;
import org.almostrealism.util.CollectionUtils;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.almostrealism.util.Ops.*;

public class AcceleratedProducer<T extends MemWrapper> extends AcceleratedOperation implements KernelizedProducer<T> {

	public AcceleratedProducer(String function, Producer<T> result, Producer<?>... inputArgs) {
		this(function, false, result, inputArgs, new Object[0]);
	}

	public AcceleratedProducer(String function, Producer<T> result, Producer<?> inputArgs[], Object additionalArguments[]) {
		this(function, false, result, inputArgs, additionalArguments);
	}

	public AcceleratedProducer(String function, boolean kernel, Producer<T> result, Producer<?> inputArgs[], Object additionalArguments[]) {
		this(function, kernel, result, producers(inputArgs, additionalArguments));
	}

	public AcceleratedProducer(String function, boolean kernel, Producer<T> result, Producer<?>... inputArgs) {
		super(function, kernel, includeResult(result, inputArgs));
	}

	@Override
	public T evaluate(Object[] args) { return (T) apply(args)[0]; }

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
	public MemoryBank<T> createKernelDestination(int size) {
		throw new RuntimeException("Not implemented");
	}

	public static void kernelEvaluate(KernelizedOperation operation, MemoryBank destination, MemoryBank args[], boolean kernel) {
		if (kernel && enableKernel) {
			operation.kernelOperate(includeResult(destination, args));
		} else if (operation instanceof Producer) {
			for (int i = 0; i < destination.getCount(); i++) {
				final int fi = i;
				destination.set(i,
						((Producer<MemWrapper>) operation).evaluate(Stream.of(args)
								.map(arg -> arg.get(fi))
								.collect(Collectors.toList()).toArray()));
			}
		} else {
			// This will produce an error, but thats the correct outcome
			operation.kernelOperate(includeResult(destination, args));
		}
	}

	public static Producer[] includeResult(Producer res, Producer... p) {
		return CollectionUtils.include(new Producer[0], res, p);
	}

	public static MemoryBank[] includeResult(MemoryBank res, MemoryBank... p) {
		return CollectionUtils.include(new MemoryBank[0], res, p);
	}

	public static List<Argument> excludeResult(List<Argument> p) {
		List<Argument> r = new ArrayList<>();
		for (int i = 1; i < p.size(); i++) r.add(p.get(i));
		return r;
	}

	public static Argument[] excludeResult(Argument... p) {
		Argument q[] = new Argument[p.length - 1];
		for (int i = 1; i < p.length; i++) q[i - 1] = p[i];
		return q;
	}

	public static Producer[] producers(Producer inputs[], Object fixedValues[]) {
		Producer p[] = new Producer[inputs.length + fixedValues.length];

		for (int i = 0; i < inputs.length; i++) {
			p[i] = inputs[i];
		}

		for (int i = 0; i < fixedValues.length; i++) {
			p[inputs.length + i] = fixedValues == null ? null : ops().v(fixedValues[i]);
		}

		return p;
	}
}
