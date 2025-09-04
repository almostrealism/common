/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DefaultNameProvider;
import io.almostrealism.code.Execution;
import io.almostrealism.code.NameProvider;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.arguments.ProcessArgumentEvaluator;
import org.almostrealism.hardware.instructions.ExecutionKey;
import org.almostrealism.hardware.instructions.InstructionSetManager;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jni.NativeExecution;
import org.almostrealism.hardware.kernel.KernelWork;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.MemoryReplacementManager;
import org.almostrealism.hardware.metal.MTLBuffer;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.io.Console;
import org.almostrealism.io.TimingMetric;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class AcceleratedOperation<T extends MemoryData> extends OperationAdapter<T>
							implements Runnable, ScopeLifecycle, Countable, HardwareFeatures {
	public static boolean enableScopeLifecycle = false;

	public static Console console = Computation.console.child();

	public static TimingMetric retrieveOperatorMetric = console.timing("retrieveOperator");
	public static TimingMetric processArgumentsMetric =  console.timing("processArguments");
	public static TimingMetric acceptMetric = console.timing("accept");
	public static TimingMetric evaluateKernelMetric = console.timing("evaluateKernel");
	public static TimingMetric evaluateMetric = console.timing("evaluate");
	public static TimingMetric kernelCreateMetric = console.timing("kernelCreate");
	public static TimingMetric nonKernelEvalMetric = console.timing("nonKernelEval");
	public static TimingMetric wrappedEvalMetric = console.timing("wrappedEval");

	private static final ThreadLocal<Semaphore> semaphores = new ThreadLocal<>();

	private final boolean kernel;
	private boolean argumentMapping;
	private ComputeContext<MemoryData> context;

	private ProcessArgumentEvaluator evaluator;
	private ProcessDetailsFactory detailsFactory;
	protected MemoryDataArgumentMap argumentMap;

	@SafeVarargs
	protected AcceleratedOperation(ComputeContext<MemoryData> context, boolean kernel,
								   Supplier<Evaluable<? extends T>>... args) {
		super(args);
		setArgumentMapping(true);
		this.context = context;
		this.kernel = kernel;
	}

	@SafeVarargs
	public AcceleratedOperation(ComputeContext<MemoryData> context, String function, boolean kernel,
								Supplier<Evaluable<? extends T>>... args) {
		this(context, kernel, args);
		setFunctionName(function);
	}

	protected AcceleratedOperation(ComputeContext<MemoryData> context, boolean kernel) {
		setArgumentMapping(true);
		this.context = context;
		this.kernel = kernel;
	}

	public Class getSourceClass() { return getClass(); }

	public ComputeContext<MemoryData> getComputeContext() { return context; }

	public NameProvider getNameProvider() { return new DefaultNameProvider(this); }

	public abstract <K extends ExecutionKey> InstructionSetManager<K> getInstructionSetManager();

	public abstract <K extends ExecutionKey> K getExecutionKey();

	protected void setArgumentMapping(boolean enabled) {
		this.argumentMapping = enabled;
	}

	public ArrayVariable getArgument(int index) {
		return getInputs() == null ? getArgumentVariables().get(index) : getArgumentForInput(getInputs().get(index));
	}

	public Variable getOutputVariable() { return getArgument(getOutputArgumentIndex()); }
	
	/** @return  -1 */
	protected int getOutputArgumentIndex() { return -1; }

	@Override
	public List<Argument<? extends T>> getChildren() {
		return getArguments();
	}

	/** @return  -1 */
	@Override
	public long getCountLong() { return -1; }

	public MemoryData createAggregatedInput(int memLength, int atomicLength) {
		return getComputeContext().getDataContext().deviceMemory(() -> new Bytes(memLength, atomicLength));
	}

	public boolean isAggregatedInput() { return false; }

	protected void prepareScope() {
		if (argumentMap != null) {
			throw new UnsupportedOperationException("Redundant call to prepareScope");
		}

		resetArguments();

		if (argumentMapping) {
			argumentMap = MemoryDataArgumentMap.create(getComputeContext(), getMetadata(),
					isAggregatedInput() ? i -> createAggregatedInput(i, i) : null, isKernel());
			prepareArguments(argumentMap);
		}

		prepareScope(argumentMap == null ?
				DefaultScopeInputManager.getInstance(getComputeContext().getLanguage()) : argumentMap.getScopeInputManager());
	}

	protected void prepareScope(ScopeInputManager manager) {
		prepareScope(manager, null);
	}

	/**
	 * Prepare the {@link Execution} for this {@link AcceleratedOperation}.
	 * This will obtain the {@link #getInstructionSetManager()} InstructionSetManager}
	 * and either {@link #compile() compile} the operation or prepare the
	 * operation to use an {@link Execution} from a previously compiled
	 * {@link io.almostrealism.code.InstructionSet}.
	 *
	 * @return  An {@link Execution} for performing this operation
	 */
	public Execution load() {
		try {
			long start = System.nanoTime();
			InstructionSetManager<?> manager = getInstructionSetManager();
			Execution operator = manager.getOperator(getExecutionKey());
			retrieveOperatorMetric.addEntry(System.nanoTime() - start);
			return operator;
		} catch (HardwareException e) {
			throw e;
		} catch (Exception e) {
			throw new HardwareException("Could not obtain operator", e);
		}
	}

	public Scope<?> compile() {
		if (!enableScopeLifecycle) {
			throw new UnsupportedOperationException();
		}

		prepareScope();
		return null;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (getInputs() != null) ScopeLifecycle.prepareArguments(getInputs().stream(), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		if (!enableScopeLifecycle) {
			throw new UnsupportedOperationException();
		}

		if (getArgumentVariables() != null) return;

		if (getInputs() != null) {
			ScopeLifecycle.prepareScope(getInputs().stream(), manager, context);
			setArguments(getInputs().stream()
					.map(manager.argumentForInput(getNameProvider()))
					.map(var -> new Argument(var, Expectation.EVALUATE_AHEAD))
					.map(arg -> (Argument<? extends T>) arg)
					.collect(Collectors.toList()));
		}
	}

	public void preApply() {
		if (argumentMap != null) {
			argumentMap.getPrepareData().get().run();
		}
	}

	public void postApply() {
		if (argumentMap != null) {
			argumentMap.getPostprocessData().get().run();
		}
	}

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

	public void setEvaluator(ProcessArgumentEvaluator evaluator) {
		this.evaluator = evaluator;

		if (detailsFactory != null) {
			throw new UnsupportedOperationException();
		}
	}

	protected MemoryReplacementManager createMemoryReplacementManager() {
		return new MemoryReplacementManager(
				getComputeContext().getDataContext().getKernelMemoryProvider(),
				this::createAggregatedInput);
	}

	public ProcessDetailsFactory getDetailsFactory() {
		if (detailsFactory == null) {
			detailsFactory = new ProcessDetailsFactory<>(
					isKernel(), isFixedCount(), getCount(),
					getArgumentVariables(), getOutputArgumentIndex(),
					this::createMemoryReplacementManager,
					getComputeContext()::runLater);

			if (evaluator != null) {
				detailsFactory.setEvaluator(evaluator);
			}
		}

		return detailsFactory;
	}

	protected AcceleratedProcessDetails getProcessDetails(MemoryBank output, Object[] args) {
		Semaphore lastSemaphore = getSemaphore();

		try {
			pushSemaphore();
			return getDetailsFactory().init(output, args).construct();
		} finally {
			semaphores.set(lastSemaphore);
		}
	}

	protected void pushSemaphore() {
		Semaphore current = getSemaphore();

		if (current == null) {
			semaphores.set(new DefaultLatchSemaphore(getMetadata(), 0));
		} else {
			semaphores.set(current.withRequester(getMetadata()));
		}
	}

	protected synchronized AcceleratedProcessDetails apply(MemoryBank output, Object[] args) {
		if (getArguments() == null && getInstructionSetManager() == null) {
			if (enableScopeLifecycle) {
				warn(getName() + " was not compiled ahead of time");
				compile();
			} else {
				throw new UnsupportedOperationException(getName() + " was not compiled ahead of time");
			}
		}

		// Load the inputs
		AcceleratedProcessDetails process = getProcessDetails(output, args);
		process.setSemaphore(new DefaultLatchSemaphore(getMetadata(), 1));

		process.whenReady(() -> {
			MemoryData input[] = process.getArguments(MemoryData[]::new);

			// Prepare the operator
			Execution operator = setupOperator(process);
			boolean processing = isPreprocessingRequired(process);

			// Preprocessing
			if (processing) {
				preApply();
				process.getPrepare().get().run();
			}

			// Run the operator
			long start = System.nanoTime();
			Semaphore nextSemaphore = operator.accept(input, null);
			acceptMetric.addEntry(System.nanoTime() - start);

			// Postprocessing
			if (processing) {
				if (nextSemaphore != null) {
					// TODO  This should actually result in a new Semaphore
					// TODO  that performs the post processing whenever the
					// TODO  original semaphore is finished
					// warn("Postprocessing will wait for semaphore");
					nextSemaphore.waitFor();
				}

				process.getPostprocess().get().run();
				postApply();
			}
		});

		return process;
	}

	protected Execution setupOperator(AcceleratedProcessDetails process) {
		try {
			Execution operator = load();

			long start = System.nanoTime();
			if (!(operator instanceof KernelWork)) {
				throw new UnsupportedOperationException();
			} else if (operator.isDestroyed()) {
				throw new HardwareException("Operator has already been destroyed");
			}

			((KernelWork) operator).setGlobalWorkOffset(0);
			((KernelWork) operator).setGlobalWorkSize(process.getKernelSize());
			processArgumentsMetric.addEntry(System.nanoTime() - start);

			return operator;
		} catch (HardwareException e) {
			throw e;
		} catch (Exception e) {
			throw new HardwareException("Could not setup operator", e);
		}
	}

	protected boolean isPreprocessingRequired(AcceleratedProcessDetails process) {
		if (!process.isEmpty())
			return true;

		return argumentMap != null && !argumentMap.getReplacementMap().isEmpty();
	}

	public boolean isKernel() { return kernel; }

	@Override
	public void destroy() {
		super.destroy();

		argumentMap.destroy();
	}

	@Override
	public Console console() { return console; }

	public static Semaphore getSemaphore() { return semaphores.get(); }

	public static void waitFor() {
		Semaphore s = getSemaphore();

		if (s != null) {
			s.waitFor();
			semaphores.set(null);
		}
	}

	public static void printTimes() {
		printTimes(false);
	}

	public static void printTimes(boolean verbose) {
		if (verbose) {
			// Memory access
			if (!NativeMemoryProvider.ioTime.getEntries().isEmpty()) {
				NativeMemoryProvider.ioTime.print();
			}

			if (!MTLBuffer.ioTime.getEntries().isEmpty()) {
				MTLBuffer.ioTime.print();
			}

			// Compilation
			console.println("AcceleratedOperation: Retrieve operator total - " +
					((long) AcceleratedOperation.retrieveOperatorMetric.getTotal()) + "sec");
			console.println("AcceleratedOperation: JNI Compile - " +
					((long) NativeCompiler.compileTime.getTotal()) + "sec");
			console.println("AcceleratedOperation: MTL Compile - " +
					((long) MetalProgram.compileTime.getTotal()) + "sec");
		}

		// Runtime
		console.println("AcceleratedOperation: " +
				((long) AcceleratedOperation.processArgumentsMetric.getTotal()) + "sec (process), " +
				((long) AcceleratedOperation.acceptMetric.getTotal()) + "sec (accept)");
		console.println("AcceleratedOperation Process Body: " +
				((long) AcceleratedOperation.kernelCreateMetric.getTotal()) + "sec (create), " +
				((long) AcceleratedOperation.evaluateKernelMetric.getTotal()) + "sec (evaluate kernel), " +
				((long) AcceleratedOperation.evaluateMetric.getTotal()) + "sec (evaluate)");

		if (verbose) {
			HardwareOperator.prepareArgumentsMetric.print();
			HardwareOperator.computeDimMasksMetric.print();
			NativeExecution.dimMaskMetric.print();

			AcceleratedOperation.kernelCreateMetric.print();
			AcceleratedOperation.nonKernelEvalMetric.print();
			AcceleratedOperation.wrappedEvalMetric.print();
		}
	}
}
