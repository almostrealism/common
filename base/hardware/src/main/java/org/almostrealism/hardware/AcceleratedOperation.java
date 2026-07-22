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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.OperationSemaphore;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.Argument;
import org.almostrealism.nio.NativeMemoryProvider;
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
 *     // Creates MemoryDataArgumentMap bound to this operation's ComputeContext
 *     argumentMap = MemoryDataArgumentMap.create(getComputeContext(), aggregateGenerator);
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
 *     return getComputeContext().getKernelManager();  // The context this operation was created with
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
 *     public VectorAddOperation(Computation<Void> computation) {
 *         // The Computer selects the context from the computation's characteristics;
 *         // never reach for the ambient/default context to make this choice
 *         super(Hardware.getLocalHardware().getComputer().getContext(computation), computation);
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
							implements Runnable, Submittable, ScopeLifecycle, Countable, Aggregatable, HardwareFeatures {

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
	 * <p>Aggregated inputs combine multiple separate small inputs into a single contiguous
	 * buffer, reducing the kernel's argument count. The buffer is allocated on the kernel's
	 * target memory provider ({@link io.almostrealism.code.DataContext#getKernelMemoryProvider()})
	 * so that {@link org.almostrealism.hardware.mem.MemoryReplacementManager} leaves it in place
	 * — that manager only reserves arguments not already on the target provider — avoiding a
	 * redundant copy of the aggregate into a per-op reservation temp around every aggregated
	 * kernel. (The previous {@code deviceMemory()} allocation routed small buffers to a different,
	 * size-selected provider, which forced that redundant reservation under the auto-select
	 * compute context.)</p>
	 *
	 * @param memLength Total element count of the aggregate buffer
	 * @param atomicLength Atomic element size; expected equal to {@code memLength} for the aggregate
	 * @return Aggregated input memory allocated on the kernel's target provider
	 */
	public MemoryData createAggregatedInput(int memLength, int atomicLength) {
		// The aggregate is a flat buffer; all callers pass atomicLength == memLength.
		if (atomicLength != memLength) {
			throw new IllegalArgumentException("Aggregate atomicLength must equal memLength");
		}

		// Allocate on the kernel's target provider so MemoryReplacementManager leaves it in place
		// (no reservation temp); the kernel reads/writes it directly. See method javadoc.
		return Bytes.of(getComputeContext().getDataContext()
				.getKernelMemoryProvider().allocate(memLength), memLength);
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

		// Provide an aggregate-buffer factory so that small input arguments can be folded into
		// a single kernel argument (keeping the kernel's argument count under the compute
		// context's limit). Eligibility is decided per argument by size inside the map. Operations
		// that opt out (see isArgumentAggregationSupported) get a map with no aggregation.
		argumentMap = MemoryDataArgumentMap.create(getComputeContext(),
				isArgumentAggregationSupported() ? i -> createAggregatedInput(i, i) : null);

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
			throw new HardwareException("Could not obtain operator", e);
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
		return new MemoryReplacementManager(getComputeContext(),
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
		return getProcessDetails(output, args, null);
	}

	/**
	 * Creates process details with argument configuration, ordering every argument
	 * evaluation after the given completion.
	 *
	 * <p>Arguments backed by a hardware dispatch chain the dependency through the
	 * provider; arguments evaluated on the host wait for the completion on their
	 * evaluation thread before reading. This guarantees that argument preparation
	 * never observes memory from before the work this operation depends on.</p>
	 *
	 * @param output    The destination memory bank for operation results
	 * @param args      The input arguments for the operation
	 * @param dependsOn completion that must fire before argument evaluation reads
	 *                  memory, or {@code null} when there is no dependency
	 * @return Process details ready for execution
	 */
	protected AcceleratedProcessDetails getProcessDetails(MemoryBank output, Object[] args, Semaphore dependsOn) {
		return getDetailsFactory().construct(output, args, dependsOn);
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
	 * operation's completion, suitable for use as the next operation's {@code dependsOn}.
	 * Completions of asynchronously produced arguments (delivered via
	 * {@link AcceleratedProcessDetails#result(int, Object, Semaphore)}) are merged with
	 * {@code dependsOn} through {@link OperationSemaphore#all}, so the kernel is ordered after the
	 * work producing its inputs the same way.</p>
	 *
	 * <p><strong>Execution model.</strong> Two independent memory mechanisms may wrap
	 * the kernel, and they unwind in reverse order of how they are set up. Every copy on both
	 * sides is a {@link Submittable} over the operation's
	 * {@link io.almostrealism.code.ComputeContext#copy(Object, Object, Semaphore) ComputeContext copy},
	 * chained on the completion it must be ordered after — nothing in this method blocks the
	 * host; the operation's completion semaphore is the tail of the chain, waited only at a
	 * genuine boundary (the {@code OperationList} runner's trailing wait, or a top-level
	 * {@code run()}/{@code evaluate()}):</p>
	 * <ul>
	 *   <li><strong>Cross-provider replacement</strong> (active when {@code !process.isEmpty()};
	 *   managed by {@link org.almostrealism.hardware.mem.MemoryReplacementManager}). Arguments not
	 *   already on the kernel's provider are reserved into a provider-owned temp:
	 *   {@code process.getPrepareOperations()} copies them in before the kernel (chained ahead of
	 *   it), {@code process.getPostprocessOperations()} copies the results back chained on the
	 *   kernel's completion. A context with no asynchronous copy mechanism waits for the kernel
	 *   inside the copy's submit, so behavior degrades to the previous per-operation wait only
	 *   where the hardware cannot do better.</li>
	 *   <li><strong>Compile-time argument aggregation</strong> (active when the argument map has
	 *   replacements; managed by {@link MemoryDataArgumentMap}). Small inputs are folded into one
	 *   aggregate kernel buffer; their data is copied in before the kernel. Whether each slice is
	 *   copied back afterward follows the side-effect policy: {@code output == null} copies every
	 *   slice back; {@code output != null} (default) copies none (the caller's explicit output is
	 *   taken to be the only result of interest); {@code output != null} with
	 *   {@link MemoryDataArgumentMap#enableStrictSideEffects strict side-effects} copies back every
	 *   slice except the one aliasing {@code output} (so an in-place {@code x = x + y} is not
	 *   overwritten by the stale read copy of {@code x}).</li>
	 * </ul>
	 *
	 * <p>When both apply, the unwind order is correctness-critical: the replacement's
	 * {@code postprocess} (temp&rarr;aggregate) must run BEFORE aggregation's de-aggregation
	 * (aggregate&rarr;originals), otherwise the de-aggregation reads a stale aggregate and the
	 * result reads as zero.</p>
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

		// Load the inputs, ordering argument evaluation after the prior completion
		AcceleratedProcessDetails process = getProcessDetails(output, args, dependsOn);
		process.setReadyLatch(new DefaultLatchSemaphore(getMetadata(), 1));

		// Requirements are thread-local, and the listener below may run on another
		// thread when dispatch is asynchronous; capture them here to re-establish there
		List<ComputeRequirement> activeRequirements =
				Hardware.getLocalHardware().getComputer().getActiveRequirements();

		process.whenReady(() -> {
			if (!activeRequirements.isEmpty()) {
				Hardware.getLocalHardware().getComputer().pushRequirements(activeRequirements);
			}

			try {
				MemoryData input[] = process.getArguments(MemoryData[]::new);

				// Prepare the operator
				Execution operator = setupOperator(process);

				boolean aggregating = argumentMap != null && argumentMap.hasReplacements();
				boolean aggregateCopyOut = aggregating
						&& (output == null || MemoryDataArgumentMap.enableStrictSideEffects);
				boolean processing = !process.isEmpty();

				// Copy-in groups chain on one another, and the kernel chains on the last of them.
				// Arguments delivered asynchronously with an outstanding completion (see
				// AcceleratedProcessDetails.getArgumentCompletions()) are merged in here, so the
				// kernel is ordered after the work producing them without any host wait.
				List<Semaphore> pending = process.getArgumentCompletions();
				pending.add(dependsOn);
				Semaphore ready = OperationSemaphore.all(getMetadata(), pending);

				if (aggregating) {
					Semaphore prepared = Submittable.submit(argumentMap.getPrepareOperations(), ready);
					if (prepared != null) ready = prepared;
				}

				if (processing) {
					Semaphore prepared = Submittable.submit(process.getPrepareOperations(), ready);
					if (prepared != null) ready = prepared;
				}

				// Run the operator, chaining on the last copy-in (or the caller's prior completion).
				Semaphore nextSemaphore = operator.accept(input, ready);

				// Register kernel semaphore with the active heap stage so
				// that pop() waits for kernel completion before destroying memory
				Heap.addPendingKernel(nextSemaphore);

				Semaphore completion = nextSemaphore;

				// Copy-out unwinds in reverse order; see the method javadoc
				if (processing) {
					Semaphore copyBack = Submittable.submit(process.getPostprocessOperations(), completion);
					if (copyBack != null) completion = copyBack;
				}

				if (aggregateCopyOut) {
					Semaphore copyOut = Submittable.submit(output == null ?
							argumentMap.getPostprocessOperations(null) :
							argumentMap.getPostprocessOperations((MemoryData) output), completion);
					if (copyOut != null) {
						completion = copyOut;
					}
				}

				// Adopt the final completion (the kernel, or the de-aggregation copy-out when present) as
				// the process completion so callers wait on (and can chain via dependsOn) the actual end
				// of the operation rather than the host-readiness latch. When the operator returns null
				// (fully synchronous providers) the host latch remains the completion — behavior is
				// unchanged.
				process.setSemaphore(completion);

				if (completion != null && completion != nextSemaphore) {
					// The trailing copy-out runs after the kernel, so heap lifecycle must wait for it too.
					Heap.addPendingKernel(completion);
				}

				if (process.hasDestinationLeases()) {
					// Release at the end of the full chain, passively — an actively waiting
					// callback (onComplete) forces a per-invocation commit on Metal.
					if (completion == null) {
						process.releaseDestinationLeases();
					} else {
						completion.whenComplete(process::releaseDestinationLeases);
					}
				}
			} finally {
				if (!activeRequirements.isEmpty()) {
					Hardware.getLocalHardware().getComputer().popRequirements();
				}
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
	 * Destroys this operation and releases associated resources.
	 *
	 * <p>Calls the parent destroy method and cleans up the argument map and the
	 * details factory's destination reuse slots.</p>
	 */
	@Override
	public void destroy() {
		super.destroy();

		if (argumentMap != null) {
			argumentMap.destroy();
		}

		if (detailsFactory != null) {
			detailsFactory.destroy();
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
