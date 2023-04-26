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
import io.almostrealism.expression.Expression;
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
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.Traversable;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.mem.MemoryDataArgumentProcessor;
import org.almostrealism.hardware.mem.MemoryDataDestination;
import org.almostrealism.io.SystemUtils;
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
import java.util.stream.Stream;

public abstract class AcceleratedOperation<T extends MemoryData> extends OperationAdapter<T> implements Function<Object[], Object[]>, Runnable,
														KernelizedOperation, Compactable, ScopeLifecycle, ComputerFeatures {
	public static final boolean enableArgumentMapping = true;
	public static final boolean enableCompaction = true;
	public static boolean enableKernelSizeWarnings = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_SIZE_WARNINGS").orElse(true);

	private static final Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private final boolean kernel;
	private boolean argumentMapping;
	private Class cls;

	protected List<ArgumentMap> argumentMaps;
	private Supplier<Runnable> preOp;
	private Supplier<Runnable> postOp;

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
		synchronized (AcceleratedOperation.class) {
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

	@Override
	public ArrayVariable getArgument(int index, Expression<Integer> size) {
		return getInputs() == null ? getArgumentVariables().get(index) : getArgumentForInput(getInputs().get(index));
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	public boolean isAggregatedInput() { return false; }

	public MemoryData createAggregatedInput(int memLength, int atomicLength) {
		return Hardware.getLocalHardware().getClDataContext().deviceMemory(() -> new Bytes(memLength, atomicLength));
	}

	protected void prepareScope() {
		resetArguments();

		SupplierArgumentMap argumentMap = null;

		if (argumentMapping) {
			if (Hardware.getLocalHardware().isDestinationConsolidation()) {
				argumentMap = new DestinationConsolidationArgumentMap<>(isKernel());
			} else if (enableArgumentMapping) {
				if (preOp != null || postOp != null) {
					throw new UnsupportedOperationException("Redundant call to prepareScope");
				}

				argumentMap = MemoryDataArgumentMap.create(isAggregatedInput() ? i -> createAggregatedInput(i, i) : null, isKernel());
				preOp = ((MemoryDataArgumentMap) argumentMap).getPrepareData();
				postOp = ((MemoryDataArgumentMap) argumentMap).getPostprocessData();
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
	public boolean isCompiled() {
		return false;
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

	public void preApply() { if (preOp != null) preOp.get().run(); }
	public void postApply() { if (postOp != null) postOp.get().run(); }

	@Override
	public void run() { apply(new Object[0]); }

	@Override
	public synchronized Object[] apply(Object[] args) {
		if (getArgumentVariables() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		Consumer<Object[]> operator = getOperator();

		if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
		MemoryDataArgumentProcessor processor = processKernelArgs(null, args);
		MemoryData input[] = Stream.of(processor.getArguments()).toArray(MemoryData[]::new);
		((HardwareOperator) operator).setGlobalWorkOffset(0);
		((HardwareOperator) operator).setGlobalWorkSize(processor.getKernelSize());

		if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");

		runApply(operator, processor, input);
		return processor.getOriginalArguments();
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

	protected void runApply(Consumer<Object[]> operator, MemoryDataArgumentProcessor processor, MemoryData input[]) {
		preApply();
		processor.getPrepare().get().run();
		operator.accept(input);
		processor.getPostprocess().get().run();
		postApply();
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
				MemoryDataArgumentProcessor processor = processKernelArgs(output, args);
				MemoryData input[] = Stream.of(processor.getArguments()).toArray(MemoryData[]::new);

				if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");
				runApply(operator, processor, input);
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
				MemoryDataArgumentProcessor processor = processKernelArgs(null, args);
				MemoryData input[] = Stream.of(processor.getArguments()).toArray(MemoryData[]::new);

				if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");
				runApply(operator, processor, input);
			} else {
				throw new HardwareException("Kernel not supported");
			}
		} catch (CLException e) {
			throw new HardwareException("Could not evaluate AcceleratedOperation", e);
		}
	}

	protected MemoryDataArgumentProcessor processKernelArgs(MemoryBank output, Object args[]) {
		int kernelSize;

		if (!isKernel() || !enableKernel) {
			kernelSize = 1;
		} else if (output != null) {
			kernelSize = output.getCount();
		} else if (args.length > 0 && Stream.of(args).filter(a -> !(a instanceof MemoryData)).findAny().isEmpty()) {
			kernelSize = ((MemoryBank) args[0]).getCount();
		} else {
			kernelSize = -1;
		}

		List<ArrayVariable<? extends T>> arguments = getArgumentVariables();
		Map<ArrayVariable<? extends T>, MemoryData> mappings = output == null ? Collections.emptyMap() :
				Collections.singletonMap((ArrayVariable<? extends T>) getOutputVariable(), output);

		MemoryData kernelArgs[] = new MemoryData[arguments.size()];

		/*
		 * In the first pass, kernel size is inferred from Producer arguments that
		 * reference an Evaluable argument.
		 */
		i: for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i) == null) continue i;

			if (mappings.containsKey(arguments.get(i))) {
				kernelArgs[i] = mappings.get(arguments.get(i));
				continue i;
			} else {
				int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));

				if (refIndex >= 0) {
					kernelArgs[i] = (MemoryData) args[refIndex];
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

		List<Integer> sizes = new ArrayList<>();

		/*
		 * In the second pass, kernel size is inferred from Producer arguments
		 * that actually implement Shape. If an input to the operation declares
		 * the kernel dimension for what it will produce, it is known ahead of
		 * time what the expected kernel size is.
		 */
		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			Supplier p = arguments.get(i).getProducer();

			if (p instanceof MemoryDataDestination && ((MemoryDataDestination) p).getDelegate() instanceof Shape) {
				sizes.add(((Shape) ((MemoryDataDestination) p).getDelegate()).getShape().getCount());
			}
		}

		/*
		 * In the third pass, kernel size is inferred from Producer arguments
		 * that do not support kernel evaluation. Given that there is no way
		 * to specify kernel parameters for these arguments, it is safe to
		 * assume that the desired kernel parameters must be compatible with
		 * their output.
		 */
		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			Evaluable<T> c = (Evaluable<T>) ProducerCache.getEvaluableForSupplier(arguments.get(i).getProducer());

			if (!(c instanceof KernelizedEvaluable)) {
				kernelArgs[i] = (MemoryData) c.evaluate((Object[]) args);

				if (kernelArgs[i] instanceof MemoryBank) {
					sizes.add(((MemoryBank<?>) kernelArgs[i]).getCount());
				}
			}
		}

		/*
		 * If there is only one size, it can be used as the kernel size.
		 */
		if (kernelSize < 0 && sizes.size() == 1) {
			kernelSize = sizes.get(0);
		}

		/*
		 * If there are multiple sizes, but they are all the same,
		 * that can be used as the kernel size.
		 */
		k: if (kernelSize < 0 && sizes.size() > 1) {
			int sharedSize = sizes.get(0);
			for (int i = 1; i < sizes.size(); i++) {
				if (sharedSize != sizes.get(i)) {
					break k;
				}
			}

			kernelSize = sharedSize;
		}

		/*
		 * Otherwise, a kernel size compatible with all sizes may be inferred.
		 */
		if (kernelSize < 0 && sizes.size() > 0) {
			kernelSize = KernelSupport.inferKernelSize(sizes.stream().mapToInt(i -> i).toArray());
		}

		/*
		 * If the kernel size is still not known, the kernel size will be 1.
		 */
		if (kernelSize < 0) {
			if (enableKernelSizeWarnings)
				System.out.println("WARN: Could not infer kernel size, it will be set to 1");
			kernelSize = 1;
		}

		/*
		 * In the final pass, kernel arguments are evaluated in a way that ensures the
		 * result is compatible with the kernel size inferred in the first and second
		 * passes.
		 */
		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			Evaluable<T> c = (Evaluable<T>) ProducerCache.getEvaluableForSupplier(arguments.get(i).getProducer());

			if (c instanceof ProducerArgumentReference) {
				// TODO  This should not be necessary
				System.out.println("WARN: ProducerArgumentReference not detected by first pass");
				int argIndex = ((ProducerArgumentReference) c).getReferencedArgumentIndex();
				kernelArgs[i] = (MemoryData) args[argIndex];
			} else if (c instanceof KernelizedEvaluable && Stream.of(args).filter(a -> !(a instanceof MemoryData)).findAny().isEmpty()) {
				KernelizedEvaluable kp = (KernelizedEvaluable) c;
				kernelArgs[i] = kp.createKernelDestination(kernelSize);
				kp.kernelEvaluate((MemoryBank) kernelArgs[i], Stream.of(args).map(MemoryData.class::cast).toArray(MemoryData[]::new));

//				if (kernelSize > 1) {
//					if (((MemoryBank<?>) kernelArgs[i]).getCount() != kernelSize && kernelArgs[i] instanceof Traversable) {
//						kernelArgs[i] = (MemoryData) ((Traversable) kernelArgs[i]).traverse(1);
//					}
//
//					if (((MemoryBank<?>) kernelArgs[i]).getCount() != kernelSize) {
//						throw new IllegalArgumentException("Kernel argument " + i + " with count " +
//								((MemoryBank<?>) kernelArgs[i]).getCount() +
//								" is not compatible with kernel size " + kernelSize);
//					}
//				}
			} else {
				kernelArgs[i] = (MemoryData) c.evaluate((Object[]) args);
			}
		}

		return new MemoryDataArgumentProcessor(kernelArgs,
				Hardware.getLocalHardware().getDataContext().getKernelMemoryProvider(),
				this::createAggregatedInput, kernelSize);
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

	@Override
	public void destroy() {
		argumentMaps.forEach(ArgumentMap::destroy);
		argumentMaps = new ArrayList<>();
	}
}
