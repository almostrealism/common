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

import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.concurrent.CompletionConsumer;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import io.almostrealism.streams.StreamingEvaluable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.arguments.ProcessArgumentEvaluator;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.MemoryDataDestination;
import org.almostrealism.hardware.mem.MemoryReplacementManager;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Factory for creating {@link AcceleratedProcessDetails} instances with intelligent kernel size inference and argument evaluation.
 *
 * <p>{@link ProcessDetailsFactory} is the critical coordination point for preparing hardware accelerated operations.
 * It analyzes operation arguments, infers the required parallel execution size (kernel size), evaluates intermediate
 * {@link Producer} arguments asynchronously, and constructs {@link AcceleratedProcessDetails} that coordinate the
 * entire execution pipeline.</p>
 *
 * <h2>Core Responsibilities</h2>
 *
 * <ul>
 *   <li><b>Kernel Size Inference:</b> Determines parallel work size from output, arguments, or operation count</li>
 *   <li><b>Argument Evaluation:</b> Evaluates {@link Producer} arguments to {@link MemoryData} before kernel execution</li>
 *   <li><b>Async Coordination:</b> Manages asynchronous evaluation of dependent computations</li>
 *   <li><b>Constant Caching:</b> Caches results of constant {@link Evaluable} arguments</li>
 *   <li><b>Memory Management:</b> Integrates with {@link Heap} for temporary allocations</li>
 * </ul>
 *
 * <h2>Kernel Size Inference Strategy</h2>
 *
 * <p>Kernel size (parallel work items) is inferred using this priority order:</p>
 * <pre>
 * 1. Fixed Count: If operation has fixed count, use that
 * 2. Output Count: If output provided, use output.getCountLong()
 * 3. Argument Reference: If arg references an evaluable arg, use its count
 * 4. First Argument: If first arg is MemoryBank with count > operation count, use that
 * 5. Operation Count: Fall back to operation's declared count
 * </pre>
 *
 * <p>Example of kernel size inference:</p>
 * <pre>{@code
 * // Operation declares count = 1, but output requires 1000
 * Producer<PackedCollection> op = ...; // count = 1
 * PackedCollection output = PackedCollection.create(1000);
 *
 * // ProcessDetailsFactory infers kernel size = 1000 from output
 * ProcessDetailsFactory factory = ...;
 * factory.init(output, args);
 * AcceleratedProcessDetails details = factory.construct();
 * assert details.getKernelSize() == 1000;
 * }</pre>
 *
 * <h2>Argument Evaluation Pipeline</h2>
 *
 * <p>Arguments pass through a three-phase evaluation:</p>
 * <pre>
 * Phase 1: Identify Direct References
 *   - Arguments that reference evaluable args (via ProducerArgumentReference)
 *   - These use the referenced memory directly
 *
 * Phase 2: Evaluate Constants
 *   - If enableConstantCache=true, evaluate constant Evaluables immediately
 *   - Cache results for reuse
 *
 * Phase 3: Async Evaluation
 *   - Create StreamingEvaluable for each non-constant argument
 *   - Set up async pipeline to deliver results to AcceleratedProcessDetails
 *   - Initiate evaluation via request()
 * </pre>
 *
 * <p>Example showing argument evaluation:</p>
 * <pre>{@code
 * // Argument 0: Direct reference to input
 * // Argument 1: Constant producer (cached)
 * // Argument 2: Non-constant producer (async evaluated)
 *
 * ProcessDetailsFactory factory = new ProcessDetailsFactory(...);
 * factory.init(output, new Object[] { input });
 *
 * // construct() evaluates arguments asynchronously
 * AcceleratedProcessDetails details = factory.construct();
 *
 * // When ready, all arguments are MemoryData
 * details.whenReady(() -> {
 *     MemoryData[] args = details.getArguments(MemoryData[]::new);
 *     // Execute kernel with prepared arguments
 * });
 * }</pre>
 *
 * <h2>Constant Caching</h2>
 *
 * <p>When {@link #enableConstantCache} is true (default), constant {@link Evaluable} arguments
 * are evaluated once and cached:</p>
 * <pre>{@code
 * // Enable constant caching (default)
 * ProcessDetailsFactory.enableConstantCache = true;
 *
 * // Constant argument
 * Producer<PackedCollection> constant = c(42.0);
 * Evaluable<PackedCollection> eval = constant.get();
 *
 * // First execution: evaluates and caches
 * factory1.init(output, args).construct();  // Evaluates 42.0, caches result
 *
 * // Second execution: reuses cached result
 * factory2.init(output, args).construct();  // Uses cached 42.0
 * }</pre>
 *
 * <h2>Asynchronous Execution</h2>
 *
 * <p>When {@link Hardware#isAsync()} returns true, argument evaluation happens asynchronously:</p>
 * <pre>{@code
 * // Enable async mode
 * Hardware.enableAsync = true;
 *
 * // Complex argument requiring computation
 * Producer<PackedCollection> complexArg = input.multiply(weights).add(bias);
 *
 * ProcessDetailsFactory factory = ...;
 * AcceleratedProcessDetails details = factory.construct();
 *
 * // complexArg is being evaluated on executor thread
 * // details.whenReady() called when all args ready
 * details.whenReady(() -> {
 *     // All arguments evaluated, kernel can execute
 *     kernel.accept(details.getArguments(...), null);
 * });
 * }</pre>
 *
 * <h2>Configuration Options</h2>
 *
 * <p>Controlled via static flags and environment variables:</p>
 * <ul>
 *   <li><b>{@link #enableConstantCache}:</b> Cache constant argument evaluations (default: true, AR_HARDWARE_CONSTANT_CACHE)</li>
 *   <li><b>{@link #enableArgumentKernelSize}:</b> Infer kernel size from first argument (default: true)</li>
 *   <li><b>{@link #enableArgumentReferenceKernelSize}:</b> Infer from referenced arguments (default: true)</li>
 *   <li><b>{@link #enableOutputCount}:</b> Use output count for kernel size (default: true)</li>
 *   <li><b>{@link #enableKernelSizeWarnings}:</b> Warn about kernel size changes (default: false, AR_HARDWARE_KERNEL_SIZE_WARNINGS)</li>
 * </ul>
 *
 * <h2>Integration with AcceleratedOperation</h2>
 *
 * <p>{@link AcceleratedOperation} uses {@link ProcessDetailsFactory} via getDetailsFactory():</p>
 * <pre>{@code
 * public class MyAcceleratedOperation extends AcceleratedOperation {
 *     @Override
 *     protected AcceleratedProcessDetails apply(MemoryBank output, Object[] args) {
 *         // Get factory (created in createDetailsFactory()), prepare the
 *         // arguments, and construct process details (evaluates arguments async)
 *         return getDetailsFactory().construct(output, args, null);
 *     }
 * }
 * }</pre>
 *
 * <h2>Custom Argument Evaluation</h2>
 *
 * <p>Custom {@link ProcessArgumentEvaluator} can be provided via {@link #setEvaluator}:</p>
 * <pre>{@code
 * ProcessDetailsFactory factory = ...;
 *
 * // Custom evaluator for specialized argument handling
 * factory.setEvaluator(new ProcessArgumentEvaluator() {
 *     @Override
 *     public <T> Evaluable<? extends Multiple<T>> getEvaluable(ArrayVariable<T> argument) {
 *         // Custom logic to obtain evaluable
 *         return customEvaluableFor(argument);
 *     }
 * });
 * }</pre>
 *
 * <h2>Memory Management</h2>
 *
 * <p>Temporary allocations for argument evaluation are tracked via {@link Heap}:</p>
 * <pre>{@code
 * // During construct():
 * MemoryData result = kernelArgEvaluables[i].createDestination(size);
 * Heap.addCreatedMemory(result);  // Track for automatic cleanup
 *
 * // Heap automatically destroys temporary allocations when scope exits
 * }</pre>
 *
 * @param <T> The type of array elements
 * @see AcceleratedProcessDetails
 * @see AcceleratedOperation
 * @see ProcessArgumentEvaluator
 * @see MemoryReplacementManager
 */
public class ProcessDetailsFactory<T> implements Factory<AcceleratedProcessDetails>, Countable, ConsoleFeatures {
	/** If true, infer kernel size from the first MemoryBank argument when not derivable from output. Pending removal. */
	public static boolean enableArgumentKernelSize = true;
	/** If true, infer kernel size from arguments that reference a ProducerArgumentReference. Pending removal. */
	public static boolean enableArgumentReferenceKernelSize = true;

	/** If true, use the output count to determine the kernel size rather than the declared count. */
	public static boolean enableOutputCount = true;

	/** If true, constant kernel arguments are evaluated once and cached across invocations. Controlled by {@code AR_HARDWARE_CONSTANT_CACHE}. */
	public static boolean enableConstantCache =
			SystemUtils.isEnabled("AR_HARDWARE_CONSTANT_CACHE").orElse(true);
	/** If true, emit a warning when kernel size differs from the declared count. Controlled by {@code AR_HARDWARE_KERNEL_SIZE_WARNINGS}. */
	public static boolean enableKernelSizeWarnings =
			SystemUtils.isEnabled("AR_HARDWARE_KERNEL_SIZE_WARNINGS").orElse(false);

	/**
	 * If true, sized argument destinations are reused across invocations via per-argument
	 * {@link DestinationSlot}s, gated on each invocation's completion chain instead of the
	 * thread identity the former {@code ThreadLocal} provider relied on (which stopped
	 * implying exclusive use once argument evaluation became asynchronous). Controlled by
	 * {@code AR_HARDWARE_DESTINATION_REUSE}.
	 */
	public static boolean enableDestinationReuse =
			SystemUtils.isEnabled("AR_HARDWARE_DESTINATION_REUSE").orElse(true);

	/** True if the count is fixed at construction time and cannot be inferred from arguments. */
	private boolean fixedCount;
	/** Declared number of parallel work items (kernel size) for fixed-count operations. */
	private int count;

	/** Evaluates {@link ArrayVariable} producers into {@link Evaluable} instances for kernel dispatch. */
	private ProcessArgumentEvaluator evaluator;
	/** Ordered list of array variables representing kernel arguments, including output. */
	private List<ArrayVariable<? extends T>> arguments;
	/** Index of the output argument in {@link #arguments}; negative if no output is used. */
	private int outputArgIndex;

	/** Supplier of the memory replacement manager, used to redirect memory from one provider to another. */
	private Supplier<MemoryReplacementManager> replacements;

	/**
	 * The most recently prepared argument snapshot, reused when {@link #init}
	 * is called again with the same output and argument identities.
	 *
	 * <p>Only ever assigned a fully constructed {@link PreparedArguments}
	 * instance, as the last step of preparation. A preparation that fails
	 * partway through leaves the previous (complete) snapshot in place, so
	 * no caller can observe argument state that is only partially resolved.</p>
	 */
	private PreparedArguments prepared;

	/** Executor used to dispatch asynchronous kernel execution when {@link Hardware#isAsync()} is true. */
	private Executor executor;

	/**
	 * Per-argument destination reuse slots, indexed like {@link #arguments}. Lazily
	 * created on first use when {@link #enableDestinationReuse} is active.
	 */
	private DestinationSlot[] destinationSlots;

	/**
	 * Constructs a factory for producing {@link AcceleratedProcessDetails} instances.
	 *
	 * @param fixedCount True if the kernel size is fixed at construction time
	 * @param count Declared number of parallel work items (used when fixedCount is true)
	 * @param arguments Ordered list of array variables representing kernel arguments
	 * @param outputArgIndex Index of the output argument in the arguments list; negative if no output
	 * @param replacements Supplier of the memory replacement manager
	 * @param executor Executor for asynchronous kernel dispatch
	 */
	public ProcessDetailsFactory(boolean fixedCount, int count,
								 List<ArrayVariable<? extends T>> arguments,
								 int outputArgIndex,
								 Supplier<MemoryReplacementManager> replacements,
								 Executor executor) {
		if (arguments == null) {
			throw new IllegalArgumentException();
		}

		this.fixedCount = fixedCount;
		this.count = count;

		this.evaluator = new ProcessArgumentEvaluator() {
			@Override
			public <T> Evaluable<? extends Multiple<T>> getEvaluable(ArrayVariable<T> argument) {
				return argument.getProducer().get();
			}
		};

		this.arguments = arguments;
		this.outputArgIndex = outputArgIndex;

		this.replacements = replacements;
		this.executor = executor;
	}

	@Override
	public boolean isFixedCount() { return fixedCount; }
	@Override
	public long getCountLong() { return count; }

	public ProcessArgumentEvaluator getEvaluator() { return evaluator; }
	public void setEvaluator(ProcessArgumentEvaluator evaluator) { this.evaluator = evaluator; }

	/**
	 * Initializes this factory with an output bank and positional arguments for the next invocation.
	 *
	 * <p>Resolves kernel size, evaluates constant arguments, and prepares evaluables for
	 * dynamic arguments. If the output and args are identical to those from the last call,
	 * the factory is considered already initialized and returns immediately.</p>
	 *
	 * @param output Output {@link MemoryBank} to write results into; may be null for operations without output
	 * @param args Positional arguments passed at evaluation time
	 * @return This factory instance for chaining
	 */
	public ProcessDetailsFactory init(MemoryBank output, Object args[]) {
		prepare(output, args);
		return this;
	}

	/**
	 * Prepares (or reuses) the argument snapshot for the given output and arguments.
	 *
	 * <p>The snapshot is built entirely in local state and only published to
	 * {@link #prepared} once every argument slot has been resolved. A failure
	 * during preparation (for example, a kernel compilation error raised while
	 * obtaining an argument's {@link Evaluable}) therefore leaves the previous
	 * snapshot untouched, and a reentrant invocation triggered by evaluating a
	 * constant argument can never observe a partially populated snapshot.</p>
	 *
	 * @param output Output {@link MemoryBank} to write results into; may be null for operations without output
	 * @param args Positional arguments passed at evaluation time
	 * @return The fully prepared argument snapshot for this combination of output and arguments
	 */
	protected PreparedArguments prepare(MemoryBank output, Object args[]) {
		PreparedArguments existing = prepared;
		if (existing != null && existing.matches(output, args)) {
			// The prepared snapshot is already valid and does not need to be rebuilt
			return existing;
		}

		if (outputArgIndex < 0 && output != null) {
			// There is no output for this process
			throw new UnsupportedOperationException();
		}

		boolean allMemoryData = args.length <= 0 || Stream.of(args).filter(a -> !(a instanceof MemoryData)).findAny().isEmpty();

		long kernelSize;

		if (isFixedCount()) {
			kernelSize = getCount();

			if (output != null) {
				long dc = output.getCountLong();
				boolean matchCount = List.of(1L, getCountLong()).contains(dc);
				boolean matchTotal = getCountLong() == output.getMemLength();

				if (!matchCount && !matchTotal) {
					throw new IllegalArgumentException("The destination count (" + dc +
							") must match the count for the process (" + kernelSize + "), unless the count " +
							"for the process is identical to the total size of the output (" +
							output.getMemLength() + ")");
				}
			}
		} else if (output != null) {
			kernelSize = enableOutputCount ? output.getCountLong() : Math.max(output.getCountLong(), getCountLong());

			if (enableKernelSizeWarnings && getCountLong() > 1 && kernelSize != getCountLong()) {
				warn("Operation count was reduced from " + getCountLong() +
						" to " + kernelSize + " to match the output count");
			}
		} else if (enableArgumentKernelSize && args.length > 0 && allMemoryData && ((MemoryBank) args[0]).getCountLong() > getCount()) {
			if (enableKernelSizeWarnings)
				warn("Relying on argument count to determine kernel size");

			kernelSize = ((MemoryBank) args[0]).getCountLong();
		} else {
			kernelSize = -1;
		}

		MemoryData kernelArgs[] = new MemoryData[arguments.size()];
		Evaluable kernelArgEvaluables[] = new Evaluable[arguments.size()];

		/*
		 * In the first pass, kernel size is inferred from Producer arguments that
		 * reference an Evaluable argument.
		 */
		i:
		for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i) == null) {
				continue i;
			} else if (i == outputArgIndex && output != null) {
				kernelArgs[i] = output;
				continue i;
			}

			int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));

			if (refIndex >= 0) {
				kernelArgs[i] = (MemoryData) args[refIndex];
			}

			if (kernelSize > 0) continue i;

			// If the kernel size can be inferred from this operation argument
			// capture it from the argument to the evaluation
			if (enableArgumentReferenceKernelSize && kernelArgs[i] instanceof MemoryBank && ((MemoryBank<?>) kernelArgs[i]).getCountLong() > 1) {
				kernelSize = ((MemoryBank<?>) kernelArgs[i]).getCountLong();
			}
		}

		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			kernelArgEvaluables[i] = getEvaluator().getEvaluable(arguments.get(i));
			if (kernelArgEvaluables[i] == null) {
				throw new UnsupportedOperationException();
			}

			if (enableConstantCache && kernelSize > 0 && kernelArgEvaluables[i].isConstant()) {
				kernelArgs[i] = (MemoryData) kernelArgEvaluables[i].evaluate(args);
			}
		}

		/*
		 * If the kernel size is still not known, the kernel size will be the count.
		 */
		if (kernelSize < 0) {
			if (enableKernelSizeWarnings)
				warn("Could not infer kernel size, it will be set to " + getCount());
			kernelSize = getCount();
		}

		PreparedArguments result = new PreparedArguments(output, args, kernelSize, kernelArgs, kernelArgEvaluables);
		prepared = result;
		return result;
	}

	/**
	 * Clears the prepared argument snapshot so the next {@link #init} call fully re-evaluates all arguments.
	 *
	 * <p>Call this when argument producers may have changed since the last {@link #init} call.</p>
	 */
	public void reset() {
		this.prepared = null;
	}

	/**
	 * Constructs an {@link AcceleratedProcessDetails} by resolving kernel arguments,
	 * creating async evaluables for arguments that require evaluation, and wiring
	 * downstream result consumers.
	 *
	 * @return the fully configured process details ready for execution
	 */
	@Override
	public AcceleratedProcessDetails construct() {
		return construct((Semaphore) null);
	}

	/**
	 * Constructs an {@link AcceleratedProcessDetails} from the most recently
	 * prepared arguments, ordering dispatch-backed argument evaluations after
	 * the given completion.
	 *
	 * @param dependsOn completion that dispatch-backed argument evaluations must chain
	 *                  on, or {@code null} when there is no dependency
	 * @return the fully configured process details ready for execution
	 * @throws IllegalStateException if no arguments have been prepared via {@link #init}
	 */
	public AcceleratedProcessDetails construct(Semaphore dependsOn) {
		PreparedArguments current = prepared;

		if (current == null) {
			throw new IllegalStateException("No arguments have been prepared");
		}

		return construct(current, dependsOn);
	}

	/**
	 * Prepares the given output and arguments, then constructs an
	 * {@link AcceleratedProcessDetails} from the resulting snapshot.
	 *
	 * <p>This is the preferred entry point for a complete invocation: the
	 * snapshot produced by preparation is carried directly into construction,
	 * so an invocation that (through argument evaluation) reaches this same
	 * factory again cannot substitute its own arguments into this one.</p>
	 *
	 * @param output Output {@link MemoryBank} to write results into; may be null for operations without output
	 * @param args Positional arguments passed at evaluation time
	 * @param dependsOn completion that dispatch-backed argument evaluations must chain
	 *                  on, or {@code null} when there is no dependency
	 * @return the fully configured process details ready for execution
	 */
	public AcceleratedProcessDetails construct(MemoryBank output, Object args[], Semaphore dependsOn) {
		return construct(prepare(output, args), dependsOn);
	}

	/**
	 * Constructs an {@link AcceleratedProcessDetails} from the given argument snapshot,
	 * ordering dispatch-backed argument evaluations after the given completion.
	 *
	 * <p>Each argument's {@link StreamingEvaluable} is requested with {@code dependsOn}.
	 * An argument whose evaluation is itself a hardware dispatch chains the dependency
	 * through the provider, so an argument kernel never reads memory written by the work
	 * {@code dependsOn} represents before that work has completed — without any host wait.
	 * Plain host evaluables are handle-producers (they return {@link MemoryData} handles;
	 * the kernel reads the contents on the device, ordered by its own chained dispatch)
	 * and disregard {@code dependsOn}, evaluating immediately: blocking them on it would
	 * violate the non-blocking submission contract (a submit with an outstanding foreign
	 * dependency must return, and a same-provider dependency must remain free), so a
	 * host function that reads memory <em>contents</em> rather than returning a handle
	 * is responsible for its own ordering.</p>
	 *
	 * <p>All working state lives in locals of this method, so overlapping
	 * constructions (whether from another thread or from an argument evaluation
	 * that reenters this factory) each operate on their own state and deliver
	 * results to their own {@link AcceleratedProcessDetails}.</p>
	 *
	 * @param prepared the argument snapshot to construct from
	 * @param dependsOn completion that dispatch-backed argument evaluations must chain
	 *                  on, or {@code null} when there is no dependency
	 * @return the fully configured process details ready for execution
	 */
	protected AcceleratedProcessDetails construct(PreparedArguments prepared, Semaphore dependsOn) {
		Evaluable kernelArgEvaluables[] = prepared.kernelArgEvaluables;
		MemoryData kernelArgs[] = new MemoryData[arguments.size()];

		for (int i = 0; i < kernelArgs.length; i++) {
			if (prepared.kernelArgs[i] != null) kernelArgs[i] = prepared.kernelArgs[i];
		}

		/*
		 * Fresh StreamingEvaluable instances are created for each construction,
		 * so that each has a downstream consumer pointing at the specific
		 * AcceleratedProcessDetails produced here. A reused StreamingEvaluable
		 * would throw UnsupportedOperationException when trying to set a
		 * different downstream consumer.
		 */
		StreamingEvaluable asyncEvaluables[] = new StreamingEvaluable[arguments.size()];

		/*
		 * First pass: determine which arguments need async evaluation and create
		 * their StreamingEvaluable instances. We don't set downstream yet because
		 * we need to create the AcceleratedProcessDetails first.
		 */
		boolean[] evaluateAhead = new boolean[arguments.size()];

		i: for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null) continue i;

			// Determine if the argument can be evaluated immediately,
			// or if its evaluation may actually depend on the kernel
			// size and hence it needs to be evaluated later using
			// Evaluable::into to target a destination of the correct size
			if (kernelArgEvaluables[i] instanceof HardwareEvaluable) {
				// There is no need to attempt kernel evaluation if
				// HardwareEvaluable will not support it
				evaluateAhead[i] = !((HardwareEvaluable<?>) kernelArgEvaluables[i]).isKernel();
			} else if (kernelArgEvaluables[i] instanceof MemoryDataDestination) {
				// Kernel evaluation is not necessary, but it is preferable to
				// leverage MemoryDataDestination::createDestination anyway
				evaluateAhead[i] = false;
			} else {
				// Kernel evaluation will not be necessary, and Evaluable::evaluate
				// can be directly invoked without creating a correctly sized
				// destination
				evaluateAhead[i] = true;
			}

			if (evaluateAhead[i]) {
				if (!Hardware.getLocalHardware().isAsync() ||
						kernelArgEvaluables[i] instanceof DestinationEvaluable<?> ||
						kernelArgEvaluables[i] instanceof HardwareEvaluable) {
					asyncEvaluables[i] = kernelArgEvaluables[i].async(this::execute);
				} else {
					asyncEvaluables[i] = kernelArgEvaluables[i].async();
				}
			}
		}

		int size = Math.toIntExact(prepared.kernelSize);

		/*
		 * Second pass: create async evaluables for kernel arguments that need
		 * sized destinations. Destinations come from this factory's per-argument
		 * reuse slots when one is free; a slot that is still leased to an
		 * overlapping invocation falls back to a fresh allocation, which is
		 * tracked by the active Heap stage exactly as before.
		 */
		List<Runnable> leases = null;

		for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null || asyncEvaluables[i] != null) continue;

			MemoryData result = null;

			if (enableDestinationReuse) {
				DestinationSlot slot = destinationSlot(i);
				Evaluable allocator = kernelArgEvaluables[i];
				result = slot.acquire(size,
						s -> (MemoryData) allocator.createDestination(s));

				if (result != null) {
					if (leases == null) leases = new ArrayList<>();
					leases.add(slot::release);
				}
			}

			if (result == null) {
				result = (MemoryData) kernelArgEvaluables[i].createDestination(size);
				Heap.addCreatedMemory(result);
			}

			asyncEvaluables[i] = kernelArgEvaluables[i].into(result).async(this::execute);
		}

		/*
		 * Create AcceleratedProcessDetails BEFORE setting downstream on async evaluables.
		 * This ensures that the downstream lambdas capture the specific details instance
		 * produced by this construction.
		 */
		AcceleratedProcessDetails details = new AcceleratedProcessDetails(kernelArgs, size,
											replacements.get(), executor);

		if (leases != null) {
			leases.forEach(details::addDestinationLease);
		}

		/* Set downstream on all async evaluables, passing the specific details instance */
		for (int i = 0; i < asyncEvaluables.length; i++) {
			if (asyncEvaluables[i] == null || kernelArgs[i] != null) continue;
			asyncEvaluables[i].setDownstream(result(i, details));
		}

		/*
		 * Now that every StreamingEvaluable is configured to deliver
		 * results to the new AcceleratedProcessDetails, their work
		 * can be initiated via StreamingEvaluable#request
		 */
		for (int i = 0; i < asyncEvaluables.length; i++) {
			if (asyncEvaluables[i] == null || kernelArgs[i] != null) continue;

			asyncEvaluables[i].request(prepared.args, dependsOn);
		}

		/* The details are ready */
		return details;
	}

	/**
	 * Creates a result consumer for the specified argument index that delivers to
	 * the given {@link AcceleratedProcessDetails} instance.
	 *
	 * <p>This method captures the specific details instance in the returned lambda,
	 * ensuring that async results are delivered to the correct details even when
	 * multiple constructions overlap.</p>
	 *
	 * <p>The returned consumer is a {@link CompletionConsumer}, so a producer that issues
	 * its work asynchronously can deliver the argument together with the {@link
	 * io.almostrealism.streams.Semaphore} for its completion instead of blocking the
	 * host until the argument's contents are valid. The completion is recorded via
	 * {@link AcceleratedProcessDetails#result(int, Object, io.almostrealism.streams.Semaphore)}
	 * and merged into the kernel dispatch's {@code dependsOn}.</p>
	 *
	 * @param index the argument index
	 * @param targetDetails the specific details instance to deliver results to
	 * @return a consumer that delivers results to the target details
	 */
	protected Consumer<Object> result(int index, AcceleratedProcessDetails targetDetails) {
		return (CompletionConsumer<Object>)
				(result, completion) -> targetDetails.result(index, result, completion);
	}

	/**
	 * Executes a runnable either asynchronously via the executor or synchronously on the calling thread.
	 *
	 * <p>When {@link Hardware#isAsync()} is true, the runnable is submitted to the configured executor.
	 * Otherwise it is run directly on the calling thread.</p>
	 *
	 * @param r The runnable to execute
	 */
	protected void execute(Runnable r) {
		if (Hardware.getLocalHardware().isAsync()) {
			executor.execute(r);
		} else {
			r.run();
		}
	}

	/**
	 * Immutable snapshot of the argument state prepared for one combination of
	 * output and positional arguments.
	 *
	 * <p>An instance is only created once every argument slot has been fully
	 * resolved, so any consumer can rely on the invariant that each slot holds
	 * either a pre-resolved {@link MemoryData} in {@link #kernelArgs} or an
	 * {@link Evaluable} in {@link #kernelArgEvaluables} (slots whose argument
	 * variable is absent hold the pre-resolved null). Because the snapshot is
	 * immutable, a construction that overlaps with the preparation of different
	 * arguments — from another thread, or from an argument evaluation that
	 * reenters the same operation — always observes consistent state.</p>
	 */
	protected static class PreparedArguments {
		/** The output {@link MemoryBank} this snapshot was prepared for. */
		private final MemoryBank output;
		/** The positional arguments this snapshot was prepared for. */
		private final Object args[];
		/** Resolved kernel size (number of parallel work items). */
		private final long kernelSize;
		/** Pre-resolved {@link MemoryData} instances for each kernel argument slot. */
		private final MemoryData kernelArgs[];
		/** {@link Evaluable} instances for kernel arguments that could not be statically resolved. */
		private final Evaluable kernelArgEvaluables[];

		/**
		 * Captures a fully resolved argument snapshot.
		 *
		 * @param output Output {@link MemoryBank} the snapshot was prepared for; may be null
		 * @param args Positional arguments the snapshot was prepared for
		 * @param kernelSize Resolved kernel size for the invocation
		 * @param kernelArgs Pre-resolved {@link MemoryData} for each argument slot
		 * @param kernelArgEvaluables {@link Evaluable} for each argument slot not covered by kernelArgs
		 */
		private PreparedArguments(MemoryBank output, Object args[], long kernelSize,
								  MemoryData kernelArgs[], Evaluable kernelArgEvaluables[]) {
			this.output = output;
			this.args = args;
			this.kernelSize = kernelSize;
			this.kernelArgs = kernelArgs;
			this.kernelArgEvaluables = kernelArgEvaluables;
		}

		/**
		 * Returns true if this snapshot was prepared for exactly the given output
		 * and argument identities.
		 *
		 * @param output Output {@link MemoryBank} for the invocation being prepared
		 * @param args Positional arguments for the invocation being prepared
		 * @return True if this snapshot can be reused for the invocation
		 */
		protected boolean matches(MemoryBank output, Object args[]) {
			return output == this.output &&
					Arrays.equals(args, this.args, (a, b) -> a == b ? 0 : 1);
		}
	}

	/**
	 * Returns the {@link ProducerArgumentReference} index for an argument variable, or -1 if not applicable.
	 *
	 * <p>Checks both the argument's producer directly and, if the producer is a {@link Delegated},
	 * its delegate for a {@link ProducerArgumentReference}.</p>
	 *
	 * @param arg The argument variable to inspect
	 * @return Referenced argument index, or -1 if the argument is not a reference
	 */
	private static int getProducerArgumentReferenceIndex(Variable<?, ?> arg) {
		if (arg.getProducer() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) arg.getProducer()).getReferencedArgumentIndex();
		}

		if (arg.getProducer() instanceof Delegated &&
				((Delegated) arg.getProducer()).getDelegate() instanceof ProducerArgumentReference) {
			return ((ProducerArgumentReference) ((Delegated) arg.getProducer()).getDelegate()).getReferencedArgumentIndex();
		}

		return -1;
	}

	/**
	 * Returns the reuse slot for the given argument index, creating the slot array
	 * on first use.
	 *
	 * @param index the argument index
	 * @return the slot for that argument
	 */
	private synchronized DestinationSlot destinationSlot(int index) {
		if (destinationSlots == null) {
			destinationSlots = new DestinationSlot[arguments.size()];
			for (int i = 0; i < destinationSlots.length; i++) {
				destinationSlots[i] = new DestinationSlot();
			}
		}

		return destinationSlots[index];
	}

	/**
	 * Destroys the destination buffers held by this factory's reuse slots. Called when
	 * the owning operation is destroyed; a buffer still leased to an in-flight
	 * invocation is left for garbage collection rather than destroyed underneath it.
	 */
	public void destroy() {
		DestinationSlot[] slots;

		synchronized (this) {
			slots = destinationSlots;
			destinationSlots = null;
		}

		if (slots != null) {
			for (DestinationSlot slot : slots) {
				slot.destroy();
			}
		}
	}

	/**
	 * A single-buffer destination reuse slot for one kernel argument position.
	 *
	 * <p>An invocation checks the slot's buffer out with {@link #acquire(int, IntFunction)}
	 * and returns it by running the release callback recorded on its
	 * {@link AcceleratedProcessDetails} once the invocation's completion chain has fired.
	 * While the buffer is checked out, an overlapping invocation of the same operation
	 * receives {@code null} and allocates a fresh, unpooled destination — reuse is
	 * opportunistic and never shares a buffer between in-flight invocations.</p>
	 *
	 * <p>Argument sizes are stable for a given operation in steady state, so a single
	 * cached buffer per argument position captures nearly all reuse; a size change
	 * destroys the cached buffer and allocates at the new size, mirroring
	 * {@link org.almostrealism.hardware.mem.MemoryBankProvider}. A buffer destroyed
	 * externally (its memory released elsewhere) is detected via {@link MemoryData#getMem()}
	 * and replaced.</p>
	 */
	public static class DestinationSlot {
		/** The cached destination buffer, or null before first use or after destruction. */
		private MemoryData bank;
		/** Element size the cached buffer was allocated for. */
		private int size;
		/** True while the buffer is leased to an in-flight invocation. */
		private boolean inUse;

		/**
		 * Checks the slot's buffer out for one invocation, allocating or replacing it
		 * as needed.
		 *
		 * @param requestedSize the destination size for this invocation
		 * @param allocate      allocates a new destination of a given size
		 * @return the leased buffer, or {@code null} when the slot is already leased
		 *         (the caller should allocate an unpooled destination)
		 */
		public synchronized MemoryData acquire(int requestedSize, IntFunction<MemoryData> allocate) {
			if (inUse) return null;

			if (bank == null || bank.getMem() == null || size != requestedSize) {
				if (bank != null && bank.getMem() != null) {
					bank.destroy();
				}

				bank = allocate.apply(requestedSize);
				size = requestedSize;
			}

			if (bank == null) return null;

			inUse = true;
			return bank;
		}

		/** Returns the leased buffer to this slot for the next invocation. */
		public synchronized void release() {
			inUse = false;
		}

		/**
		 * Destroys the cached buffer unless it is currently leased, in which case it is
		 * abandoned to garbage collection instead of being destroyed mid-invocation.
		 */
		public synchronized void destroy() {
			if (!inUse && bank != null && bank.getMem() != null) {
				bank.destroy();
			}

			bank = null;
			size = 0;
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
