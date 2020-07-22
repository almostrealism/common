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

import org.almostrealism.util.Producer;
import org.almostrealism.util.ProducerCache;
import org.almostrealism.util.StaticProducer;

import java.util.HashMap;
import java.util.Map;

public class AcceleratedProducer<T extends MemWrapper> implements Producer<T> {
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

		for (int i = 0; i < allArgs.length; i++) {
			if (allArgs[i] == null) return handleNull(i);
		}

		try {
			return (T) getOperator().evaluate(allArgs);
		} finally {
			for (int i = 0; i < inputProducers.length; i++) {
				allArgs[i] = null;
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

	public static class Argument {
		private String name;
		private Producer producer;

		public Argument(Producer p) { setProducer(p); }
		public Argument(String name, Producer p) { setName(name); setProducer(p);}

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }

		public Producer getProducer() { return producer; }
		public void setProducer(Producer producer) { this.producer = producer; }
	}
}
