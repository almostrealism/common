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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.jocl.CLException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AcceleratedOperation<T extends MemWrapper> extends OperationAdapter<T> implements Function<Object[], Object[]>, Runnable,
														KernelizedOperation, Compactable, ScopeLifecycle, ComputerFeatures {
	public static final boolean enableDestinationConsolidation = true;
	public static final boolean enableArgumentMapping = true;
	public static final boolean enableCompaction = true;
	public static final boolean enableInputLogging = false;

	private static Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private boolean kernel;

	private Class cls;

	protected AcceleratedOperation(boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		super(args);
		this.kernel = kernel;
	}

	public AcceleratedOperation(String function, boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		this(kernel, args);
		setFunctionName(function);
	}

	protected AcceleratedOperation(boolean kernel, ArrayVariable<T>... args) {
		super(args);
		this.kernel = kernel;
	}

	public AcceleratedOperation(String function, boolean kernel, ArrayVariable<T>... args) {
		this(kernel, args);
		setFunctionName(function);
	}

	public void setSourceClass(Class cls) { this.cls = cls; }

	public Class getSourceClass() { return cls == null ? getClass() : cls; }

	public HardwareOperator getOperator() {
		// TODO  This needs to be by class in addition to function, as function names may collide
		synchronized (AcceleratedEvaluable.class) {
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
	public String getDefaultAnnotation() { return "__global"; }

	protected void prepareScope() {
		SupplierArgumentMap argumentMap = null;

		if (enableDestinationConsolidation) {
			argumentMap = new DestinationConsolidationArgumentMap<>();
		} else if (enableArgumentMapping) {
			argumentMap = new MemWrapperArgumentMap<>();
		}

		if (argumentMap != null) {
			prepareArguments(argumentMap);
		}

		prepareScope(argumentMap == null ?
				DefaultScopeInputManager.getInstance() : argumentMap.getScopeInputManager());
	}

	@Override
	public Scope<?> compile() {
		prepareScope();
		if (enableCompaction) compact();
		return null;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (getInputs() != null) ScopeLifecycle.prepareArguments(getInputs().stream(), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		if (getArguments() != null) return;

		if (getInputs() != null) {
			ScopeLifecycle.prepareScope(getInputs().stream(), manager);
			setArguments(getInputs().stream()
					.map(manager.argumentForInput(this)).collect(Collectors.toList()));
		}
	}

	@Override
	public void run() { apply(new Object[0]); }

	@Override
	public synchronized Object[] apply(Object[] args) {
		if (getArguments() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		HardwareOperator op = getOperator();

		Object allArgs[] = getAllArgs(args);

		for (int i = 0; i < allArgs.length; i++) {
			if (allArgs[i] == null) return new Object[] { handleNull(i) };
		}

		String before = null;
		if (enableInputLogging) before = Arrays.toString(allArgs);

		op.accept(allArgs);

		if (enableInputLogging) {
			System.out.println(getName() + ": " + before + " -> " + Arrays.toString(allArgs));
		}
		return allArgs;
	}

	protected Object[] getAllArgs(Object args[]) {
		List<ArrayVariable<? extends T>> arguments = getArguments();
		Object allArgs[] = new Object[arguments.size()];

		for (int i = 0; i < arguments.size(); i++) {
			try {
				if (arguments.get(i) == null) {
					allArgs[i] = replaceNull(i);
				} else if (arguments.get(i).getProducer() == null) {
					throw new IllegalArgumentException("No Producer for " + arguments.get(i).getName());
				} else {
					int argRef = getProducerArgumentReferenceIndex(arguments.get(i));

					if (argRef >= args.length) {
						throw new IllegalArgumentException("Not enough arguments were supplied for evaluation");
					}

					if (argRef >= 0) {
						allArgs[i] = args[argRef];
					} else {
						allArgs[i] = ProducerCache.evaluate(arguments.get(i).getProducer(), args);
					}
				}

				if (allArgs[i] == null) allArgs[i] = replaceNull(i);
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("Function \"" + getFunctionName() +
						"\" could not complete due to exception evaluating argument " + i +
						" (" + arguments.get(i).getProducer().getClass() + ")", e);
			}
		}

		return allArgs;
	}

	private int getProducerArgumentReferenceIndex(ArrayVariable<?> arg) {
		if (arg.getProducer() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) arg.getProducer()).getReferencedArgumentIndex();
		}

		if (arg.getProducer() instanceof AcceleratedComputationOperation == false) return -1;

		Computation c = ((AcceleratedComputationOperation) arg.getProducer()).getComputation();
		if (c instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) c).getReferencedArgumentIndex();
		}

		return -1;
	}

	@Override
	public void kernelOperate(MemoryBank[] args) {
		if (getArguments() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		try {
			if (isKernel() && enableKernel) {
				HardwareOperator operator = getOperator();
				operator.setGlobalWorkOffset(0);
				operator.setGlobalWorkSize(args[0].getCount());

				if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
				MemWrapper input[] = getKernelArgs(args);

				if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");

				operator.accept(input);
			} else {
				throw new HardwareException("Kernel not supported");
			}
		} catch (CLException e) {
			throw new HardwareException("Could not evaluate AcceleratedOperation", e);
		}
	}

	protected MemWrapper[] getKernelArgs(MemoryBank args[]) {
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
		for (ArrayVariable arg : getArguments()) {
			if (arg.getProducer() instanceof AcceleratedEvaluable == false) return false;
			if (!((AcceleratedEvaluable) arg.getProducer()).isKernel()) return false;
		}

		return false;
	}

	protected static <T> MemWrapper[] getKernelArgs(List<ArrayVariable<? extends T>> arguments, MemoryBank args[], int passThroughLength) {
		MemWrapper kernelArgs[] = new MemWrapper[arguments.size()];

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

			Evaluable<T> c = (Evaluable<T>) ProducerCache.getEvaluableForSupplier(arguments.get(i).getProducer());

			if (c instanceof ProducerArgumentReference) {
				int argIndex = ((ProducerArgumentReference) c).getReferencedArgumentIndex();
				kernelArgs[i] = args[passThroughLength + argIndex];
			} else if (c instanceof KernelizedEvaluable) {
				MemoryBank downstreamArgs[] = new MemoryBank[args.length - passThroughLength];
				for (int j = passThroughLength; j < args.length; j++) {
					downstreamArgs[j - passThroughLength] = args[j];
				}

				KernelizedEvaluable kp = (KernelizedEvaluable) c;
				kernelArgs[i] = kp.createKernelDestination(args[0].getCount());
				kp.kernelEvaluate((MemoryBank) kernelArgs[i], downstreamArgs);
			} else {
				MemoryBank downstreamArgs[] = new MemoryBank[args.length - passThroughLength];
				for (int j = passThroughLength; j < args.length; j++) {
					downstreamArgs[j - passThroughLength] = args[j];
				}

				kernelArgs[i] = (MemWrapper) c.evaluate(downstreamArgs);
			}
		}

		return kernelArgs;
	}
}
