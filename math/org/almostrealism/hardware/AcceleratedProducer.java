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

import org.almostrealism.util.Producer;
import org.almostrealism.util.ProducerArgumentReference;

import java.util.stream.Collectors;
import java.util.stream.Stream;

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
		String name = getClass().getSimpleName();
		if (name == null || name.trim().length() <= 0) name = "anonymous";
		if (name.equals("AcceleratedProducer")) name = getFunctionName();

		if (isKernel() && enableKernel) {
			HardwareOperator operator = getOperator();
			operator.setGlobalWorkOffset(0);
			operator.setGlobalWorkSize(destination.getCount());

			System.out.println("AcceleratedProducer: Preparing " + name + " kernel...");
			MemoryBank input[] = getKernelArgs(destination, args);

			System.out.println("AcceleratedProducer: Evaluating " + name + " kernel...");
			operator.accept(input);
		} else {
			for (int i = 0; i < destination.getCount(); i++) {
				final int fi = i;
				destination.set(i,
						evaluate(Stream.of(args)
								.map(arg -> arg.get(fi))
								.collect(Collectors.toList()).toArray()));
			}
		}
	}

	protected MemoryBank[] getKernelArgs(MemoryBank destination, MemoryBank args[]) {
		MemoryBank kernelArgs[] = new MemoryBank[inputProducers.length];
		kernelArgs[0] = destination;

		i: for (int i = 1; i < inputProducers.length; i++) {
			if (inputProducers[i] == null) continue i;

			if (inputProducers[i].getProducer() instanceof ProducerArgumentReference) {
				int argIndex = ((ProducerArgumentReference) inputProducers[i].getProducer()).getReferencedArgumentIndex();
				kernelArgs[i] = args[argIndex];
			} else if (inputProducers[i].getProducer() instanceof KernelizedProducer) {
				KernelizedProducer kp = (KernelizedProducer)  inputProducers[i].getProducer();
				kernelArgs[i] = kp.createKernelDestination(destination.getCount());
				kp.kernelEvaluate(kernelArgs[i], args);
			} else {
				throw new IllegalArgumentException(inputProducers[i].getProducer().getClass().getSimpleName() +
						" is not a ProducerArgumentReference or KernelizedProducer");
			}
		}

		return kernelArgs;
	}

	@Override
	public MemoryBank<T> createKernelDestination(int size) {
		throw new RuntimeException("Not implemented");
	}

	@Override
	public synchronized void compact() {
		for (int i = 0; i < inputProducers.length; i++) {
			if (inputProducers[i] != null && inputProducers[i].getProducer() != null)
				inputProducers[i].getProducer().compact();
		}
	}
}
