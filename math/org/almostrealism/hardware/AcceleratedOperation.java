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
import io.almostrealism.code.OperationAdapter;
import org.almostrealism.relation.Computation;
import org.almostrealism.util.Compactable;
import org.almostrealism.util.Named;
import org.almostrealism.util.Producer;
import org.almostrealism.util.ProducerArgumentReference;
import org.almostrealism.util.ProducerCache;
import org.jocl.CLException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class AcceleratedOperation extends OperationAdapter implements Function<Object[], Object[]>, Runnable,
														KernelizedOperation, Compactable, ComputerFeatures {
	private static Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private boolean kernel;

	private Class cls;

	public AcceleratedOperation(String function, boolean kernel, Producer<?>... args) {
		super(args);
		setFunctionName(function);
		this.kernel = kernel;
	}

	public AcceleratedOperation(String function, boolean kernel, Argument<?>... args) {
		super(args);
		setFunctionName(function);
		this.kernel = kernel;
	}

	public void setSourceClass(Class cls) { this.cls = cls; }

	public Class getSourceClass() { return cls == null ? getClass() : cls; }

	public HardwareOperator getOperator() {
		// TODO  This needs to be by class in addition to function, as function names may collide
		synchronized (AcceleratedProducer.class) {
			if (operators.get(getFunctionName()) == null) {
				operators.put(getFunctionName(), new ThreadLocal<>());
			}

			if (operators.get(getFunctionName()).get() == null) {
				operators.get(getFunctionName()).set(Hardware.getLocalHardware()
						.getFunctions().getOperators(getSourceClass()).get(getFunctionName(), getArgsCount()));
			}
		}

		return operators.get(getFunctionName()).get();
	}

	@Override
	public void run() { apply(new Object[0]); }

	@Override
	public synchronized Object[] apply(Object[] args) {
		HardwareOperator op = getOperator();

		Object allArgs[] = getAllArgs(args);

		for (int i = 0; i < allArgs.length; i++) {
			if (allArgs[i] == null) return new Object[] { handleNull(i) };
		}

		op.accept(allArgs);
		return allArgs;
	}

	protected Object[] getAllArgs(Object args[]) {
		Object allArgs[] = new Object[getArguments().size()];

		for (int i = 0; i < getArguments().size(); i++) {
			try {
				if (getArguments().get(i) == null) {
					allArgs[i] = replaceNull(i);
				} else if (getArguments().get(i).getProducer() == null) {
					throw new IllegalArgumentException("No Producer for " + getArguments().get(i).getName());
				} else {
					int argRef = getProducerArgumentReferenceIndex(getArguments().get(i));

					if (argRef >= args.length) {
						throw new IllegalArgumentException("Not enough arguments were supplied for evaluation");
					}

					if (argRef >= 0) {
						allArgs[i] = args[argRef];
					} else {
						allArgs[i] = ProducerCache.evaluate(getArguments().get(i).getProducer(), args);
					}
				}

				if (allArgs[i] == null) allArgs[i] = replaceNull(i);
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("Function \"" + getFunctionName() +
						"\" could not complete due to exception evaluating argument " + i +
						" (" + getArguments().get(i).getProducer().getClass() + ")", e);
			}
		}

		return allArgs;
	}

	private int getProducerArgumentReferenceIndex(Argument<?> arg) {
		if (arg.getProducer() instanceof AcceleratedComputationOperation == false) return -1;

		Computation c = ((AcceleratedComputationOperation) arg.getProducer()).getComputation();
		if (c instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) c).getReferencedArgumentIndex();
		}

		return -1;
	}

	@Override
	public void kernelOperate(MemoryBank[] args) {
		String name = this instanceof Named ? ((Named) this).getName() : OperationAdapter.operationName(getClass(), "function");

		try {
			if (isKernel() && enableKernel) {
				HardwareOperator operator = getOperator();
				operator.setGlobalWorkOffset(0);
				operator.setGlobalWorkSize(args[0].getCount());

				if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + name + " kernel...");
				MemoryBank input[] = getKernelArgs(args);

				if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + name + " kernel...");

				operator.accept(input);
			} else {
				throw new HardwareException("Kernel not supported", null);
			}
		} catch (CLException e) {
			throw new HardwareException("Could not evaluate AcceleratedOperation", e);
		}
	}

	protected MemoryBank[] getKernelArgs(MemoryBank args[]) {
		return getKernelArgs(getArguments(), args, 0);
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
		throw new NullPointerException("argument " + argIndex + " to function " + getFunctionName());
	}

	public boolean isKernel() { return kernel; }

	public boolean isInputKernel() {
		for (Argument arg : getArguments()) {
			if (arg.getProducer() instanceof AcceleratedProducer == false) return false;
			if (!((AcceleratedProducer) arg.getProducer()).isKernel()) return false;
		}

		return false;
	}

	protected static MemoryBank[] getKernelArgs(List<Argument> arguments, MemoryBank args[], int passThroughLength) {
		MemoryBank kernelArgs[] = new MemoryBank[arguments.size()];

		for (int i = 0; i < passThroughLength; i++) {
			kernelArgs[i] = args[i];
		}

		i: for (int i = passThroughLength; i < arguments.size(); i++) {
			if (arguments.get(i) == null) continue i;

			if (arguments.get(i).getProducer() instanceof ProducerArgumentReference) {
				int argIndex = ((ProducerArgumentReference) arguments.get(i).getProducer()).getReferencedArgumentIndex();
				kernelArgs[i] = args[passThroughLength + argIndex];
				continue i;
			}

			Optional<Computation> c = Hardware.getLocalHardware().getComputer().decompile(arguments.get(i).getProducer());

			if (c.orElse(null) instanceof ProducerArgumentReference) {
				int argIndex = ((ProducerArgumentReference) c.get()).getReferencedArgumentIndex();
				kernelArgs[i] = args[passThroughLength + argIndex];
			} else if (arguments.get(i).getProducer() instanceof KernelizedProducer) {
				MemoryBank downstreamArgs[] = new MemoryBank[args.length - passThroughLength];
				for (int j = passThroughLength; j < args.length; j++) {
					downstreamArgs[j - passThroughLength] = args[j];
				}

				KernelizedProducer kp = (KernelizedProducer) arguments.get(i).getProducer();
				kernelArgs[i] = kp.createKernelDestination(args[0].getCount());
				kp.kernelEvaluate(kernelArgs[i], downstreamArgs);
			} else {
				throw new IllegalArgumentException(arguments.get(i).getProducer().getClass().getSimpleName() +
						" is not a ProducerArgumentReference or KernelizedProducer");
			}
		}

		return kernelArgs;
	}
}
