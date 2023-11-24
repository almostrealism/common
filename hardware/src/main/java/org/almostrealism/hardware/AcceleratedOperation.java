/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.code.Semaphore;
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
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.jni.NativeExecution;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.io.SystemUtils;
import org.jocl.CLException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AcceleratedOperation<T extends MemoryData> extends OperationAdapter<T> implements Runnable,
														KernelizedOperation, ScopeLifecycle, ComputerFeatures {
	public static final boolean enableArgumentMapping = true;
	public static boolean enableArgumentKernelSize = true;
	public static boolean enableKernelSizeWarnings = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_SIZE_WARNINGS").orElse(true);

	public static double retrieveOperatorTime, processArgumentsTime, acceptTime;
	public static double processKernelSizeTime1, processKernelSizeTime2, processKernelSizeTime3;
	public static double createKernelDestinationTime, evaluateKernelTime, evaluateTime;
	public static Map<String, Double> kernelCreateTimes = Collections.synchronizedMap(new HashMap<>());
	public static Map<String, Double> nonKernelEvalTimes = Collections.synchronizedMap(new HashMap<>());

	private static final ThreadLocal<Semaphore> semaphores = new ThreadLocal<>();
	private static final ThreadLocal<CreatedMemoryData> created = new ThreadLocal<>();

	private final boolean kernel;
	private boolean argumentMapping;
	private ComputeContext<MemoryData> context;
	private Class cls;

	protected List<ArgumentMap> argumentMaps;
	private OperationList preOp;
	private OperationList postOp;

	@SafeVarargs
	protected AcceleratedOperation(ComputeContext<MemoryData> context, boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		super(args);
		setArgumentMapping(true);
		this.context = context;
		this.kernel = kernel;
		this.argumentMaps = new ArrayList<>();
	}

	@SafeVarargs
	public AcceleratedOperation(ComputeContext<MemoryData> context, String function, boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		this(context, kernel, args);
		setFunctionName(function);
	}

	@SafeVarargs
	protected AcceleratedOperation(ComputeContext<MemoryData> context, boolean kernel, ArrayVariable<T>... args) {
		super(Arrays.stream(args).map(var -> new Argument(var, Expectation.EVALUATE_AHEAD)).toArray(Argument[]::new));
		setArgumentMapping(true);
		this.context = context;
		this.kernel = kernel;
		this.argumentMaps = new ArrayList<>();
	}

	public Class getSourceClass() {
		if (cls != null) return cls;
		return getClass();
	}

	public ComputeContext<MemoryData> getComputeContext() { return context; }

	public abstract Execution getOperator();

	protected void setArgumentMapping(boolean enabled) {
		this.argumentMapping = enabled;
	}

	@Override
	public ArrayVariable getArgument(LanguageOperations lang, int index, Expression<Integer> size) {
		return getInputs() == null ? getArgumentVariables().get(index) : getArgumentForInput(getInputs().get(index));
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public int getCount() { return -1; }

	public MemoryData createAggregatedInput(int memLength, int atomicLength) {
		return getComputeContext().getDataContext().deviceMemory(() -> new Bytes(memLength, atomicLength));
	}

	public boolean isAggregatedInput() { return false; }

	protected void prepareScope() {
		resetArguments();

		SupplierArgumentMap argumentMap = null;

		if (argumentMapping) {
			if (enableArgumentMapping) {
				if (preOp != null || postOp != null) {
					throw new UnsupportedOperationException("Redundant call to prepareScope");
				}

				argumentMap = MemoryDataArgumentMap.create(getComputeContext(), isAggregatedInput() ? i -> createAggregatedInput(i, i) : null, isKernel());
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
				DefaultScopeInputManager.getInstance(getComputeContext().getLanguage()) : argumentMap.getScopeInputManager());
	}

	@Override
	public Scope<?> compile() {
		prepareScope();
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
	public void run() {
		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			AcceleratedProcessDetails process = apply(null, new Object[0]);
			if (!Hardware.isAsync()) waitFor(process.getSemaphore());
		} finally {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	protected synchronized AcceleratedProcessDetails apply(MemoryBank output, Object[] args) {
		if (getArguments() == null) {
			System.out.println("WARN: " + getName() + " was not compiled ahead of time");
			compile();
		}

		long start = System.nanoTime();

		Execution operator = getOperator();
		retrieveOperatorTime += sec(System.nanoTime() - start); start = System.nanoTime();

		if (operator instanceof KernelWork == false) {
			throw new UnsupportedOperationException();
		}

		if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
		AcceleratedProcessDetails process = processKernelArgs(output, args);
		MemoryData input[] = Stream.of(process.getArguments()).toArray(MemoryData[]::new);
		((KernelWork) operator).setGlobalWorkOffset(0);
		((KernelWork) operator).setGlobalWorkSize(process.getKernelSize());
		processArgumentsTime += sec(System.nanoTime() - start); start = System.nanoTime();

		if (enableKernelLog) System.out.println("AcceleratedOperation: Evaluating " + getName() + " kernel...");

		boolean processing = !process.isEmpty();
		if (preOp != null && !preOp.isEmpty()) processing = true;
		if (postOp != null && !postOp.isEmpty()) processing = true;

		if (processing) {
			preApply();
			process.getPrepare().get().run();
		}

		Semaphore semaphore = operator.accept(input, semaphores.get());
		acceptTime += sec(System.nanoTime() - start); start = System.nanoTime();
		process.setSemaphore(semaphore);
		semaphores.set(semaphore);

		if (processing) {
			if (semaphore != null) throw new UnsupportedOperationException();
			process.getPostprocess().get().run();
			postApply();
		}

		return process;
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

	@Override
	public void kernelOperate(MemoryBank output, MemoryData[] args) {
		try {
			if (isKernel()) {
				apply(output, args);
			} else {
				throw new HardwareException("Kernel not supported");
			}
		} catch (CLException e) {
			throw new HardwareException("Could not evaluate AcceleratedOperation", e);
		}
	}

	protected AcceleratedProcessDetails processKernelArgs(MemoryBank output, Object args[]) {
		long start = System.nanoTime();

		int kernelSize;

		if (!isKernel()) {
			kernelSize = 1;
		} else if (!enableArgumentKernelSize && isFixedCount()) {
			kernelSize = getCount();
		} else if (output != null) {
			kernelSize = output.getCount();
		} else if (args.length > 0 && Stream.of(args).filter(a -> !(a instanceof MemoryData)).findAny().isEmpty()) {
			kernelSize = ((MemoryBank) args[0]).getCount();
		} else if (isFixedCount()) {
			kernelSize = getCount();
		} else {
			kernelSize = -1;
		}

		List<ArrayVariable<? extends T>> arguments = getArgumentVariables();
		Map<ArrayVariable<? extends T>, MemoryData> mappings = output == null ? Collections.emptyMap() :
				Collections.singletonMap((ArrayVariable<? extends T>) getOutputVariable(), output);

		MemoryData kernelArgs[] = new MemoryData[arguments.size()];
		Evaluable kernelArgEvaluables[] = new Evaluable[arguments.size()];

		processKernelSizeTime1 += sec(System.nanoTime() - start); start = System.nanoTime();

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
			if (kernelArgs[i] instanceof MemoryBank) {
				kernelSize = ((MemoryBank<?>) kernelArgs[i]).getCount();
			}
		}

		processKernelSizeTime2 += sec(System.nanoTime() - start); start = System.nanoTime();

		/*
		 * In the second pass, kernel size is inferred from Producer arguments
		 * that do not support kernel evaluation. Given that there is no way
		 * to specify kernel parameters for these arguments, it is safe to
		 * assume that the desired kernel parameters must be compatible with
		 * their output.
		 */
		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			kernelArgEvaluables[i] = ProducerCache.getEvaluableForSupplier(arguments.get(i).getProducer());
			if (kernelArgEvaluables[i] == null) {
				throw new UnsupportedOperationException();
			}

			if (!(kernelArgEvaluables[i] instanceof KernelizedEvaluable) ||
					(kernelArgEvaluables[i] instanceof HardwareEvaluable && !((HardwareEvaluable) kernelArgEvaluables[i]).isKernel())) {
				long s = System.nanoTime();
				Object o = kernelArgEvaluables[i].evaluate(args);
				if (!(o instanceof MemoryData))
					throw new IllegalArgumentException();

				kernelArgs[i] = (MemoryData) o;

				nonKernelEvalTimes.merge(arguments.get(i).getProducer().getClass().getName(), sec(System.nanoTime() - s), (a, b) -> a + b);
			}
		}

		/*
		 * If the kernel size is still not known, the kernel size will be 1.
		 */
		if (kernelSize < 0) {
			if (enableKernelSizeWarnings)
				System.out.println("WARN: Could not infer kernel size, it will be set to 1");
			kernelSize = 1;
		}

		processKernelSizeTime3 += sec(System.nanoTime() - start); start = System.nanoTime();

		MemoryData[] memoryDataArgs = null;
		boolean allMemoryData = args.length <= 0 || Stream.of(args).filter(a -> !(a instanceof MemoryData)).findAny().isEmpty();
		if (allMemoryData) memoryDataArgs = Stream.of(args).map(MemoryData.class::cast).toArray(MemoryData[]::new);

		/*
		 * In the final pass, kernel arguments are evaluated in a way that ensures the
		 * result is compatible with the kernel size inferred in the first and second
		 * passes.
		 */
		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			if (kernelArgEvaluables[i] instanceof KernelizedEvaluable && allMemoryData) {
				kernelArgs[i] = (MemoryData) kernelArgEvaluables[i].createDestination(kernelSize);

				double time = sec(System.nanoTime() - start); start = System.nanoTime();
				kernelCreateTimes.merge(kernelArgEvaluables[i].getClass().getName(), time, (a, b) -> a + b);
				createKernelDestinationTime += time;

				if (created.get() != null)
					created.get().add(kernelArgs[i]);

				kernelArgEvaluables[i].into(kernelArgs[i]).evaluate(memoryDataArgs);

				evaluateKernelTime += sec(System.nanoTime() - start); start = System.nanoTime();
			} else {
				kernelArgs[i] = (MemoryData) kernelArgEvaluables[i].evaluate(args);
				evaluateTime += sec(System.nanoTime() - start); start = System.nanoTime();
			}
		}

		return new AcceleratedProcessDetails(kernelArgs,
				getComputeContext().getDataContext().getKernelMemoryProvider(),
				this::createAggregatedInput, kernelSize);
	}

	private double sec(long nanos) {
		return nanos / 1e9;
	}

	public boolean isKernel() { return kernel; }

	@Override
	public void destroy() {
		super.destroy();

		argumentMaps.forEach(ArgumentMap::destroy);
		argumentMaps = new ArrayList<>();

		preOp.destroy();
		postOp.destroy();
		preOp = null;
		postOp = null;
	}

	public static Semaphore getSemaphore() { return semaphores.get(); }

	public static void waitFor() {
		Semaphore s = getSemaphore();

		if (s != null) {
			s.waitFor();
			semaphores.set(null);
		}
	}

	public static <T> T record(CreatedMemoryData data, Callable<T> exec) {
		CreatedMemoryData last = created.get();

		try {
			created.set(data);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			created.set(last);
		}
	}

	public static <I, O> O apply(Supplier<I> input, Function<I, O> process) {
		CreatedMemoryData data = new CreatedMemoryData();

		try {
			I in = record(data, () -> input.get());
			return process.apply(in);
		} finally {
			data.destroy();
		}
	}

	public static void printTimes() {
		System.out.println("AcceleratedOperation: " +
				((long) AcceleratedOperation.retrieveOperatorTime) + "sec (operator), " +
				((long) AcceleratedOperation.processArgumentsTime) + "sec (process), " +
				((long) AcceleratedOperation.acceptTime) + "sec (accept)");
		System.out.println("AcceleratedOperation Process Init: " +
				((long) AcceleratedOperation.processKernelSizeTime1) + "sec (size), " +
				((long) AcceleratedOperation.processKernelSizeTime2) + "sec (size), " +
				((long) AcceleratedOperation.processKernelSizeTime3) + "sec (size)");
		System.out.println("AcceleratedOperation Process Body: " +
				((long) AcceleratedOperation.createKernelDestinationTime) + "sec (create), " +
				((long) AcceleratedOperation.evaluateKernelTime) + "sec (evaluate kernel), " +
				((long) AcceleratedOperation.evaluateTime) + "sec (evaluate)");
		System.out.println("AcceleratedOperation Accept: " +
				((long) HardwareOperator.prepareArgumentsTime) + "sec (prepare), " +
				((long) HardwareOperator.computeDimMasksTime) + "sec (masks), " +
				((long) NativeExecution.dimMaskTime) + "sec (masks)");

		AcceleratedOperation.kernelCreateTimes.forEach((k, v) ->
				System.out.println("AcceleratedOperation: " + k + " - " + v.longValue() + "sec (create)"));
		AcceleratedOperation.nonKernelEvalTimes.forEach((k, v) ->
				System.out.println("AcceleratedOperation: " + k + " - " + v.longValue() + "sec (evaluate)"));
	}
}
