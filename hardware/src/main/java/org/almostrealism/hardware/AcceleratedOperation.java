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
import io.almostrealism.code.Execution;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.Argument;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
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

/**
 * Abstract base class for all hardware-accelerated operations, managing compilation, argument processing, and execution.
 *
 * <p>{@link AcceleratedOperation} coordinates the entire lifecycle of accelerated computations, from scope
 * preparation and kernel compilation through argument mapping, execution, and cleanup. It serves as the foundation
 * for both kernel-based operations (GPU/OpenCL/Metal) and JNI-based native code execution.</p>
 *
 * <h2>Operation Lifecycle</h2>
 *
 * <p>An accelerated operation progresses through several phases:</p>
 * <pre>
 * 1. Scope Preparation:  prepareScope() -> creates ArgumentMap, prepares inputs
 * 2. Compilation:        load() -> compiles or retrieves cached kernel/native code
 * 3. Argument Setup:     getProcessDetails() -> prepares arguments for execution
 * 4. Preprocessing:      preApply() -> aggregates arguments if needed
 * 5. Execution:          operator.accept() -> runs kernel/native code
 * 6. Postprocessing:     postApply() -> disaggregates results
 * 7. Cleanup:            destroy() -> releases resources
 * </pre>
 *
 * <h2>Argument Mapping and Aggregation</h2>
 *
 * <p>For kernel operations, {@link AcceleratedOperation} automatically manages argument preparation
 * via {@link MemoryDataArgumentMap}:</p>
 * <pre>{@code
 * // When scope is prepared:
 * prepareScope() {
 *     // Creates MemoryDataArgumentMap with aggregation support
 *     argumentMap = MemoryDataArgumentMap.create(context, metadata, ...);
 *
 *     // Maps operation inputs to kernel arguments
 *     prepareArguments(argumentMap);
 * }
 *
 * // Before execution:
 * preApply() {
 *     // Copies CPU memory -> aggregated GPU buffer
 *     argumentMap.getPrepareData().run();
 * }
 *
 * // After execution:
 * postApply() {
 *     // Copies aggregated buffer -> original CPU memory
 *     argumentMap.getPostprocessData().run();
 * }
 * }</pre>
 *
 * <h2>Kernel vs Non-Kernel Operations</h2>
 *
 * <p>Operations can be either kernel-based (executed on GPU/accelerator) or non-kernel (JNI/native):</p>
 * <pre>{@code
 * // Kernel operation (GPU)
 * AcceleratedOperation kernelOp = new MyKernelOperation(context, true);
 * kernelOp.prepareScope();  // Creates argumentMap with aggregation
 * kernelOp.load();          // Compiles OpenCL/Metal kernel
 *
 * // Non-kernel operation (JNI)
 * AcceleratedOperation nativeOp = new MyNativeOperation(context, false);
 * nativeOp.prepareScope();  // Simpler argument handling
 * nativeOp.load();          // Compiles C code via JNI
 * }</pre>
 *
 * <h2>Instruction Set Management</h2>
 *
 * <p>Compilation and caching are delegated to {@link InstructionSetManager}, which provides
 * multi-level caching (operation container cache, process tree cache, scope cache):</p>
 * <pre>{@code
 * @Override
 * public InstructionSetManager getInstructionSetManager() {
 *     return Hardware.getLocalHardware().getComputeContext().getKernelManager();
 * }
 *
 * @Override
 * public ExecutionKey getExecutionKey() {
 *     return new MyExecutionKey(getScope(), getArguments());
 * }
 *
 * // Load operator (compiles or retrieves from cache)
 * Execution operator = load();  // Uses instructionSetManager.getOperator(key)
 * }</pre>
 *
 * <h2>Asynchronous Execution</h2>
 *
 * <p>Operations coordinate asynchronous execution via {@link Semaphore}:</p>
 * <pre>{@code
 * // Synchronous execution
 * operation.run();  // Blocks until complete
 *
 * // Asynchronous execution
 * AcceleratedProcessDetails process = operation.apply(output, args);
 * process.getSemaphore().waitFor();  // Wait when needed
 *
 * // Chained async execution
 * process1.getSemaphore().andThen(() -> {
 *     return operation2.apply(...);
 * });
 * }</pre>
 *
 * <h2>Subclass Requirements</h2>
 *
 * <p>Concrete implementations must provide:</p>
 * <ul>
 *   <li>{@link #getInstructionSetManager()} - Provide the manager for this operation type</li>
 *   <li>{@link #getExecutionKey()} - Create a unique key for caching this operation</li>
 *   <li>{@link #isAggregatedInput()} - Whether arguments should be aggregated</li>
 *   <li>{@link #getOutputArgumentIndex()} - Index of output argument in kernel signature</li>
 * </ul>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Creating a Kernel Operation</h3>
 * <pre>{@code
 * public class VectorAddOperation extends AcceleratedComputationOperation {
 *     public VectorAddOperation(Producer<PackedCollection<?>> a,
 *                               Producer<PackedCollection<?>> b) {
 *         super(Hardware.getLocalHardware().getComputeContext(), true,
 *               a.get().evaluate(), b.get().evaluate());
 *     }
 *
 *     @Override
 *     public ExecutionKey getExecutionKey() {
 *         return new DefaultExecutionKey(getScope(), getArguments());
 *     }
 * }
 * }</pre>
 *
 * <h3>Executing an Operation</h3>
 * <pre>{@code
 * // Compile and execute synchronously
 * VectorAddOperation op = new VectorAddOperation(a, b);
 * PackedCollection<?> result = op.evaluate();  // Blocks until complete
 *
 * // Execute asynchronously
 * AcceleratedProcessDetails process = op.apply(output, args);
 * // ... do other work ...
 * process.getSemaphore().waitFor();  // Wait when needed
 * }</pre>
 *
 * @param <T> The type of {@link MemoryData} this operation works with
 * @see AcceleratedComputationOperation
 * @see AcceleratedComputationEvaluable
 * @see InstructionSetManager
 * @see MemoryDataArgumentMap
 * @see AcceleratedProcessDetails
 */
public abstract class AcceleratedOperation<T extends MemoryData> extends OperationAdapter<T>
							implements Runnable, ScopeLifecycle, Countable, HardwareFeatures {

	/** Console for logging accelerated operation events. */
	public static Console console = Computation.console.child();

	/** Timing metric for operator retrieval from instruction set managers. */
	public static TimingMetric retrieveOperatorMetric = console.timing("retrieveOperator");
	/** Timing metric for wrapped evaluation. */
	public static TimingMetric wrappedEvalMetric = console.timing("wrappedEval");

	private static final ThreadLocal<Semaphore> semaphores = new ThreadLocal<>();

	private final boolean kernel;
	private boolean argumentMapping;
	private ComputeContext<MemoryData> context;

	private ProcessArgumentEvaluator evaluator;
	private ProcessDetailsFactory detailsFactory;
	protected MemoryDataArgumentMap argumentMap;

	protected AcceleratedOperation(ComputeContext<MemoryData> context, boolean kernel) {
		setArgumentMapping(true);
		this.context = context;
		this.kernel = kernel;
	}

	/**
	 * Returns the {@link ComputeContext} this operation executes within.
	 *
	 * @return The compute context (OpenCL, Metal, JNI, etc.)
	 */
	public ComputeContext<MemoryData> getComputeContext() { return context; }

	/**
	 * Returns the {@link InstructionSetManager} responsible for compiling and caching this operation.
	 *
	 * <p>The instruction set manager coordinates kernel compilation, caching, and retrieval of
	 * compiled {@link Execution}s. Implementations determine whether instruction sets are
	 * reused across instances or operation-specific.</p>
	 *
	 * @param <K> The execution key type
	 * @return The instruction set manager for this operation
	 */
	public abstract <K extends ExecutionKey> InstructionSetManager<K> getInstructionSetManager();

	/**
	 * Returns the {@link ExecutionKey} that uniquely identifies this operation's compiled form.
	 *
	 * <p>The execution key is used to look up compiled {@link Execution}s within the
	 * {@link InstructionSetManager}. Different key types support different caching strategies:
	 * {@link io.almostrealism.hardware.instructions.ScopeSignatureExecutionKey} for signature-based
	 * reuse, {@link io.almostrealism.hardware.instructions.DefaultExecutionKey} for per-operation caching.</p>
	 *
	 * @param <K> The execution key type
	 * @return The execution key for this operation
	 */
	public abstract <K extends ExecutionKey> K getExecutionKey();

	protected void setArgumentMapping(boolean enabled) {
		this.argumentMapping = enabled;
	}

	/**
	 * Returns the argument list for this operation.
	 *
	 * <p>Implements {@link io.almostrealism.relation.Producer#getChildren()} by delegating
	 * to {@link #getArguments()}.</p>
	 *
	 * @return The list of arguments
	 */
	@Override
	public List<Argument<? extends T>> getChildren() {
		return getArguments();
	}

	/**
	 * Creates aggregated input memory for kernel execution.
	 *
	 * <p>Aggregated inputs combine multiple separate memory allocations into a single
	 * contiguous buffer, improving GPU kernel performance by reducing memory indirection.</p>
	 *
	 * @param memLength Total memory length in bytes
	 * @param atomicLength Atomic memory length (element size) in bytes
	 * @return Allocated aggregated input memory on the device
	 */
	public MemoryData createAggregatedInput(int memLength, int atomicLength) {
		return getComputeContext().getDataContext().deviceMemory(() -> new Bytes(memLength, atomicLength));
	}

	/**
	 * Returns whether this operation uses aggregated input memory.
	 *
	 * <p>Operations with many small inputs benefit from aggregation, which reduces
	 * kernel launch overhead and improves memory access patterns on GPU.</p>
	 *
	 * @return true if inputs are aggregated, false otherwise
	 */
	public abstract boolean isAggregatedInput();

	protected abstract int getOutputArgumentIndex();

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
	 * This will obtain the {@link #getInstructionSetManager() InstructionSetManager}
	 * and either compile the operation or prepare the
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

	/**
	 * Prepares arguments for this operation by adding them to the argument map.
	 *
	 * <p>Delegates to {@link ScopeLifecycle#prepareArguments} for all inputs.</p>
	 *
	 * @param map The argument map to populate
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		if (getInputs() != null) ScopeLifecycle.prepareArguments(getInputs().stream(), map);
	}

	/**
	 * Executes pre-application data preparation.
	 *
	 * <p>Runs the prepare data runnable from the argument map, which typically
	 * handles memory transfers and argument packing before kernel dispatch.</p>
	 */
	public void preApply() {
		if (argumentMap != null) {
			argumentMap.getPrepareData().get().run();
		}
	}

	/**
	 * Executes post-application data processing.
	 *
	 * <p>Runs the postprocess data runnable from the argument map, which typically
	 * handles result retrieval and memory cleanup after kernel execution.</p>
	 */
	public void postApply() {
		if (argumentMap != null) {
			argumentMap.getPostprocessData().get().run();
		}
	}

	/**
	 * Executes this operation synchronously.
	 *
	 * <p>Applies any compute requirements, dispatches the kernel with no arguments,
	 * and waits for completion. This is the primary entry point for executing
	 * compiled operations.</p>
	 */
	@Override
	public void run() {
		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			AcceleratedProcessDetails process = apply(null, new Object[0]);
			waitFor(process.getSemaphore());
		} finally {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	/**
	 * Sets the argument evaluator for argument substitution.
	 *
	 * <p>The evaluator allows runtime substitution of arguments when reusing
	 * compiled operations with different input data (e.g., in instruction containers).</p>
	 *
	 * @param evaluator The argument evaluator to use
	 * @throws UnsupportedOperationException if a details factory is already set
	 */
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

	protected synchronized void createDetailsFactory() {
		if (detailsFactory != null) return;

		detailsFactory = new ProcessDetailsFactory<>(
				isKernel(), isFixedCount(), getCount(),
				getArgumentVariables(), getOutputArgumentIndex(),
				this::createMemoryReplacementManager,
				getComputeContext()::runLater);

		if (evaluator != null) {
			detailsFactory.setEvaluator(evaluator);
		}
	}

	public ProcessDetailsFactory getDetailsFactory() {
		if (detailsFactory == null) {
			createDetailsFactory();
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
			throw new UnsupportedOperationException("Operation was not compiled");
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

			if (!(operator instanceof KernelWork)) {
				throw new UnsupportedOperationException();
			} else if (operator.isDestroyed()) {
				throw new HardwareException("Operator has already been destroyed");
			}

			((KernelWork) operator).setGlobalWorkOffset(0);
			((KernelWork) operator).setGlobalWorkSize(process.getKernelSize());

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

		return argumentMap != null && argumentMap.hasReplacements();
	}

	public boolean isKernel() { return kernel; }

	@Override
	public void destroy() {
		super.destroy();

		if (argumentMap != null) {
			argumentMap.destroy();
		}
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

		HardwareOperator.prepareArgumentsMetric.print();
		HardwareOperator.computeDimMasksMetric.print();
		NativeExecution.dimMaskMetric.print();

		AcceleratedOperation.wrappedEvalMetric.print();
	}
}
