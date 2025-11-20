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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.Computer;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.hardware.arguments.AcceleratedOperationContainer;
import org.almostrealism.hardware.arguments.AcceleratedSubstitutionEvaluable;
import io.almostrealism.relation.ProducerSubstitution;
import org.almostrealism.hardware.instructions.ProcessTreeInstructionsManager;
import org.almostrealism.hardware.instructions.ScopeInstructionsManager;
import org.almostrealism.hardware.instructions.ScopeSignatureExecutionKey;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Stack;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Default implementation of {@link Computer} that coordinates compilation, caching, and execution
 * of hardware-accelerated computations.
 *
 * <p>{@link DefaultComputer} serves as the bridge between high-level computation graphs
 * ({@link Computation}, {@link Producer}, {@link OperationList}) and low-level hardware execution
 * ({@link ComputeContext}, kernels, native code). It is responsible for:</p>
 * <ul>
 *   <li><strong>Context Selection:</strong> Choosing the optimal {@link ComputeContext} (CPU/GPU/OpenCL/Metal)
 *       based on operation characteristics</li>
 *   <li><strong>Instruction Caching:</strong> Maintaining multi-level caches to avoid redundant kernel compilation</li>
 *   <li><strong>Requirements Management:</strong> Thread-local stack of {@link ComputeRequirement}s for
 *       controlling execution targets</li>
 *   <li><strong>Container Creation:</strong> Supporting the {@link HardwareFeatures#instruct} pattern for
 *       cacheable instruction sequences</li>
 *   <li><strong>Compilation:</strong> Converting {@link Computation}s into executable {@link Runnable}s
 *       and {@link Evaluable}s</li>
 * </ul>
 *
 * <h2>Architecture Overview</h2>
 *
 * <pre>
 * -----------------------------------------------------------
 * -              DefaultComputer                             -
 * ----------------------------------------------------------
 * -  Requirements Stack (Thread-Local)                       -
 * -  ----------------                                        -
 * -  - GPU          - &lt;- Current requirements                 -
 * -  ---------------                                        -
 * -  - CPU          -                                        -
 * -  ---------------                                        -
 * -  - (default)    -                                        -
 * -  ---------------                                        -
 * -                                                           -
 * -  Instruction Caches                                      -
 * -  ----------------------------------------------          -
 * -  - operationsCache (Map)                      -          -
 * -  - - Instruction containers (unlimited)        -          -
 * -  ---------------------------------------------          -
 * -  ----------------------------------------------          -
 * -  - processTreeCache (FrequencyCache 500*0.4)  -          -
 * -  - - Process tree instruction managers         -          -
 * -  ---------------------------------------------          -
 * -  ----------------------------------------------          -
 * -  - instructionsCache (FrequencyCache 500*0.4) -          -
 * -  - - Scope instruction managers                -          -
 * -  - - Auto-destroys evicted managers            -          -
 * -  ---------------------------------------------          -
 * -                                                           -
 * -  Context Selection Logic                                 -
 * -  -----------------------------                           -
 * -  - Analyze Computation:      -                           -
 * -  - - Fixed/Variable count    -                           -
 * -  - - Sequential (count=1)    -                           -
 * -  - - Parallel (count>128)    -                           -
 * -  - -> Select CPU or GPU        -                           -
 * -  ----------------------------                           -
 * ----------------------------------------------------------
 * </pre>
 *
 * <h2>Context Selection Strategy</h2>
 *
 * <p>{@link #getContext(Computation)} implements an intelligent selection algorithm that
 * analyzes the computation's characteristics to choose the optimal execution backend:</p>
 *
 * <h3>Decision Flow</h3>
 * <ol>
 *   <li><strong>Count Analysis:</strong> Extract {@link Countable} properties
 *     <ul>
 *       <li>{@code count} - Number of parallel work items</li>
 *       <li>{@code isFixedCount} - Whether count is known at compilation time</li>
 *     </ul>
 *   </li>
 *   <li><strong>Execution Mode Classification:</strong>
 *     <ul>
 *       <li><strong>Sequential:</strong> {@code isFixedCount && count == 1} -> Prefer CPU</li>
 *       <li><strong>Parallel:</strong> {@code !isFixedCount || count > 128} -> Prefer GPU</li>
 *     </ul>
 *   </li>
 *   <li><strong>Context Filtering:</strong> Apply active {@link ComputeRequirement}s</li>
 *   <li><strong>Final Selection:</strong>
 *     <ul>
 *       <li>Parallel operations: First non-CPU context (GPU)</li>
 *       <li>Sequential operations: First CPU context</li>
 *       <li>Fallback: First available context</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Example: Context Selection in Action</h3>
 * <pre>{@code
 * // Sequential operation (count=1) -> CPU
 * Computation<PackedCollection<?>> single = multiply(v1, v2);  // count=1
 * ComputeContext ctx1 = computer.getContext(single);
 * // Result: NativeDataContext (CPU/JNI)
 *
 * // Parallel operation (count=10000) -> GPU
 * Computation<PackedCollection<?>> parallel = multiply(
 *     v(shape(10000, 3), 0),
 *     v(shape(10000, 3), 1)
 * );  // count=10000
 * ComputeContext ctx2 = computer.getContext(parallel);
 * // Result: CLDataContext or MetalDataContext (GPU)
 *
 * // Variable count -> GPU
 * Computation<PackedCollection<?>> variable = multiply(
 *     v(shape(-1, 3), 0),  // count unknown
 *     v(shape(-1, 3), 1)
 * );
 * ComputeContext ctx3 = computer.getContext(variable);
 * // Result: GPU context (handles variable sizes better)
 * }</pre>
 *
 * <h2>ComputeRequirements Management</h2>
 *
 * <p>Requirements form a <strong>thread-local stack</strong> that allows nested execution
 * with different targets:</p>
 *
 * <h3>Stack-Based Requirements</h3>
 * <pre>{@code
 * DefaultComputer computer = Hardware.getLocalHardware().getComputer();
 *
 * // Default: No requirements (auto-select)
 * operation1.get().run();  // Auto-selected context
 *
 * // Push GPU requirement
 * computer.pushRequirements(List.of(ComputeRequirement.GPU));
 * try {
 *     operation2.get().run();  // Forces GPU context
 *
 *     // Nested: Push CPU requirement
 *     computer.pushRequirements(List.of(ComputeRequirement.CPU));
 *     try {
 *         operation3.get().run();  // Forces CPU context
 *     } finally {
 *         computer.popRequirements();  // Back to GPU
 *     }
 *
 *     operation4.get().run();  // GPU again
 * } finally {
 *     computer.popRequirements();  // Back to default
 * }
 * }</pre>
 *
 * <p><strong>Note:</strong> {@link OperationList#setComputeRequirements} automatically
 * manages the requirements stack, so manual push/pop is rarely needed.</p>
 *
 * <h2>Multi-Level Instruction Caching</h2>
 *
 * <p>{@link DefaultComputer} maintains three levels of caching to minimize compilation overhead:</p>
 *
 * <h3>Level 1: Operation Container Cache</h3>
 * <p>Caches entire instruction patterns created via {@link HardwareFeatures#instruct}:</p>
 * <pre>{@code
 * // First call: Compiles and caches under "scale_2x"
 * Producer<PackedCollection<?>> scaled1 = instruct("scale_2x",
 *     args -> multiply(args[0], c(2.0)),
 *     inputData1
 * );
 *
 * // Second call: Reuses cached container, applies argument substitution
 * Producer<PackedCollection<?>> scaled2 = instruct("scale_2x",
 *     args -> multiply(args[0], c(2.0)),  // Not re-executed
 *     inputData2  // New data substituted into cached operation
 * );
 * }</pre>
 *
 * <p><strong>Cache Properties:</strong></p>
 * <ul>
 *   <li>Type: {@link HashMap} (unbounded)</li>
 *   <li>Key: String instruction key</li>
 *   <li>Value: {@link OperationContainer} with compiled operation and argument templates</li>
 *   <li>Lifetime: Process lifetime (no eviction)</li>
 * </ul>
 *
 * <h3>Level 2: Process Tree Cache</h3>
 * <p>Caches {@link ProcessTreeInstructionsManager}s for operation graph transformations:</p>
 * <pre>{@code
 * // Extracts instruction patterns from process tree
 * ProcessTreeInstructionsManager mgr = computer.getProcessTreeInstructionsManager("key");
 * Process optimized = mgr.extractAll(originalProcess);
 * }</pre>
 *
 * <p><strong>Cache Properties:</strong></p>
 * <ul>
 *   <li>Type: {@link FrequencyCache} (LFU-based)</li>
 *   <li>Capacity: 500 entries</li>
 *   <li>Eviction Threshold: 0.4 (40% least frequently used evicted when full)</li>
 *   <li>Lifetime: Eviction-based (frequently used entries retained)</li>
 * </ul>
 *
 * <h3>Level 3: Scope Instructions Cache</h3>
 * <p>Caches {@link ScopeInstructionsManager}s for scope-level compilation:</p>
 * <pre>{@code
 * // Manages compiled kernels for specific scope signatures
 * ScopeInstructionsManager mgr = computer.getScopeInstructionsManager(
 *     signature, computation, context, scopeSupplier
 * );
 * }</pre>
 *
 * <p><strong>Cache Properties:</strong></p>
 * <ul>
 *   <li>Type: {@link FrequencyCache} (LFU-based)</li>
 *   <li>Capacity: 500 entries</li>
 *   <li>Eviction Threshold: 0.4</li>
 *   <li>Eviction Listener: Calls {@code destroy()} on evicted managers to free resources</li>
 *   <li>Auto-Restore: Accessing an evicted entry recreates it</li>
 * </ul>
 *
 * <h3>Cache Access Patterns</h3>
 * <p>Using scope instructions triggers automatic cache management:</p>
 * <pre>{@code
 * // First access: Creates and caches manager
 * ScopeInstructionsManager mgr1 = computer.getScopeInstructionsManager(...);
 * mgr1.compile();  // Updates frequency count
 *
 * // Later: If evicted, accessing restores to cache
 * ScopeInstructionsManager mgr2 = computer.getScopeInstructionsManager(...);
 * // Either returns cached instance or creates new one
 * }</pre>
 *
 * <h2>Instruction Container Pattern</h2>
 *
 * <p>The {@link #createContainer} method implements the caching strategy for
 * {@link HardwareFeatures#instruct}:</p>
 *
 * <h3>Container Creation Flow</h3>
 * <pre>
 * 1. Check operationsCache for key
 *    - Found: Reuse container
 *    -         Create substitution evaluable
 *    -         Apply argument substitutions
 *    -         Return delegated producer
 *    -
 *    - Not Found: Create new container
 *                  Apply function to arguments
 *                  Extract/apply instruction managers
 *                  Create AcceleratedOperationContainer
 *                  Cache container
 *                  Create substitution evaluable
 *                  Return delegated producer
 * </pre>
 *
 * <h3>Argument Substitution</h3>
 * <p>Containers store argument templates and substitute actual data at evaluation time:</p>
 * <pre>{@code
 * // Cached container has template arguments: [arg0Template]
 * // Actual evaluation with different data:
 * AcceleratedSubstitutionEvaluable eval = new AcceleratedSubstitutionEvaluable(container);
 * eval.addSubstitution(substitution.apply(arg0Template, actualData));
 * // Evaluation uses actualData instead of arg0Template
 * }</pre>
 *
 * <h2>Compilation Methods</h2>
 *
 * <h3>Runnable Compilation</h3>
 * <p>Compile void computations into executable {@link Runnable}s:</p>
 * <pre>{@code
 * Computation<Void> computation = a(memLength, destination, source);
 * Runnable executable = computer.compileRunnable(computation);
 * executable.run();  // Executes on hardware
 * }</pre>
 *
 * <h3>Producer Compilation</h3>
 * <p>Compile value-producing computations into {@link Evaluable}s:</p>
 * <pre>{@code
 * Computation<PackedCollection<?>> computation = multiply(a, b);
 * Evaluable<PackedCollection<?>> evaluable = computer.compileProducer(computation);
 * PackedCollection<?> result = evaluable.evaluate();
 * }</pre>
 *
 * <p><strong>Note:</strong> Producer compilation bypasses {@code postProcessOutput} methods.
 * If post-processing is required, use {@link Producer#get()} instead.</p>
 *
 * <h2>Decompilation</h2>
 *
 * <p>Extract the original {@link Computation} from compiled operations:</p>
 * <pre>{@code
 * Runnable compiled = computer.compileRunnable(computation);
 *
 * // Later: Recover original computation
 * Optional<Computation<Void>> recovered = computer.decompile(compiled);
 * if (recovered.isPresent()) {
 *     Computation<Void> original = recovered.get();
 *     // Can analyze, recompile with different context, etc.
 * }
 * }</pre>
 *
 * <p><strong>Use Cases:</strong></p>
 * <ul>
 *   <li>Debugging: Inspect computation structure after compilation</li>
 *   <li>Recompilation: Compile same computation for different backend</li>
 *   <li>Analysis: Extract operation metadata and dependencies</li>
 * </ul>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Manual Context Selection</h3>
 * <pre>{@code
 * DefaultComputer computer = Hardware.getLocalHardware().getComputer();
 *
 * // Force GPU execution
 * computer.pushRequirements(List.of(ComputeRequirement.GPU));
 * try {
 *     largeMatrixMultiply.get().run();
 * } finally {
 *     computer.popRequirements();
 * }
 * }</pre>
 *
 * <h3>Cached Instruction Patterns</h3>
 * <pre>{@code
 * public class AudioProcessor implements HardwareFeatures {
 *     private static final String FILTER_KEY = "lowpass_1000hz";
 *
 *     public Producer<PackedCollection<?>> filter(Producer<PackedCollection<?>> input) {
 *         // First call: compiles and caches
 *         // Subsequent calls: reuse cached kernel with new input
 *         return instruct(FILTER_KEY,
 *             args -> lowPass(args[0], c(1000.0), 44100),
 *             input
 *         );
 *     }
 * }
 * }</pre>
 *
 * <h3>Inspecting Active Requirements</h3>
 * <pre>{@code
 * List<ComputeRequirement> active = computer.getActiveRequirements();
 * System.out.println("Current requirements: " + active);
 * // Output: [GPU] or [] if none
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <h3>Cache Efficiency</h3>
 * <ul>
 *   <li><strong>Instruction keys should be stable:</strong> Use constant strings for {@code instruct()} keys</li>
 *   <li><strong>Avoid dynamic keys:</strong> {@code instruct("key_" + Math.random(), ...)} defeats caching</li>
 *   <li><strong>FrequencyCache tuning:</strong> 500 entries @ 0.4 eviction works for most workloads</li>
 *   <li><strong>Cache hits are 1000x+ faster than compilation</strong></li>
 * </ul>
 *
 * <h3>Context Selection Overhead</h3>
 * <ul>
 *   <li>Context selection is lightweight (microseconds)</li>
 *   <li>Compilation is expensive (milliseconds to seconds)</li>
 *   <li>Caching amortizes compilation cost over many executions</li>
 * </ul>
 *
 * <h3>Requirements Stack Overhead</h3>
 * <ul>
 *   <li>Thread-local stack operations are very fast</li>
 *   <li>Always use try-finally to ensure pop operations</li>
 *   <li>Imbalanced push/pop can cause incorrect context selection</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link DefaultComputer} uses {@link ThreadLocal} for the requirements stack, making
 * it thread-safe for concurrent access. Each thread maintains its own requirements stack.</p>
 *
 * <p>The caches ({@code operationsCache}, {@code processTreeCache}, {@code instructionsCache})
 * are shared across threads but use thread-safe data structures.</p>
 *
 * <h2>Integration with Hardware</h2>
 *
 * <p>{@link DefaultComputer} is created and managed by {@link Hardware}:</p>
 * <pre>{@code
 * Hardware hw = Hardware.getLocalHardware();
 * DefaultComputer computer = hw.getComputer();
 *
 * // Computer delegates to Hardware for context retrieval
 * List<ComputeContext<MemoryData>> contexts = hw.getComputeContexts(...);
 * }</pre>
 *
 * <h2>Common Pitfalls</h2>
 *
 * <h3>Forgetting to Pop Requirements</h3>
 * <pre>{@code
 * // BAD: Unbalanced stack
 * computer.pushRequirements(List.of(ComputeRequirement.GPU));
 * operation.get().run();
 * // Missing popRequirements() - all subsequent operations use GPU
 *
 * // GOOD: Always use try-finally
 * computer.pushRequirements(List.of(ComputeRequirement.GPU));
 * try {
 *     operation.get().run();
 * } finally {
 *     computer.popRequirements();
 * }
 * }</pre>
 *
 * <h3>Using Dynamic Instruction Keys</h3>
 * <pre>{@code
 * // BAD: Cache never hits
 * for (int i = 0; i < 1000; i++) {
 *     instruct("scale_" + i, args -> multiply(args[0], c(2.0)), data);
 *     // Creates 1000 cache entries, no reuse
 * }
 *
 * // GOOD: Single cache entry, 1000 reuses
 * for (int i = 0; i < 1000; i++) {
 *     instruct("scale_op", args -> multiply(args[0], c(2.0)), data);
 *     // Compiles once, reuses 999 times
 * }
 * }</pre>
 *
 * <h3>Assuming postProcessOutput is Applied</h3>
 * <pre>{@code
 * // compileProducer bypasses postProcessOutput
 * Computation<T> comp = ...;  // Has postProcessOutput method
 * Evaluable<T> eval = computer.compileProducer(comp);
 * T result = eval.evaluate();  // postProcessOutput NOT called
 *
 * // Use Producer.get() if post-processing is needed
 * Producer<T> producer = ...;
 * Evaluable<T> eval = producer.get();  // Includes postProcessOutput
 * }</pre>
 *
 * @see Computer
 * @see Hardware
 * @see ComputeContext
 * @see ComputeRequirement
 * @see HardwareFeatures#instruct
 * @see FrequencyCache
 *
 * @author  Michael Murray
 */
public class DefaultComputer implements Computer<MemoryData>, ConsoleFeatures {
	private Hardware hardware;

	private ThreadLocal<Stack<List<ComputeRequirement>>> requirements;

	private Map<String, OperationContainer> operationsCache;
	private FrequencyCache<String, ProcessTreeInstructionsManager> processTreeCache;
	private FrequencyCache<String, ScopeInstructionsManager<ScopeSignatureExecutionKey>> instructionsCache;

	public DefaultComputer(Hardware hardware) {
		this.hardware = hardware;
		this.requirements = ThreadLocal.withInitial(Stack::new);
		this.operationsCache = new HashMap<>();
		this.processTreeCache = new FrequencyCache<>(500, 0.4);
		this.instructionsCache = new FrequencyCache<>(500, 0.4);
		this.instructionsCache.setEvictionListener(
				(key, mgr) -> mgr.destroy());
	}

	/**
	 * Selects the optimal {@link ComputeContext} for the given computation.
	 *
	 * <p>Implements intelligent context selection based on computation characteristics:</p>
	 * <ul>
	 *   <li>Sequential operations (count=1): Prefer CPU contexts</li>
	 *   <li>Parallel operations (count>128 or variable): Prefer GPU contexts</li>
	 *   <li>Active {@link ComputeRequirement}s are applied to filter contexts</li>
	 * </ul>
	 *
	 * @param c The computation to select a context for
	 * @return The optimal compute context
	 * @throws RuntimeException if no contexts are available
	 */
	@Override
	public ComputeContext<MemoryData> getContext(Computation<?> c) {
		long count = Countable.countLong(c);
		boolean fixed = Countable.isFixedCount(c);
		boolean sequential = fixed && count == 1;
		boolean accelerator = !fixed || count > 128;
		List<ComputeContext<MemoryData>> contexts = hardware
				.getComputeContexts(sequential, accelerator,
					getActiveRequirements().toArray(ComputeRequirement[]::new));
		if (contexts.isEmpty()) throw new RuntimeException("No compute contexts available");
		if (contexts.size() == 1) return contexts.get(0);

		if (!fixed || count > 1) {
			return contexts.stream()
					.filter(cc -> !cc.isCPU())
					.findFirst()
					.orElse(contexts.get(0));
		} else {
			return contexts.stream()
					.filter(cc -> cc.isCPU())
					.findFirst()
					.orElse(contexts.get(0));
		}
	}

	/**
	 * Returns the currently active {@link ComputeRequirement}s for this thread.
	 *
	 * <p>Requirements are maintained in a thread-local stack. Returns the top
	 * of the stack, or an empty list if no requirements are active.</p>
	 *
	 * @return The active requirements, or empty list if none
	 */
	public List<ComputeRequirement> getActiveRequirements() {
		return requirements.get().isEmpty() ? Collections.emptyList() : requirements.get().peek();
	}

	/**
	 * Pushes new {@link ComputeRequirement}s onto the thread-local stack.
	 *
	 * <p>All subsequent context selections will respect these requirements
	 * until they are popped via {@link #popRequirements()}.</p>
	 *
	 * <p><strong>Always use try-finally to ensure balanced push/pop:</strong></p>
	 * <pre>{@code
	 * computer.pushRequirements(List.of(ComputeRequirement.GPU));
	 * try {
	 *     operation.get().run();
	 * } finally {
	 *     computer.popRequirements();
	 * }
	 * }</pre>
	 *
	 * @param requirements The requirements to activate
	 */
	public void pushRequirements(List<ComputeRequirement> requirements) {
		this.requirements.get().push(requirements);
	}

	/**
	 * Pops the most recently pushed {@link ComputeRequirement}s from the stack.
	 *
	 * <p>Restores the previous requirements (or no requirements if stack becomes empty).</p>
	 *
	 * <p><strong>Warning:</strong> Always call this in a finally block to avoid
	 * imbalanced stack state.</p>
	 */
	public void popRequirements() {
		this.requirements.get().pop();
	}

	/**
	 * Returns a {@link ProcessTreeInstructionsManager} for the given key, creating if absent.
	 *
	 * <p>Managers are cached in a frequency-based cache (capacity: 500, eviction: 0.4).
	 * Accessing a manager updates its frequency count, making it less likely to be evicted.</p>
	 *
	 * @param key Unique identifier for this instruction manager
	 * @return The instruction manager for this key
	 */
	public ProcessTreeInstructionsManager getProcessTreeInstructionsManager(String key) {
		return processTreeCache.computeIfAbsent(key, k -> new ProcessTreeInstructionsManager());
	}

	/**
	 * Returns a {@link ScopeInstructionsManager} for the given signature, creating if absent.
	 *
	 * <p>Scope instruction managers compile and cache kernels for operations with matching
	 * signatures. This enables cross-instance kernel reuse when multiple operations share
	 * the same structure.</p>
	 *
	 * <p>Managers are cached with automatic eviction (capacity: 500, eviction: 0.4).
	 * Evicted managers are destroyed, but accessing them again recreates the manager.</p>
	 *
	 * <p>Access listener ensures that using any {@link io.almostrealism.code.InstructionSet}
	 * from the manager updates the cache frequency or restores an evicted entry.</p>
	 *
	 * @param signature Unique signature identifying the operation structure
	 * @param computation The computation to manage (used for Process tree substitution if applicable)
	 * @param context The compute context for compilation
	 * @param scope Supplier of the scope to compile
	 * @return The instruction manager for this signature
	 */
	public ScopeInstructionsManager<ScopeSignatureExecutionKey> getScopeInstructionsManager(String signature,
																							Computation<?> computation,
																							ComputeContext<?> context,
																							Supplier<Scope<?>> scope) {
		Consumer<ScopeInstructionsManager<ScopeSignatureExecutionKey>>
				accessListener =mgr -> {
					// Ensure that usage of any InstructionSets updates
					// the access frequency in the cache if it is present
					// or restores it to the cache if it had previously
					// been evicted
					instructionsCache.computeIfAbsent(signature, () -> mgr);
				};

		return instructionsCache.computeIfAbsent(Objects.requireNonNull(signature),
				() -> {
					ScopeInstructionsManager<ScopeSignatureExecutionKey> mgr =
							new ScopeInstructionsManager<>(context, scope, accessListener);

					if (computation instanceof Process<?, ?>) {
						mgr.setProcess((Process<?, ?>) computation);
					}

					return mgr;
				});
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>, M extends MemoryData>
			Producer<M> createContainer(String key,
								Function<Producer[], Producer<M>> func,
								BiFunction<Producer, Producer, ProducerSubstitution> substitution,
								BiFunction<Producer, Producer, Producer> delegate,
								Producer... args) {
		OperationContainer container;

		if (operationsCache.containsKey(key)) {
			container = operationsCache.get(key);
		} else {
			Producer<M> producer = func.apply(args);

			if (!(producer instanceof Process)) {
				return producer;
			}

			Process<?, ?> operation = applyInstructionsManager(key, (Process) producer);
			AcceleratedOperationContainer c = getProcessTreeInstructionsManager(key).applyContainer(operation);
			if (c == null) {
				return producer;
			}

			container = new OperationContainer(c, args, producer);
			operationsCache.put(key, container);
		}

		AcceleratedSubstitutionEvaluable evaluable = new AcceleratedSubstitutionEvaluable<>(container.container);
		for (int i = 0; i < args.length; i++) {
			evaluable.addSubstitution(substitution.apply(container.arguments[i], args[i]));
		}
		return delegate.apply(container.result, () -> evaluable);
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>> Process<P, T>
			applyInstructionsManager(String key, V process) {
		if (processTreeCache.containsKey(key)) {
			return getProcessTreeInstructionsManager(key).replaceAll(process);
		} else {
			return getProcessTreeInstructionsManager(key).extractAll(process);
		}
	}

	/**
	 * Compiles a void {@link Computation} into an executable {@link Runnable}.
	 *
	 * <p>The computation is wrapped in an {@link AcceleratedComputationOperation} and
	 * registered with {@link Heap} for automatic cleanup. The runnable is compiled once
	 * and can be executed repeatedly.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * Computation<Void> assignment = a(memLength, destination, source);
	 * Runnable executable = computer.compileRunnable(assignment);
	 * executable.run();  // Executes on hardware
	 * }</pre>
	 *
	 * @param c The void computation to compile
	 * @return Executable runnable
	 */
	@Override
	public Runnable compileRunnable(Computation<Void> c) {
		return Heap.addCompiled(new AcceleratedComputationOperation<>(getContext(c), c, true));
	}

	/**
	 * Compiles a value-producing {@link Computation} into an {@link Evaluable}.
	 *
	 * <p><strong>Important:</strong> This method bypasses any {@code postProcessOutput}
	 * method defined in the computation. If post-processing is needed, use
	 * {@link io.almostrealism.relation.Producer#get()} instead.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * Computation<PackedCollection<?>> multiply = ...;
	 * Evaluable<PackedCollection<?>> evaluable = computer.compileProducer(multiply);
	 * PackedCollection<?> result = evaluable.evaluate();
	 * }</pre>
	 *
	 * @param c The computation to compile
	 * @param <T> The result type
	 * @return Evaluable that produces values
	 */
	// TODO  The Computation may have a postProcessOutput method that will not be called
	// TODO  when using this method of creating an Evaluable from it. Ideally, that feature
	// TODO  of the Computation would be recognized, and applied after evaluation, so that
	// TODO  the correct type is returned.
	@Override
	public <T extends MemoryData> Evaluable<T> compileProducer(Computation<T> c) {
		return new AcceleratedComputationEvaluable<>(getContext(c), c);
	}

	/**
	 * Extracts the original {@link Computation} from a compiled {@link Runnable}.
	 *
	 * <p>Only works for runnables created via {@link #compileRunnable(Computation)}.
	 * Other runnables return {@link Optional#empty()}.</p>
	 *
	 * <p>Use cases:</p>
	 * <ul>
	 *   <li>Debugging: Inspect computation structure after compilation</li>
	 *   <li>Recompilation: Compile same computation for different context</li>
	 *   <li>Analysis: Extract metadata and dependencies</li>
	 * </ul>
	 *
	 * @param r The runnable to decompile
	 * @param <T> The computation's result type
	 * @return The original computation, or empty if not available
	 */
	@Override
	public <T> Optional<Computation<T>> decompile(Runnable r) {
		if (r instanceof AcceleratedComputationOperation) {
			return Optional.of(((AcceleratedComputationOperation) r).getComputation());
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Extracts the original {@link Computation} from a compiled {@link Evaluable}.
	 *
	 * <p>Only works for evaluables created via {@link #compileProducer(Computation)}.
	 * Other evaluables return {@link Optional#empty()}.</p>
	 *
	 * @param p The evaluable to decompile
	 * @param <T> The evaluable's result type
	 * @return The original computation, or empty if not available
	 */
	@Override
	public <T> Optional<Computation<T>> decompile(Evaluable<T> p) {
		if (p instanceof AcceleratedComputationEvaluable) {
			return Optional.of(((AcceleratedComputationEvaluable) p).getComputation());
		} else {
			return Optional.empty();
		}
	}

	@Override
	public Console console() { return Hardware.console; }

	class OperationContainer {
		private AcceleratedOperationContainer container;
		private Producer arguments[];
		private Producer result;

		public OperationContainer(AcceleratedOperationContainer container, Producer arguments[], Producer result) {
			this.container = container;
			this.arguments = arguments;
			this.result = result;
		}
	}
}
