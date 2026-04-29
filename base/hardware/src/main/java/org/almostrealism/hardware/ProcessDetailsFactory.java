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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
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
 *         // Get factory (created in createDetailsFactory())
 *         ProcessDetailsFactory factory = getDetailsFactory();
 *
 *         // Initialize with output and args
 *         factory.init(output, args);
 *
 *         // Construct process details (evaluates arguments async)
 *         return factory.construct();
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

	/** True if this factory produces kernel (parallel) operations; false for scalar operations. */
	private boolean kernel;
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

	/** The output {@link MemoryBank} passed to the last {@link #init} call. */
	private MemoryBank output;
	/** The positional arguments passed to the last {@link #init} call. */
	private Object args[];
	/** True if all positional arguments passed to {@link #init} are {@link io.almostrealism.code.MemoryData} instances. */
	private boolean allMemoryData;

	/** Resolved kernel size (number of parallel work items) for the current invocation. */
	private long kernelSize;
	/** Pre-resolved {@link MemoryData} instances for each kernel argument slot. */
	private MemoryData kernelArgs[];
	/** {@link Evaluable} instances for kernel arguments that could not be statically resolved. */
	private Evaluable kernelArgEvaluables[];
	/** {@link StreamingEvaluable} instances for asynchronous kernel arguments. */
	private StreamingEvaluable asyncEvaluables[];
	/** The most recently built {@link AcceleratedProcessDetails} instance. */
	private AcceleratedProcessDetails currentDetails;

	/** Executor used to dispatch asynchronous kernel execution when {@link Hardware#isAsync()} is true. */
	private Executor executor;

	/**
	 * Constructs a factory for producing {@link AcceleratedProcessDetails} instances.
	 *
	 * @param kernel True if this is a kernel (parallel) operation; false for scalar
	 * @param fixedCount True if the kernel size is fixed at construction time
	 * @param count Declared number of parallel work items (used when fixedCount is true)
	 * @param arguments Ordered list of array variables representing kernel arguments
	 * @param outputArgIndex Index of the output argument in the arguments list; negative if no output
	 * @param replacements Supplier of the memory replacement manager
	 * @param executor Executor for asynchronous kernel dispatch
	 */
	public ProcessDetailsFactory(boolean kernel, boolean fixedCount, int count,
								 List<ArrayVariable<? extends T>> arguments,
								 int outputArgIndex,
								 Supplier<MemoryReplacementManager> replacements,
								 Executor executor) {
		if (arguments == null) {
			throw new IllegalArgumentException();
		}

		this.kernel = kernel;
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

	public boolean isKernel() { return kernel; }
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
		if (kernelArgEvaluables != null && output == this.output &&
				Arrays.equals(args, this.args, (a, b) -> a == b ? 0 : 1)) {
			// The configuration is already valid as does not need to be repeated
			return this;
		}

		this.output = output;
		this.args = args;

		allMemoryData = args.length <= 0 || Stream.of(args).filter(a -> !(a instanceof MemoryData)).findAny().isEmpty();

		if (!isKernel()) {
			kernelSize = 1;
		} else if (isFixedCount()) {
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

		kernelArgs = new MemoryData[arguments.size()];
		kernelArgEvaluables = new Evaluable[arguments.size()];
		asyncEvaluables = new StreamingEvaluable[arguments.size()];

		if (outputArgIndex < 0 && output != null) {
			// There is no output for this process
			throw new UnsupportedOperationException();
		}

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

		return this;
	}
	
	/**
	 * Clears cached evaluables so the next {@link #init} call fully re-evaluates all arguments.
	 *
	 * <p>Call this when argument producers may have changed since the last {@link #init} call.</p>
	 */
	public void reset() {
		this.kernelArgEvaluables = null;
		this.asyncEvaluables = null;
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
		MemoryData kernelArgs[] = new MemoryData[arguments.size()];

		for (int i = 0; i < kernelArgs.length; i++) {
			if (this.kernelArgs[i] != null) kernelArgs[i] = this.kernelArgs[i];
		}

		/*
		 * Reset asyncEvaluables for each construct() call.
		 * This ensures fresh StreamingEvaluable instances are created with
		 * new downstream consumers that point to the new AcceleratedProcessDetails.
		 * Without this reset, reused asyncEvaluables would throw UnsupportedOperationException
		 * when trying to set a different downstream consumer.
		 */
		asyncEvaluables = new StreamingEvaluable[arguments.size()];

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

		/*
		 * If the kernel size is still not known, the kernel size will be the count.
		 */
		if (kernelSize < 0) {
			if (enableKernelSizeWarnings)
				warn("Could not infer kernel size, it will be set to " + getCount());
			kernelSize = getCount();
		}

		int size = Math.toIntExact(kernelSize);

		/*
		 * Second pass: create async evaluables for kernel arguments that need
		 * sized destinations.
		 */
		for (int i = 0; i < arguments.size(); i++) {
			if (kernelArgs[i] != null || asyncEvaluables[i] != null) continue;

			MemoryData result = (MemoryData) kernelArgEvaluables[i].createDestination(size);
			Heap.addCreatedMemory(result);

			asyncEvaluables[i] = kernelArgEvaluables[i].into(result).async(this::execute);
		}

		/*
		 * Create AcceleratedProcessDetails BEFORE setting downstream on async evaluables.
		 * This ensures that the downstream lambdas capture the specific details instance
		 * rather than accessing the mutable currentDetails field at execution time.
		 */
		currentDetails = new AcceleratedProcessDetails(kernelArgs, size,
											replacements.get(), executor);

		/*
		 * Set downstream on all async evaluables, passing the specific details instance.
		 * FIX: Previously, result(i) created a lambda that accessed 'this.currentDetails'
		 * at execution time. Now we pass the specific instance to avoid the mutable field.
		 */
		for (int i = 0; i < asyncEvaluables.length; i++) {
			if (asyncEvaluables[i] == null || kernelArgs[i] != null) continue;
			asyncEvaluables[i].setDownstream(result(i, currentDetails));
		}

		/*
		 * Now that every StreamingEvaluable is configured to deliver
		 * results to the current AcceleratedProcessDetails, their work
		 * can be initiated via StreamingEvaluable#request
		 */
		for (int i = 0; i < asyncEvaluables.length; i++) {
			if (asyncEvaluables[i] == null || kernelArgs[i] != null) continue;
			asyncEvaluables[i].request(args);
		}

		/* The details are ready */
		return currentDetails;
	}

	/**
	 * Creates a result consumer for the specified argument index that delivers to
	 * the given {@link AcceleratedProcessDetails} instance.
	 *
	 * <p>This method captures the specific details instance in the returned lambda,
	 * ensuring that async results are delivered to the correct details even when
	 * multiple constructions overlap.</p>
	 *
	 * @param index the argument index
	 * @param targetDetails the specific details instance to deliver results to
	 * @return a consumer that delivers results to the target details
	 */
	protected Consumer<Object> result(int index, AcceleratedProcessDetails targetDetails) {
		return result -> targetDetails.result(index, result);
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

	@Override
	public Console console() { return Hardware.console; }
}
