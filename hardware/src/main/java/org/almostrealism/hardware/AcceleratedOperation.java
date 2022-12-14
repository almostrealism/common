/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.code.KernelIndex;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.Traversable;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.jocl.CLException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class AcceleratedOperation<T extends MemoryData> extends OperationAdapter<T> implements Function<Object[], Object[]>, Runnable,
														KernelizedOperation, Compactable, ScopeLifecycle, ComputerFeatures {
	public static final boolean enableArgumentMapping = true;
	public static final boolean enableCompaction = true;
	public static final boolean enableInputLogging = false;

	private static final Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private final boolean kernel;
	private boolean argumentMapping;
	private Class cls;

	protected List<ArgumentMap> argumentMaps;

	@SafeVarargs
	protected AcceleratedOperation(boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		super(args);
		setArgumentMapping(true);
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
		setArgumentMapping(true);
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

	public Consumer<Object[]> getOperator() {
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

	protected void setArgumentMapping(boolean enabled) {
		this.argumentMapping = enabled;
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	protected void prepareScope() {
		SupplierArgumentMap argumentMap = null;

		if (argumentMapping) {
			if (Hardware.getLocalHardware().isDestinationConsolidation()) {
				argumentMap = new DestinationConsolidationArgumentMap<>(isKernel());
			} else if (enableArgumentMapping) {
				argumentMap = MemoryDataArgumentMap.create(isKernel());
			}
		}

		if (argumentMap != null) {
			prepareArguments(argumentMap);
			argumentMaps.add(argumentMap);
			argumentMap.confirmArguments();
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

		Consumer<Object[]> op = getOperator();

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

	private static int getProducerArgumentReferenceIndex(Variable<?, ?> arg) {
		if (arg.getProducer() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) arg.getProducer()).getReferencedArgumentIndex();
		}

		if (arg.getProducer() instanceof Delegated && ((Delegated) arg.getProducer()).getDelegate() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) ((Delegated) arg.getProducer()).getDelegate()).getReferencedArgumentIndex();
		}

		if (!(arg.getProducer() instanceof AcceleratedComputationOperation)) return -1;

		Computation c = ((AcceleratedComputationOperation) arg.getProducer()).getComputation();
		if (c instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) c).getReferencedArgumentIndex();
		}

		return -1;
	}

	private static int getProducerArgumentKernelIndex(Variable<?, ?> arg) {
		if (arg.getProducer() instanceof KernelIndex) {
			return ((KernelIndex) arg.getProducer()).getKernelIndex();
		}

		if (arg.getProducer() instanceof Delegated && ((Delegated) arg.getProducer()).getDelegate() instanceof KernelIndex) {
			return ((KernelIndex) ((Delegated) arg.getProducer()).getDelegate()).getKernelIndex();
		}

		if (!(arg.getProducer() instanceof AcceleratedComputationOperation)) return -1;

		Computation c = ((AcceleratedComputationOperation) arg.getProducer()).getComputation();
		if (c instanceof KernelIndex) {
			return ((KernelIndex) c).getKernelIndex();
		}

		return -1;
	}

	@Override
	public void kernelOperate(MemoryBank output, MemoryData[] args) {
		if (getArgumentVariables() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		try {
			if (isKernel() && enableKernel) {
				Consumer<Object[]> operator = getOperator();
				((HardwareOperator) operator).setGlobalWorkOffset(0);
				((HardwareOperator) operator).setGlobalWorkSize(Optional.ofNullable(output).map(MemoryBank::getCount).orElseGet(() -> ((MemoryBank) args[0]).getCount()));

				if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
				MemoryData input[] = getKernelArgs(output, args);

				if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");

				operator.accept(input);
			} else {
				throw new HardwareException("Kernel not supported");
			}
		} catch (CLException e) {
			throw new HardwareException("Could not evaluate AcceleratedOperation", e);
		}
	}

	@Override
	public void kernelOperate(MemoryData... args) {
		if (getArgumentVariables() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		try {
			if (isKernel() && enableKernel) {
				Consumer<Object[]> operator = getOperator();
				((HardwareOperator) operator).setGlobalWorkOffset(0);
				((HardwareOperator) operator).setGlobalWorkSize(((MemoryBank) args[0]).getCount());

				if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
				MemoryData input[] = getKernelArgs(null, args);

				if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");

				operator.accept(input);
			} else {
				throw new HardwareException("Kernel not supported");
			}
		} catch (CLException e) {
			throw new HardwareException("Could not evaluate AcceleratedOperation", e);
		}
	}

	protected MemoryData[] getKernelArgs(MemoryBank output, MemoryData args[]) {
		int kernelSize;

		if (output != null) {
			kernelSize = output.getCount();
		} else if (args.length > 0) {
			kernelSize = ((MemoryBank) args[0]).getCount();
		} else {
			System.out.println("WARN: Cannot determine kernel size, it will be inferred");
			kernelSize = -1;
		}

		if (kernelSize < 0) {
			return getKernelArgsInferSize(getArgumentVariables(), args,
					output == null ? Collections.emptyMap() : Collections.singletonMap((ArrayVariable) getOutputVariable(), output));
		} else {
			return getKernelArgs(getArgumentVariables(), args,
					output == null ? Collections.emptyMap() : Collections.singletonMap((ArrayVariable) getOutputVariable(), output),
					kernelSize);
		}
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

		return true;
	}

	@Override
	public void destroy() {
		argumentMaps.forEach(ArgumentMap::destroy);
		argumentMaps = new ArrayList<>();
	}

	protected static <T> MemoryData[] getKernelArgs(List<ArrayVariable<? extends T>> arguments, MemoryData args[], int kernelSize) {
		return getKernelArgs(arguments, args, new HashMap<>(), kernelSize);
	}

	protected static <T> MemoryData[] getKernelArgs(List<ArrayVariable<? extends T>> arguments, MemoryData args[], Map<ArrayVariable<? extends T>, MemoryBank> mappings, int kernelSize) {
		MemoryData kernelArgs[] = new MemoryData[arguments.size()];

		i: for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i) == null) continue i;

			if (mappings.containsKey(arguments.get(i))) {
				kernelArgs[i] = mappings.get(arguments.get(i));
				continue i;
			}

			int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));

			if (refIndex >= 0) {
				kernelArgs[i] = args[refIndex];
				continue i;
			}

			Evaluable<T> c = (Evaluable<T>) ProducerCache.getEvaluableForSupplier(arguments.get(i).getProducer());

			if (c instanceof ProducerArgumentReference) {
				int argIndex = ((ProducerArgumentReference) c).getReferencedArgumentIndex();
				kernelArgs[i] = args[argIndex];
			} else if (c instanceof KernelizedEvaluable) {
				KernelizedEvaluable kp = (KernelizedEvaluable) c;
				kernelArgs[i] = kp.createKernelDestination(kernelSize);
				kp.kernelEvaluate((MemoryBank) kernelArgs[i], args);

				// This assumes that the axis of the kernel is always the first dimension
				if (kernelArgs[i] instanceof Traversable) kernelArgs[i] = (MemoryData) ((Traversable) kernelArgs[i]).traverse(1);
			} else {
				kernelArgs[i] = (MemoryData) c.evaluate((Object[]) args);
			}
		}

		return kernelArgs;
	}


	protected static <T> MemoryData[] getKernelArgsInferSize(List<ArrayVariable<? extends T>> arguments, MemoryData args[], Map<ArrayVariable<? extends T>, MemoryBank> mappings) {
		MemoryData kernelArgs[] = new MemoryData[arguments.size()];

		int kernelSize = -1;

		i: for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i) == null) continue i;

			if (mappings.containsKey(arguments.get(i))) {
				kernelArgs[i] = mappings.get(arguments.get(i));
				continue i;
			} else {
				int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));

				if (refIndex >= 0) {
					kernelArgs[i] = args[refIndex];
				}
			}

			if (kernelSize > 0) continue i;

			// If the kernel size can be inferred from this operation argument
			// capture it from the argument to the evaluation
			int kernelIdx = getProducerArgumentKernelIndex(arguments.get(i));
			if (kernelIdx >= 0 && kernelArgs[i] instanceof MemoryBank) {
				kernelSize = ((MemoryBank<?>) kernelArgs[i]).getCount();
			}
		}

		if (kernelSize < 0) {
			System.out.println("WARN: Could not infer kernel size, it will be set to 1");
			kernelSize = 1;
		}

		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			Evaluable<T> c = (Evaluable<T>) ProducerCache.getEvaluableForSupplier(arguments.get(i).getProducer());

			if (c instanceof ProducerArgumentReference) {
				int argIndex = ((ProducerArgumentReference) c).getReferencedArgumentIndex();
				kernelArgs[i] = args[argIndex];
			} else if (c instanceof KernelizedEvaluable) {
				KernelizedEvaluable kp = (KernelizedEvaluable) c;
				kernelArgs[i] = kp.createKernelDestination(kernelSize);
				kp.kernelEvaluate((MemoryBank) kernelArgs[i], args);

				// This assumes that the axis of the kernel is always the first dimension
				if (kernelArgs[i] instanceof Traversable) kernelArgs[i] = (MemoryData) ((Traversable) kernelArgs[i]).traverse(1);
			} else {
				kernelArgs[i] = (MemoryData) c.evaluate((Object[]) args);
			}
		}

		return kernelArgs;
	}
}
