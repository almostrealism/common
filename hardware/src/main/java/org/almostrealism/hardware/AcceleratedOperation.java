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

import io.almostrealism.code.Argument;
import io.almostrealism.code.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.code.Variable;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.hardware.mem.MemWrapperArgumentMap;
import org.jocl.CLException;

import java.util.ArrayList;
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

	private static final Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private final boolean kernel;
	private Class cls;

	protected List<ArgumentMap> argumentMaps;

	@SafeVarargs
	protected AcceleratedOperation(boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		super(args);
		this.kernel = kernel;
		this.argumentMaps = new ArrayList<>();
	}

	@SafeVarargs
	public AcceleratedOperation(String function, boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		this(kernel, args);
		setFunctionName(function);
	}

	@SafeVarargs
	protected AcceleratedOperation(boolean kernel, ArrayVariable<T>... args) {
		super(Arrays.stream(args).map(var -> new Argument(var, Expectation.EVALUATE_AHEAD)).toArray(Argument[]::new));
		this.kernel = kernel;
		this.argumentMaps = new ArrayList<>();
	}

	@SafeVarargs
	public AcceleratedOperation(String function, boolean kernel, ArrayVariable<T>... args) {
		this(kernel, args);
		setFunctionName(function);
	}

	public void setSourceClass(Class cls) { this.cls = cls; }

	public Class getSourceClass() {
		if (cls != null) return cls;
		return getClass();
	}

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

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	protected void prepareScope() {
		SupplierArgumentMap argumentMap = null;

		if (enableDestinationConsolidation) {
			argumentMap = new DestinationConsolidationArgumentMap<>();
		} else if (enableArgumentMapping) {
			argumentMap = new MemWrapperArgumentMap<>();
		}

		if (argumentMap != null) {
			prepareArguments(argumentMap);
			argumentMaps.add(argumentMap);
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
		if (getArgumentVariables() != null) return;

		if (getInputs() != null) {
			ScopeLifecycle.prepareScope(getInputs().stream(), manager);
			setArguments(getInputs().stream()
					.map(manager.argumentForInput(this))
					.map(var -> new Argument(var, Expectation.EVALUATE_AHEAD))
					.map(arg -> (Argument<? extends T>) arg)
					.collect(Collectors.toList()));
		}
	}

	@Override
	public void run() { apply(new Object[0]); }

	@Override
	public synchronized Object[] apply(Object[] args) {
		if (getArgumentVariables() == null) {
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
		List<Argument<? extends T>> arguments = getArguments();
		Object allArgs[] = new Object[arguments.size()];

		for (int i = 0; i < arguments.size(); i++) {
			Argument<? extends T> arg = arguments.get(i);

			try {
				if (arg == null) {
					allArgs[i] = replaceNull(i);
				} else if (arg.getVariable().getProducer() == null) {
					throw new IllegalArgumentException("No Producer for " + arg.getName());
				} else {
					int argRef = getProducerArgumentReferenceIndex(arg.getVariable());

					if (argRef >= args.length) {
						throw new IllegalArgumentException("Not enough arguments were supplied for evaluation");
					}

					if (argRef >= 0) {
						allArgs[i] = args[argRef];
					} else if (arg.getExpectation() == Expectation.EVALUATE_AHEAD) {
						allArgs[i] = ProducerCache.evaluate(arg.getVariable().getProducer(), args);
					} else if (arg.getVariable().getProducer() instanceof Delegated &&
							((Delegated) arg.getVariable().getProducer()).getDelegate() instanceof DestinationSupport) {
						DestinationSupport dest = (DestinationSupport) ((Delegated) arg.getVariable().getProducer()).getDelegate();
						allArgs[i] = dest.getDestination().get();
					} else if (arg.getVariable().getProducer() instanceof Delegated &&
							((Delegated) arg.getVariable().getProducer()).getDelegate() instanceof Provider) {
						Provider dest = (Provider) ((Delegated) arg.getVariable().getProducer()).getDelegate();
						allArgs[i] = dest.get();
					} else if (arg.getVariable().getProducer() instanceof ProducerComputation) {
						Variable var = ((ProducerComputation) arg.getVariable().getProducer()).getOutputVariable();
						allArgs[i] = ProducerCache.evaluate(var.getProducer(), args);
					} else if (arg.getVariable().getProducer().get() instanceof Provider) {
						allArgs[i] = ((Provider) arg.getVariable().getProducer().get()).get();
					} else {
						throw new IllegalArgumentException("Argument Expectation requires access to destination or output variable");
					}
				}

				if (allArgs[i] == null) allArgs[i] = replaceNull(i);
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("Function \"" + getFunctionName() +
						"\" could not complete due to exception evaluating argument " + i +
						" (" + arguments.get(i).getVariable().getProducer().getClass() + ")", e);
			}
		}

		return allArgs;
	}

	private int getProducerArgumentReferenceIndex(Variable<?> arg) {
		if (arg.getProducer() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) arg.getProducer()).getReferencedArgumentIndex();
		}

		if (!(arg.getProducer() instanceof AcceleratedComputationOperation)) return -1;

		Computation c = ((AcceleratedComputationOperation) arg.getProducer()).getComputation();
		if (c instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) c).getReferencedArgumentIndex();
		}

		return -1;
	}

	@Override
	public void kernelOperate(MemoryBank[] args) {
		if (getArgumentVariables() == null) {
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
		return getKernelArgs(getArgumentVariables(), args, 0);
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
		for (ArrayVariable arg : getArgumentVariables()) {
			if (!(arg.getProducer() instanceof AcceleratedEvaluable)) return false;
			if (!((AcceleratedEvaluable) arg.getProducer()).isKernel()) return false;
		}

		return false;
	}

	@Override
	public void destroy() {
		argumentMaps.forEach(ArgumentMap::destroy);
		argumentMaps = new ArrayList<>();
	}

	protected static <T> MemWrapper[] getKernelArgs(List<ArrayVariable<? extends T>> arguments, MemoryBank args[], int passThroughLength) {
		MemWrapper kernelArgs[] = new MemWrapper[arguments.size()];

		if (passThroughLength >= 0)
			System.arraycopy(args, 0, kernelArgs, 0, passThroughLength);

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
				if (args.length - passThroughLength >= 0)
					System.arraycopy(args, passThroughLength, downstreamArgs, 0, args.length - passThroughLength);

				KernelizedEvaluable kp = (KernelizedEvaluable) c;
				kernelArgs[i] = kp.createKernelDestination(args[0].getCount());
				kp.kernelEvaluate((MemoryBank) kernelArgs[i], downstreamArgs);
			} else {
				MemoryBank downstreamArgs[] = new MemoryBank[args.length - passThroughLength];
				if (args.length - passThroughLength >= 0)
					System.arraycopy(args, passThroughLength, downstreamArgs, 0, args.length - passThroughLength);

				kernelArgs[i] = (MemWrapper) c.evaluate((Object[]) downstreamArgs);
			}
		}

		return kernelArgs;
	}
}
