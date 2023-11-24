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
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.jni.NativeExecution;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
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

	public static double retrieveOperatorTime, processArgumentsTime, acceptTime;
	public static double processTime;
	public static double createKernelDestinationTime, evaluateKernelTime, evaluateTime;
	public static Map<String, Double> kernelCreateTimes = Collections.synchronizedMap(new HashMap<>());
	public static Map<String, Double> nonKernelEvalTimes = Collections.synchronizedMap(new HashMap<>());
	public static Map<String, Double> wrappedEvalTimes = Collections.synchronizedMap(new HashMap<>());

	private static final ThreadLocal<Semaphore> semaphores = new ThreadLocal<>();
	private static final ThreadLocal<CreatedMemoryData> created = new ThreadLocal<>();

	private final boolean kernel;
	private boolean argumentMapping;
	private ComputeContext<MemoryData> context;
	private Class cls;

	private ProcessDetailsFactory detailsFactory;
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

		if (detailsFactory == null) {
			detailsFactory = new ProcessDetailsFactory<>(isKernel(), isFixedCount(), getCount(),
					getArgumentVariables(), getOutputVariable(), created,
					getComputeContext().getDataContext().getKernelMemoryProvider(),
					this::createAggregatedInput);
		}

		if (enableKernelLog) System.out.println("AcceleratedOperation: Preparing " + getName() + " kernel...");
		AcceleratedProcessDetails process = detailsFactory.init(output, args).construct();
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
				((long) ProcessDetailsFactory.initTime) + "sec (init), " +
				((long) AcceleratedOperation.processTime) + "sec (process)");
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
		AcceleratedOperation.wrappedEvalTimes.forEach((k, v) ->
				System.out.println("AcceleratedOperation: " + k + " - " + v.longValue() + "sec (wrapped evaluate)"));
	}
}
