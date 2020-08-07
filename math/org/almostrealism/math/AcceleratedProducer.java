/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.math;

import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.util.CollectionUtils;
import org.almostrealism.util.Producer;
import org.almostrealism.util.ProducerCache;
import org.almostrealism.util.ProducerWithRank;
import org.almostrealism.util.StaticProducer;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AcceleratedProducer<T extends MemWrapper> implements KernelizedProducer<T> {
	private static Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private String function;
	private boolean  kernel;

	protected Argument inputProducers[];

	public AcceleratedProducer(String function, Producer<?>... inputArgs) {
		this(function, false, inputArgs, new Object[0]);
	}

	public AcceleratedProducer(String function, Producer<?> inputArgs[], Object additionalArguments[]) {
		this(function, false, inputArgs, additionalArguments);
	}

	public AcceleratedProducer(String function, boolean kernel, Producer<?> inputArgs[], Object additionalArguments[]) {
		this(function, kernel, producers(inputArgs, additionalArguments));
	}

	public AcceleratedProducer(String function, boolean kernel, Producer<?> inputArgs[]) {
		this.function = function;
		this.kernel = kernel;
		this.inputProducers = arguments(inputArgs);
	}

	public void setFunctionName(String name) { function = name; }

	public String getFunctionName() { return function; }

	public int getArgsCount() { return inputProducers.length; }

	public Argument[] getInputProducers() { return inputProducers; }

	public synchronized HardwareOperator getOperator() {
		synchronized (AcceleratedProducer.class) {
			if (operators.get(function) == null) {
				operators.put(function, new ThreadLocal<>());
			}

			if (operators.get(function).get() == null) {
				operators.get(function).set(Hardware.getLocalHardware()
						.getFunctions().getOperators(getClass()).get(function, kernel, getArgsCount()));
			}
		}

		return operators.get(function).get();
	}

	@Override
	public synchronized T evaluate(Object[] args) {
		Object allArgs[] = getAllArgs(args);

		for (int i = 0; i < allArgs.length; i++) {
			if (allArgs[i] == null) return handleNull(i);
		}

		return (T) getOperator().evaluate(allArgs);
	}

	protected Object[] getAllArgs(Object args[]) {
		Object allArgs[] = new Object[inputProducers.length];

		for (int i = 0; i < inputProducers.length; i++) {
			try {
				allArgs[i] = inputProducers[i] == null ? replaceNull(i) : ProducerCache.evaluate(inputProducers[i].getProducer(), args);
				if (allArgs[i] == null) allArgs[i] = replaceNull(i);
			} catch (Exception e) {
				throw new RuntimeException("Function \"" + function +
						"\" could not complete due to exception evaluating argument " + i +
						" (" + inputProducers[i].getProducer().getClass() + ")", e);
			}
		}

		return allArgs;
	}

	/**
	 * If {@link #isKernel()} returns true, this method will pass the
	 * destination and the argument {@link MemoryBank}s to the
	 * {@link HardwareOperator}. Otherwise, {@link #evaluate(Object[])}
	 * will be called sequentially and the result will be added to the
	 * destination.
	 */
	@Override
	public void kernelEvaluate(MemoryBank destination, MemoryBank args[], int offset, int length) {
		if (kernel) {
			Object allArgs[] = CollectionUtils.include(new Object[0], destination, args);

			HardwareOperator operator = getOperator();
			operator.setGlobalWorkOffset(offset);
			operator.setGlobalWorkSize(length);

			operator.evaluate(allArgs);
		} else {
			for (int i = 0; i < length; i++) {
				final int fi = i;
				destination.set(offset + i,
						evaluate(Stream.of(args)
								.map(arg -> arg.get(offset + fi))
								.collect(Collectors.toList()).toArray()));
			}
		}
	}

	/**
	 * Override this method to provide a value to use in place of null
	 * when a null parameter is encountered.
	 *
	 * @param argIndex  The index of the argument that is null.
	 * @return  null
	 */
	protected T replaceNull(int argIndex) {
		return null;
	}

	/**
	 * Override this method to provide a value to return from the function
	 * should one of the parameters be null. The default implementation
	 * throws a {@link NullPointerException}.
	 *
	 * @param argIndex  The index of the argument that is null.
	 */
	protected T handleNull(int argIndex) {
		throw new NullPointerException("argument " + argIndex + " to function " + function);
	}

	@Override
	public synchronized void compact() {
		for (int i = 0; i < inputProducers.length; i++) {
			if (inputProducers[i] != null && inputProducers[i].getProducer() != null)
				inputProducers[i].getProducer().compact();
		}
	}

	public boolean isKernel() { return kernel; }

	public boolean isInputKernel() {
		for (Argument arg : inputProducers) {
			if (arg.getProducer() instanceof AcceleratedProducer == false) return false;
			if (!((AcceleratedProducer) arg.getProducer()).isKernel()) return false;
		}

		return false;
	}

	protected static Producer[] includeResult(Producer res, Producer... p) {
		return CollectionUtils.include(new Producer[0], res, p);
	}

	protected static Argument[] excludeResult(Argument... p) {
		Argument q[] = new Argument[p.length - 1];
		for (int i = 1; i < p.length; i++) q[i - 1] = p[i];
		return q;
	}

	protected static Argument[] arguments(Producer... producers) {
		Argument args[] = new Argument[producers.length];
		for (int i = 0; i < args.length; i++) {
			args[i] = producers[i] == null ? null : new Argument(producers[i]);
		}

		return args;
	}

	protected static Producer[] producers(Producer inputs[], Object fixedValues[]) {
		Producer p[] = new Producer[inputs.length + fixedValues.length];

		for (int i = 0; i < inputs.length; i++) {
			p[i] = inputs[i];
		}

		for (int i = 0; i < fixedValues.length; i++) {
			p[inputs.length + i] = fixedValues == null ? null : StaticProducer.of(fixedValues[i]);
		}

		return p;
	}

	/**
	 * A parameter for the {@link AcceleratedProducer}. Note that this
	 * type will extract the internal {@link Producer} from instances
	 * of {@link ProducerWithRank} and the rank will not be available,
	 * and will extract the internal {@link Producer} from instances
	 * of {@link GeneratedColorProducer} and the generator will not be
	 * available.
	 */
	public static class Argument {
		private String name;
		private Producer producer;

		public Argument(Producer p) { setProducer(p); }
		public Argument(String name, Producer p) { setName(name); setProducer(p);}

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }

		public Producer getProducer() { return producer; }
		public void setProducer(Producer producer) {
			this.producer = producer;

			while (this.producer instanceof ProducerWithRank || this.producer instanceof GeneratedColorProducer) {
				if (this.producer instanceof ProducerWithRank) {
					this.producer = ((ProducerWithRank) this.producer).getProducer();
				}

				if (this.producer instanceof GeneratedColorProducer) {
					this.producer = ((GeneratedColorProducer) this.producer).getProducer();
				}
			}
		}
	}
}
