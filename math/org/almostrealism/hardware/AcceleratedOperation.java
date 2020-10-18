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
import org.almostrealism.util.ProducerArgumentReference;
import org.almostrealism.util.ProducerCache;
import org.almostrealism.util.StaticProducer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class AcceleratedOperation implements Function<Object[], Object[]>, Runnable {
	public static final boolean enableNullInputs = true;
	public static final boolean enableKernel = true;

	private static Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private String function;
	private boolean kernel;

	protected Argument inputProducers[];

	public AcceleratedOperation(String function, boolean kernel, Producer<?>... args) {
		this.function = function;
		this.kernel = kernel;
		this.inputProducers = arguments(args);
	}

	public void setFunctionName(String name) { function = name; }

	public String getFunctionName() { return function; }

	public int getArgsCount() { return inputProducers.length; }

	public Argument[] getInputProducers() { return inputProducers; }

	public HardwareOperator getOperator() {
		// TODO  This needs to be by class in addition to function, as function names may collide
		synchronized (AcceleratedProducer.class) {
			if (operators.get(function) == null) {
				operators.put(function, new ThreadLocal<>());
			}

			if (operators.get(function).get() == null) {
				operators.get(function).set(Hardware.getLocalHardware()
						.getFunctions().getOperators(getClass()).get(function, getArgsCount()));
			}
		}

		return operators.get(function).get();
	}

	protected String getNumberType() {
		return Hardware.getLocalHardware().isDoublePrecision() ? "double" : "float";
	}

	@Override
	public void run() { apply(new Object[0]); }

	@Override
	public synchronized Object[] apply(Object[] args) {
		Object allArgs[] = getAllArgs(args);

		for (int i = 0; i < allArgs.length; i++) {
			if (allArgs[i] == null) return new Object[] { handleNull(i) };
		}

		getOperator().accept(allArgs);
		return allArgs;
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

	protected MemoryBank[] getKernelArgs(MemoryBank destination, MemoryBank args[]) {
		MemoryBank kernelArgs[] = new MemoryBank[inputProducers.length];
		kernelArgs[0] = destination;

		i:
		for (int i = 1; i < inputProducers.length; i++) {
			if (inputProducers[i] == null) continue i;

			if (inputProducers[i].getProducer() instanceof ProducerArgumentReference) {
				int argIndex = ((ProducerArgumentReference) inputProducers[i].getProducer()).getReferencedArgumentIndex();
				kernelArgs[i] = args[argIndex];
			} else if (inputProducers[i].getProducer() instanceof KernelizedProducer) {
				KernelizedProducer kp = (KernelizedProducer) inputProducers[i].getProducer();
				kernelArgs[i] = kp.createKernelDestination(destination.getCount());
				kp.kernelEvaluate(kernelArgs[i], args);
			} else {
				throw new IllegalArgumentException(inputProducers[i].getProducer().getClass().getSimpleName() +
						" is not a ProducerArgumentReference or KernelizedProducer");
			}
		}

		return kernelArgs;
	}

	/**
	 * Override this method to provide a value to use in place of null
	 * when a null parameter is encountered.
	 *
	 * @param argIndex  The index of the argument that is null.
	 * @return  null
	 */
	protected Object replaceNull(int argIndex) {
		return null;
	}

	/**
	 * Override this method to provide a value to return from the function
	 * should one of the parameters be null. The default implementation
	 * throws a {@link NullPointerException}.
	 *
	 * @param argIndex  The index of the argument that is null.
	 */
	protected Object handleNull(int argIndex) {
		throw new NullPointerException("argument " + argIndex + " to function " + function);
	}

	protected String stringForDouble(double d) {
		return "(" + getNumberType() + ")" + Hardware.getLocalHardware().stringForDouble(d);
	}

	protected double doubleForString(String s) {
		s = s.trim();
		while (s.startsWith("(double)") || s.startsWith("(float)")) {
			if (s.startsWith("(double)")) {
				s = s.substring(8).trim();
			} else if (s.startsWith("(float)")) {
				s = s.substring(7).trim();
			}
		}

		return Double.parseDouble(s);
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
			if (!enableNullInputs && producers[i] == null) {
				throw new IllegalArgumentException("Null argument at index " + i);
			}

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
}
