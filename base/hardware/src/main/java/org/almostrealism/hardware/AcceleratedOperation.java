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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.Argument;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.arguments.ProcessArgumentEvaluator;
import org.almostrealism.hardware.instructions.ExecutionKey;
import org.almostrealism.hardware.instructions.InstructionSetManager;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jni.NativeExecution;
import org.almostrealism.hardware.kernel.KernelWork;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
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
 * 1. Scope Preparation:  prepareScope() -> creates the argument map and prepares inputs
 * 2. Compilation:        load() -> compiles or retrieves cached kernel/native code
 * 3. Argument Setup:     getProcessDetails() -> prepares arguments for execution
 * 4. Execution:          operator.accept() -> runs kernel/native code
 * 5. Cleanup:            destroy() -> releases resources
 * </pre>
 *
 * <h2>Argument Mapping</h2>
 *
 * <p>For kernel operations, {@link AcceleratedOperation} automatically manages argument preparation
 * via {@link MemoryDataArgumentMap}:</p>
 * <pre>{@code
 * // When scope is prepared:
 * prepareScope() {
 *     // Creates MemoryDataArgumentMap
 *     argumentMap = MemoryDataArgumentMap.create();
 * }
 * }</pre>
 *
 * <h2>Kernel vs Native Execution</h2>
 *
 * <p>Whether an operation is dispatched as a GPU/OpenCL/Metal kernel or as JNI/native code is
 * determined by the {@link ComputeContext} the operation was created within, not by the operation
 * itself:</p>
 * <pre>{@code
 * AcceleratedOperation op = new MyOperation(context);
 * op.prepareScope();  // Creates argumentMap
 * op.load();          // Compiles via the context (OpenCL/Metal kernel, or C/JNI)
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
 *   <li>{@link #getOutputArgumentIndex()} - Index of output argument in kernel signature</li>
 * </ul>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Creating a Kernel Operation</h3>
 * <pre>{@code
 * public class VectorAddOperation extends AcceleratedComputationOperation {
 *     public VectorAddOperation(Producer<PackedCollection> a,
 *                               Producer<PackedCollection> b) {
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
 * PackedCollection result = op.evaluate();  // Blocks until complete
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
							implements Runnable, Submittable, ScopeLifecycle, Countable, HardwareFeatures {

	/** Console for logging accelerated operation events. */
	public static Console console = Computation.console.child();

	/** Timing metric for operator retrieval from instruction set managers. */
	public static TimingMetric retrieveOperatorMetric = console.timing("retrieveOperator");
	/** Timing metric for wrapped evaluation. */
	public static TimingMetric wrappedEvalMetric = console.timing("wrappedEval");

	/** The {@link ComputeContext} this operation executes within (OpenCL, Metal, JNI, etc.). */
	private ComputeContext<MemoryData> context;

	/** Evaluator responsible for preparing and processing operation arguments. */
	private ProcessArgumentEvaluator evaluator;

	/** Factory for creating {@link AcceleratedProcessDetails} instances with operation metadata. */
	private ProcessDetailsFactory detailsFactory;

	/**
	 * Manages argument mapping, aggregation, and memory allocation for kernel operations.
	 * Handles input/output buffer preparation and automatic garbage collection.
	 */
	protected MemoryDataArgumentMap argumentMap;

	/**
	 * Creates a new accelerated operation within the specified compute context.
	 *
	 * @param context The {@link ComputeContext} for compilation and execution (OpenCL, Metal, JNI, etc.)
	 */
	protected AcceleratedOperation(ComputeContext<MemoryData> context) {
		this.context = context;
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
	 * Returns the index of the output argument in the argument list.
	 *
	 * @return The output argument index
	 */
	protected abstract int getOutputArgumentIndex();

	/**
	 * Prepares the scope for compilation by creating argument mappings and input managers.
	 *
	 * <p>This method initializes the {@link MemoryDataArgumentMap} if argument mapping is enabled,
	 * and delegates to {@link #prepareScope(ArgumentProvider)} for scope-specific setup.</p>
	 *
	 * @throws UnsupportedOperationException if prepareScope has already been called
	 */
	protected void prepareScope() {
		if (argumentMap != null) {
			throw new UnsupportedOperationException("Redundant call to prepareScope");
		}

		resetArguments();

		argumentMap = MemoryDataArgumentMap.create();

		prepareScope(argumentMap);
	}

	/**
	 * Prepares the scope with the specified input manager.
	 *
	 * @param manager The {@link ArgumentProvider} for handling input registration
	 */
	protected void prepareScope(ArgumentProvider manager) {
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
			throw new OperatorPoolExhaustedException(e);
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
			process.awaitReady();
			waitFor(process.getSemaphore());
		} finally {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Dispatches this operation chaining on {@code dependsOn} (via
	 * {@link #apply(MemoryBank, Object[], Semaphore)}) and returns its completion
	 * {@link Semaphore} <em>without</em> blocking the host. This is the non-blocking
	 * counterpart to {@link #run()} (which is {@code submit(null)} followed by a wait); it
	 * lets a composite chain operations and defer the completion wait into the provider.</p>
	 */
	@Override
	public Semaphore submit(Semaphore dependsOn) {
		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			AcceleratedProcessDetails process = apply(null, new Object[0], dependsOn);

			// Ensure this operation is encoded/dispatched before returning, so a subsequent
			// chained operation is encoded after it (preserving order). The completion wait
			// itself stays deferred — that is what the returned semaphore carries.
			process.awaitReady();

			return process.getSemaphore();
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

	/**
	 * Creates a {@link MemoryReplacementManager} for managing memory aggregation and replacement
	 * during kernel execution. The manager handles input/output buffer allocation and consolidation.
	 *
	 * @return A new {@link MemoryReplacementManager} configured for this operation
	 */
	protected MemoryReplacementManager createMemoryReplacementManager() {
		return new MemoryReplacementManager(
				getComputeContext().getDataContext().getKernelMemoryProvider(),
				this::createAggregatedInput);
	}

	/**
	 * Creates and initializes the {@link ProcessDetailsFactory} for this operation.
	 *
	 * <p>The factory produces {@link AcceleratedProcessDetails} instances that contain
	 * argument metadata, memory configuration, and execution context for each operation invocation.
	 * This method is synchronized to ensure only one factory is created even under concurrent access.</p>
	 */
	protected synchronized void createDetailsFactory() {
		if (detailsFactory != null) return;

		detailsFactory = new ProcessDetailsFactory<>(
				isFixedCount(), getCount(),
				getArgumentVariables(), getOutputArgumentIndex(),
				this::createMemoryReplacementManager,
				getComputeContext()::runLater);

		if (evaluator != null) {
			detailsFactory.setEvaluator(evaluator);
		}
	}

	/**
	 * Returns the {@link ProcessDetailsFactory} for this operation, creating it if necessary.
	 *
	 * <p>The factory is lazily initialized on first access. It produces {@link AcceleratedProcessDetails}
	 * instances containing argument metadata and execution context for each operation invocation.</p>
	 *
	 * @return The process details factory
	 */
	public ProcessDetailsFactory getDetailsFactory() {
		if (detailsFactory == null) {
			createDetailsFactory();
		}

		return detailsFactory;
	}

	/**
	 * Creates {@link AcceleratedProcessDetails} for executing this operation with the specified arguments.
	 *
	 * <p>Process details contain all information needed for execution: argument arrays, memory buffers,
	 * output destination, and semaphore for synchronization. This method prepares arguments according to
	 * the operation's configuration (kernel vs JNI, aggregated vs non-aggregated).</p>
	 *
	 * @param output The destination memory bank for operation results
	 * @param args   The input arguments for the operation
	 * @return Process details ready for execution
	 */
	protected AcceleratedProcessDetails getProcessDetails(MemoryBank output, Object[] args) {
		return getDetailsFactory().init(output, args).construct();
	}

	/**
	 * Applies this operation with the specified output destination and arguments.
	 *
	 * <p>This method orchestrates the full execution lifecycle:</p>
	 * <ol>
	 *   <li>Creates process details with argument configuration</li>
	 *   <li>Sets up a semaphore for synchronization</li>
	 *   <li>Registers a completion listener that:
	 *       <ul>
	 *         <li>Loads and configures the operator</li>
	 *         <li>Runs preprocessing (if required)</li>
	 *         <li>Executes the kernel</li>
	 *         <li>Runs postprocessing (if required)</li>
	 *       </ul>
	 *   </li>
	 * </ol>
	 *
	 * @param output The destination memory bank for operation results, or null
	 * @param args   The input arguments for the operation
	 * @return Process details containing execution state and semaphore for synchronization
	 * @throws UnsupportedOperationException if the operation was not compiled
	 */
	protected AcceleratedProcessDetails apply(MemoryBank output, Object[] args) {
		return apply(output, args, null);
	}

	/**
	 * Applies this operation, chaining on a prior operation's completion.
	 *
	 * <p>Equivalent to {@link #apply(MemoryBank, Object[])} except that {@code dependsOn},
	 * when non-null, is passed to the operator so the provider can make this dispatch wait
	 * on the prior operation's completion <em>inside the provider</em> (e.g. an OpenCL
	 * {@code cl_event} wait-list) rather than blocking the host. The returned details'
	 * {@link AcceleratedProcessDetails#getSemaphore() completion semaphore} is this
	 * operation's completion, suitable for use as the next operation's {@code dependsOn}.</p>
	 *
	 * @param output    The destination memory bank for operation results, or null
	 * @param args      The input arguments for the operation
	 * @param dependsOn The prior operation's completion {@link Semaphore} to chain on, or null
	 * @return Process details containing execution state and the completion semaphore
	 * @throws UnsupportedOperationException if the operation was not compiled
	 */
	protected synchronized AcceleratedProcessDetails apply(MemoryBank output, Object[] args, Semaphore dependsOn) {
		if (getArguments() == null && getInstructionSetManager() == null) {
			throw new UnsupportedOperationException("Operation was not compiled");
		}

		// Load the inputs
		AcceleratedProcessDetails process = getProcessDetails(output, args);
		process.setReadyLatch(new DefaultLatchSemaphore(getMetadata(), 1));

		process.whenReady(() -> {
			MemoryData input[] = process.getArguments(MemoryData[]::new);

			// Prepare the operator
			Execution operator = setupOperator(process);

			// "processing" means this operation needs cross-provider memory replacement: its arguments
			// are copied into a shared memory reservation on the kernel's provider (prepare, below) and
			// the result is copied back out afterward (postprocess).
			//
			// Aggregation is the signal that this operation CANNOT be batched into the provider's
			// shared command buffer. The de-aggregation copies between memory in DIFFERENT
			// MemoryProviders — the aggregated buffer is provider-owned (e.g. a Metal command buffer's
			// memory) while the originals usually are not — so it must run only after this kernel has
			// actually completed. That forces a per-operation completion wait below (and, on Metal, a
			// per-operation command-buffer commit). This per-op commit is correct and intended; it is
			// also why a Metal command buffer that carries an aggregated op is not shared with others.
			//
			// The inverse is the case that matters for performance: when "processing" is false there
			// is NO per-op wait here, so the dispatch simply accumulates into the provider's open
			// command buffer and is committed ONCE at the genuine boundary (the OperationList runner's
			// trailing wait, or a top-level run()/evaluate()). That is where Metal command-buffer
			// batching happens. Aggregation rarely occurs for performance-critical work because those
			// buffers exceed the off-heap threshold (AR_HARDWARE_OFF_HEAP_SIZE); setting it to 0 keeps
			// every reservation provider-owned and avoids aggregation entirely.
			//
			// IMPORTANT: do NOT add a per-op wait for non-aggregated operations, and do NOT treat the
			// mere presence of an aggregated operation as a reason to stop batching the others — only
			// the aggregated operation itself pays the per-op commit.
			boolean processing = isPreprocessingRequired(process);

			// Preprocessing
			if (processing) {
				process.getPrepare().get().run();
			}

			// Run the operator, chaining on the prior operation's completion when provided
			Semaphore nextSemaphore = operator.accept(input, dependsOn);

			// Register kernel semaphore with the active heap stage so
			// that pop() waits for kernel completion before destroying memory
			Heap.addPendingKernel(nextSemaphore);

			// Adopt the operator's device-completion semaphore as the process completion so
			// callers wait on (and can chain via dependsOn) the actual kernel completion
			// rather than the host-readiness latch. When the operator returns null (fully
			// synchronous providers) the host latch remains the completion — behavior is
			// unchanged.
			process.setSemaphore(nextSemaphore);

			// Postprocessing. This per-op wait is required, NOT batching being switched off
			// arbitrarily: the de-aggregation below crosses MemoryProviders and must run after the
			// kernel completes (see the "processing" explanation above). It only applies to
			// aggregated operations; non-aggregated ones never reach here and stay batched.
			if (processing) {
				if (nextSemaphore != null) {
					// TODO  This should actually result in a new Semaphore
					// TODO  that performs the post processing whenever the
					// TODO  original semaphore is finished
					nextSemaphore.waitFor();
				}

				process.getPostprocess().get().run();
			}
		});

		return process;
	}

	/**
	 * Prepares the {@link Execution} operator for running the kernel with the specified process details.
	 *
	 * <p>This method loads the compiled kernel/native code and configures execution parameters such as
	 * global work size based on the process details. For kernel operations, it sets up the {@link KernelWork}
	 * interface with appropriate work dimensions.</p>
	 *
	 * @param process The process details containing kernel size and execution metadata
	 * @return The configured {@link Execution} operator ready to accept arguments
	 * @throws UnsupportedOperationException if the operator is not a {@link KernelWork}
	 * @throws HardwareException if the operator has been destroyed
	 */
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

	/**
	 * Determines whether preprocessing (cross-provider memory replacement) is required before kernel execution.
	 *
	 * @param process The process details to check
	 * @return true if preprocessing is required, false otherwise
	 */
	protected boolean isPreprocessingRequired(AcceleratedProcessDetails process) {
		return !process.isEmpty();
	}

	/**
	 * Destroys this operation and releases associated resources.
	 *
	 * <p>Calls the parent destroy method and cleans up the argument map.</p>
	 */
	@Override
	public void destroy() {
		super.destroy();

		if (argumentMap != null) {
			argumentMap.destroy();
		}
	}

	/** Returns the console for logging operations. */
	@Override
	public Console console() { return console; }


	/** Prints timing statistics. */
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
