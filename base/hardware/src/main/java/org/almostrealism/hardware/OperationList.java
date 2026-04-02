/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ComputableParallelProcess;
import io.almostrealism.code.Computation;
import io.almostrealism.code.NamedFunction;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.profile.OperationTimingListener;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.computations.Abort;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A composable list of operations that can be executed sequentially as Java code or
 * compiled into a single hardware-accelerated kernel.
 *
 * <p>{@link OperationList} serves as the primary mechanism for composing multiple
 * operations into a unified execution unit in Almost Realism. It intelligently decides
 * whether to compile operations into a single GPU/CPU kernel (for maximum performance)
 * or execute them sequentially as Java code (when compilation is not possible).</p>
 *
 * <h2>Core Concept: Dual Execution Strategy</h2>
 *
 * <p>{@link OperationList} has a <strong>dual nature</strong>:</p>
 *
 * <h3>1. Compiled Execution (Fast)</h3>
 * <p>When all operations are {@link Computation}s, the list can be compiled into a single
 * hardware kernel:</p>
 * <pre>{@code
 * OperationList ops = new OperationList("Matrix Operations");
 * ops.add(multiply(a, b));      // Computation
 * ops.add(add(result, c));       // Computation
 * ops.add(scale(result, 2.0));   // Computation
 *
 * Runnable kernel = ops.get();
 * kernel.run();  // Single GPU kernel dispatch (very fast)
 * }</pre>
 *
 * <p><strong>Benefits:</strong></p>
 * <ul>
 *   <li>Single kernel launch overhead (vs multiple launches)</li>
 *   <li>Intermediate results stay in GPU memory (no host transfers)</li>
 *   <li>Kernel fusion optimizations automatically applied</li>
 *   <li>Significantly faster for GPU execution</li>
 * </ul>
 *
 * <h3>2. Sequential Execution (Flexible)</h3>
 * <p>When operations cannot be compiled (e.g., contain Java lambdas, I/O, or exceed depth limits),
 * the list executes them sequentially:</p>
 * <pre>{@code
 * OperationList ops = new OperationList("Mixed Operations");
 * ops.add(computation1);         // Computation
 * ops.add(() -> () -> {          // Plain Java Supplier<Runnable>
 *     System.out.println("Debug");
 * });
 * ops.add(computation2);         // Computation
 *
 * Runnable runner = ops.get();
 * runner.run();  // Sequential Java execution (slower but flexible)
 * }</pre>
 *
 * <p><strong>Use cases:</strong></p>
 * <ul>
 *   <li>Debugging and logging within operation sequences</li>
 *   <li>Mixing hardware operations with I/O or control flow</li>
 *   <li>Operations exceeding compilation depth limits</li>
 * </ul>
 *
 * <h2>Compilation Eligibility</h2>
 *
 * <p>An {@link OperationList} can be compiled to a kernel ({@link #isComputation()} returns true) if:</p>
 * <ol>
 *   <li><strong>All operations are Computations:</strong> Every element must implement
 *       {@link Computation} or be an {@link OperationList} that is itself compilable</li>
 *   <li><strong>Compilation is enabled:</strong> Constructor parameter {@code enableCompilation} is true</li>
 *   <li><strong>Depth is within limits:</strong> Nested {@link OperationList} depth &lt;= {@code maxDepth} (default: 500)</li>
 * </ol>
 *
 * <p><strong>Example - Non-Compilable List:</strong></p>
 * <pre>{@code
 * OperationList ops = new OperationList();
 * ops.add(computation1);  // Computation check
 * ops.add(() -> {         // Not a Computation x
 *     return () -> System.out.println("Not compilable");
 * });
 *
 * ops.isComputation();  // Returns false
 * ops.get().run();      // Sequential execution via Runner
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Model Setup Operations</h3>
 * <p>Compose initialization operations that run before training:</p>
 * <pre>{@code
 * public class Model {
 *     public Supplier<Runnable> setup() {
 *         OperationList setup = new OperationList("Model Setup");
 *         setup.add(initializeWeights());
 *         setup.add(resetOptimizer());
 *         setup.add(clearGradients());
 *         return setup;
 *     }
 * }
 * }</pre>
 *
 * <h3>Layer Composition</h3>
 * <p>Build multi-step layer operations:</p>
 * <pre>{@code
 * OperationList layer = new OperationList("Attention Layer");
 * layer.add(qkvProjection);      // Q, K, V projections
 * layer.add(attentionScores);     // Scaled dot-product
 * layer.add(softmax);             // Attention weights
 * layer.add(outputProjection);    // Final linear
 *
 * // Entire layer compiles to single kernel if all ops are Computations
 * Runnable forward = layer.get();
 * }</pre>
 *
 * <h3>Temporal Iteration</h3>
 * <p>Execute setup, iteration, and teardown:</p>
 * <pre>{@code
 * OperationList iter = new OperationList("Audio Processing");
 * iter.add(synthesizer.setup());
 * iter.add(loop(synthesizer.tick(), 1000));
 * iter.add(synthesizer.reset());
 * iter.get().run();
 * }</pre>
 *
 * <h3>Assignment Operations</h3>
 * <p>Assign producer results to destinations:</p>
 * <pre>{@code
 * OperationList assignments = new OperationList();
 * assignments.add(memLength, sourceProducer, destinationProducer);
 * // Equivalent to: destinationProducer <- sourceProducer
 * assignments.get().run();
 * }</pre>
 *
 * <h2>Nesting and Flattening</h2>
 *
 * <h3>Nested Lists</h3>
 * <p>{@link OperationList} supports nesting for hierarchical organization:</p>
 * <pre>{@code
 * OperationList outer = new OperationList("Training");
 *
 * OperationList forward = new OperationList("Forward Pass");
 * forward.add(layer1);
 * forward.add(layer2);
 * forward.add(layer3);
 *
 * OperationList backward = new OperationList("Backward Pass");
 * backward.add(layer3Grad);
 * backward.add(layer2Grad);
 * backward.add(layer1Grad);
 *
 * outer.add(forward);
 * outer.add(backward);
 * outer.add(updateWeights);
 *
 * // Depth = 2 (outer -> forward/backward -> layers)
 * int depth = outer.getDepth();  // Returns 2
 * }</pre>
 *
 * <h3>Flattening</h3>
 * <p>Use {@link #flatten()} to collapse nested lists into a single level:</p>
 * <pre>{@code
 * OperationList nested = ...;  // Depth = 3
 * OperationList flat = nested.flatten();  // Depth = 1
 *
 * // Flattening can improve compilation success for deep nests
 * if (nested.getDepth() > maxDepth) {
 *     flat = nested.flatten();
 * }
 * }</pre>
 *
 * <p><strong>Note:</strong> {@link OperationList}s with {@link ComputeRequirement}s set
 * are preserved during flattening to maintain execution context.</p>
 *
 * <h2>Depth Limits and Abort Mechanism</h2>
 *
 * <h3>Maximum Depth</h3>
 * <p>To prevent excessive compilation overhead and stack depth issues:</p>
 * <ul>
 *   <li><strong>{@code maxDepth = 500}:</strong> Lists exceeding this depth cannot compile</li>
 *   <li><strong>{@code abortableDepth = 1000}:</strong> Very deep lists get abort flag injection</li>
 * </ul>
 *
 * <h3>Abort Flag Mechanism</h3>
 * <p>For extremely deep operation lists (depth > 1000), the framework automatically injects
 * abort checks to prevent runaway execution:</p>
 * <pre>{@code
 * // Set abort flag before execution
 * MemoryData abortFlag = new PackedCollection(1).traverse(0);
 * OperationList.setAbortFlag(abortFlag);
 *
 * try {
 *     deepList.get().run();
 *
 *     // To abort mid-execution, set flag value to non-zero
 *     abortFlag.setMem(0, 1.0);  // Triggers abort on next check
 * } finally {
 *     OperationList.removeAbortFlag();
 * }
 * }</pre>
 *
 * <h2>ComputeRequirements Propagation</h2>
 *
 * <p>Attach {@link ComputeRequirement}s to control execution target:</p>
 * <pre>{@code
 * OperationList gpuOps = new OperationList("GPU Pipeline");
 * gpuOps.add(largeMatrixMultiply);
 * gpuOps.add(convolution);
 * gpuOps.setComputeRequirements(List.of(ComputeRequirement.GPU));
 *
 * // All operations execute on GPU
 * gpuOps.get().run();
 * }</pre>
 *
 * <p><strong>Requirement Stacking:</strong> Requirements are pushed/popped during execution,
 * allowing nested lists with different requirements:</p>
 * <pre>{@code
 * OperationList mixed = new OperationList();
 * mixed.setComputeRequirements(List.of(ComputeRequirement.CPU));
 *
 * OperationList gpuSection = new OperationList();
 * gpuSection.setComputeRequirements(List.of(ComputeRequirement.GPU));
 * gpuSection.add(gpuKernel);
 *
 * mixed.add(cpuPreprocess);
 * mixed.add(gpuSection);      // Temporarily switches to GPU
 * mixed.add(cpuPostprocess);  // Returns to CPU
 * }</pre>
 *
 * <h2>Optimization Flags</h2>
 *
 * <p>Three static boolean flags control how {@link OperationList} compiles and executes.
 * All three are global (process-wide) settings that affect every {@link OperationList}
 * in the JVM. See the detailed documentation at
 * {@code docs/internals/operationlist-optimization-flags.md} for full analysis.</p>
 *
 * <h3>{@code enableAutomaticOptimization} (default: {@code false})</h3>
 * <p>When {@code true}, {@link #get()} automatically calls {@link #optimize()} on any
 * non-uniform list before compiling or executing it. This triggers the
 * {@link io.almostrealism.compute.Process} optimization pipeline, which handles
 * process isolation (critical for computations like {@code LoopedWeightedSumComputation})
 * and delegates to optimization strategies like {@code ParallelismTargetOptimization}.</p>
 *
 * <p><strong>Risk of enabling:</strong> Every non-uniform {@code get()} call now pays
 * optimization overhead. Code that already calls {@code optimize()} explicitly will
 * optimize twice. Previously-compiled single kernels may be restructured.</p>
 *
 * <p><strong>Risk of disabling (when currently enabled):</strong> Computations requiring
 * isolation will be embedded into parent expression trees, potentially causing
 * compilation timeouts or stack overflows.</p>
 *
 * <h3>{@code enableSegmenting} (default: {@code false})</h3>
 * <p>When {@code true}, {@link #optimize(ProcessContext)} groups consecutive operations
 * with the same parallelism count into sub-lists. Each sub-list compiles as a single
 * kernel, reducing JNI transitions:</p>
 * <pre>{@code
 * // Input:  [scalar(1), scalar(1), vector(4096), vector(4096), scalar(1)]
 * // Output: [group(1,1), group(4096,4096), group(1)]
 * // Result: 3 kernel dispatches instead of 5
 * }</pre>
 *
 * <p><strong>Prerequisites:</strong> Segmentation only activates when the list has &gt;1
 * element, is non-uniform, and has at least two adjacent operations with the same count.
 * It only runs during {@code optimize()}, so it requires either
 * {@code enableAutomaticOptimization = true} or an explicit {@code optimize()} call.</p>
 *
 * <p><strong>Risk of enabling:</strong> The process tree is restructured (new sub-lists
 * created), which affects profiling node keys. Each segment compiles independently,
 * potentially increasing cold-start compilation time.</p>
 *
 * <p><strong>Risk of disabling (when currently enabled):</strong> Non-uniform lists
 * dispatch each operation individually, increasing JNI overhead. Performance regression
 * in pipelines relying on kernel fusion (e.g. AudioScene).</p>
 *
 * <h3>{@code enableNonUniformCompilation} (default: {@code false})</h3>
 * <p>When {@code true}, allows non-uniform lists to compile directly into a single
 * hardware kernel. <strong>WARNING:</strong> This carries significant correctness risk
 * because the kernel dispatch count must be a single value, but the operations expect
 * different counts. This can produce <strong>silent incorrect results</strong>.</p>
 *
 * <h3>Flag Precedence in {@code get()}</h3>
 * <ol>
 *   <li>If {@code enableAutomaticOptimization &amp;&amp; !isUniform()} → call {@code optimize().get()}</li>
 *   <li>If {@code isComputation() &amp;&amp; (enableNonUniformCompilation || isUniform())} → compile to single kernel</li>
 *   <li>Otherwise → sequential {@link Runner} execution</li>
 * </ol>
 *
 * <h3>Uniform Lists</h3>
 * <p>A list is <strong>uniform</strong> if all operations have the same {@link Countable} count.
 * Uniform lists bypass both automatic optimization and segmentation, going directly to
 * single-kernel compilation when all operations are {@link Computation}s.</p>
 *
 * <h2>Metadata and Profiling</h2>
 *
 * <p>Every {@link OperationList} has associated metadata for profiling:</p>
 * <pre>{@code
 * OperationList ops = new OperationList("Forward Pass");
 * ops.add(...);
 *
 * OperationMetadata metadata = ops.getMetadata();
 * String name = metadata.getDisplayName();  // "Forward Pass"
 * String functionName = ops.getFunctionName();  // "operations_42"
 *
 * // Attach profiler
 * OperationProfile profile = new DefaultProfile();
 * Runnable profiled = ops.get(profile);
 * profiled.run();  // Timing data collected in profile
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <h3>Compiled vs Sequential Execution</h3>
 * <table>
 *   <caption>Comparison of compiled versus sequential execution modes</caption>
 *   <tr>
 *     <th>Aspect</th>
 *     <th>Compiled (Computation)</th>
 *     <th>Sequential (Runner)</th>
 *   </tr>
 *   <tr>
 *     <td>Execution Speed</td>
 *     <td>Very fast (single kernel)</td>
 *     <td>Slower (multiple dispatches)</td>
 *   </tr>
 *   <tr>
 *     <td>Memory Transfers</td>
 *     <td>Minimal (results stay in GPU)</td>
 *     <td>More transfers (between operations)</td>
 *   </tr>
 *   <tr>
 *     <td>Flexibility</td>
 *     <td>Limited (only Computations)</td>
 *     <td>Full (any Supplier&lt;Runnable&gt;)</td>
 *   </tr>
 *   <tr>
 *     <td>Debugging</td>
 *     <td>Harder (kernel internals opaque)</td>
 *     <td>Easier (step through Java code)</td>
 *   </tr>
 *   <tr>
 *     <td>First Execution</td>
 *     <td>Slow (compilation overhead)</td>
 *     <td>Fast (no compilation)</td>
 *   </tr>
 *   <tr>
 *     <td>Subsequent Runs</td>
 *     <td>Very fast (kernel cached)</td>
 *     <td>Moderate (repeated dispatch)</td>
 *   </tr>
 * </table>
 *
 * <h3>When to Use OperationList</h3>
 * <ul>
 *   <li><strong>Model layers:</strong> Compose multi-step transformations that compile to single kernel</li>
 *   <li><strong>Setup/teardown:</strong> Sequence initialization operations before/after training</li>
 *   <li><strong>Mixed execution:</strong> Combine hardware operations with logging/debugging</li>
 *   <li><strong>Hierarchical organization:</strong> Nest lists for logical grouping (forward/backward passes)</li>
 * </ul>
 *
 * <h3>When NOT to Use OperationList</h3>
 * <ul>
 *   <li><strong>Single operation:</strong> No need for list wrapper - use operation directly</li>
 *   <li><strong>Dynamic operation count:</strong> Cannot add/remove during execution</li>
 *   <li><strong>Branching logic:</strong> Use conditional operations instead of runtime list modification</li>
 * </ul>
 *
 * <h2>Lifecycle Management</h2>
 *
 * <p>Implements {@link Destroyable} to clean up all contained operations:</p>
 * <pre>{@code
 * OperationList ops = new OperationList();
 * ops.add(computation1);
 * ops.add(computation2);
 *
 * try {
 *     ops.get().run();
 * } finally {
 *     ops.destroy();  // Destroys all contained operations
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link OperationList} is <strong>not thread-safe</strong>. Concurrent modifications
 * require external synchronization. However, once {@link #get()} is called and the
 * {@link Runnable} is obtained, that runnable can be executed concurrently (though execution
 * of the same runnable from multiple threads may not be safe depending on the operations).</p>
 *
 * <h2>Debugging and Logging</h2>
 *
 * <p>Enable run logging to see operation execution order:</p>
 * <pre>
 * export AR_HARDWARE_RUN_LOGGING=true
 * </pre>
 *
 * <p>This logs each operation as it executes (only for sequential execution mode).</p>
 *
 * <h2>Common Pitfalls</h2>
 *
 * <h3>Mixing Computation and Non-Computation Operations</h3>
 * <pre>{@code
 * // BAD: Mixed list won't compile
 * OperationList ops = new OperationList();
 * ops.add(computation);  // Computation
 * ops.add(() -> () -> { System.out.println("Debug"); });  // Not Computation
 *
 * // GOOD: Separate concerns
 * OperationList compiled = new OperationList();
 * compiled.add(computation1);
 * compiled.add(computation2);
 *
 * OperationList withLogging = new OperationList(false);  // Disable compilation
 * withLogging.add(compiled);
 * withLogging.add(() -> () -> System.out.println("Done"));
 * }</pre>
 *
 * <h3>Exceeding Depth Limits</h3>
 * <pre>{@code
 * // BAD: Very deep nesting
 * OperationList outer = new OperationList();
 * for (int i = 0; i < 600; i++) {
 *     OperationList inner = new OperationList();
 *     inner.add(computation);
 *     outer = wrapInList(outer);  // Depth grows unbounded
 * }
 * outer.isComputation();  // false - exceeds maxDepth
 *
 * // GOOD: Flatten before compilation
 * OperationList deep = ...;
 * if (deep.getDepth() > maxDepth) {
 *     deep = deep.flatten();  // Reduces depth to 1
 * }
 * }</pre>
 *
 * <h3>Forgetting to Call get()</h3>
 * <pre>{@code
 * // BAD: OperationList is not directly executable
 * OperationList ops = new OperationList();
 * ops.add(computation);
 * ops.run();  // Convenience method, but prefer explicit get()
 *
 * // GOOD: Explicit compilation/preparation
 * OperationList ops = new OperationList();
 * ops.add(computation);
 * Runnable executable = ops.get();  // Compile or prepare
 * executable.run();  // Execute
 *
 * // BEST: Reuse compiled result
 * Runnable cached = ops.get();
 * for (int i = 0; i < 1000; i++) {
 *     cached.run();  // No recompilation overhead
 * }
 * }</pre>
 *
 * @see Computation
 * @see AcceleratedOperation
 * @see ComputeRequirement
 * @see OperationMetadata
 * @see OperationProfile
 *
 * @author  Michael Murray
 */
public class OperationList extends ArrayList<Supplier<Runnable>>
		implements OperationComputation<Void>,
					ComputableParallelProcess<Process<?, ?>, Runnable>,
					NamedFunction, Destroyable, ComputerFeatures {
	/** Enable logging of operation list execution (controlled by AR_HARDWARE_RUN_LOGGING environment variable). */
	public static boolean enableRunLogging = SystemUtils.isEnabled("AR_HARDWARE_RUN_LOGGING").orElse(false);

	/**
	 * Enable automatic optimization of operation lists before execution.
	 *
	 * <p>When {@code true}, {@link #get()} automatically calls {@link #optimize()} on any
	 * non-uniform list before compiling or executing it. When {@code false} (the default),
	 * callers must invoke {@link #optimize()} explicitly.</p>
	 *
	 * <p><strong>Why this matters:</strong> The {@code optimize()} call triggers the
	 * {@link io.almostrealism.compute.Process} optimization pipeline, which recursively
	 * optimizes children and evaluates whether each child should be <em>isolated</em>
	 * (wrapped in an {@code IsolatedProcess} to prevent expression embedding). Without
	 * optimization, computations that return {@code true} from
	 * {@link io.almostrealism.compute.Process#isIsolationTarget} are embedded directly
	 * into the parent scope's expression tree, which can cause massive expression trees,
	 * compilation timeouts, or stack overflows.</p>
	 *
	 * <p><strong>Risks of enabling:</strong></p>
	 * <ul>
	 *   <li>Code that already calls {@code optimize()} explicitly will optimize twice,
	 *       wasting compilation time</li>
	 *   <li>Every non-uniform {@code get()} call now pays the optimization overhead</li>
	 *   <li>Operations that were previously compiled as one kernel may be restructured
	 *       into multiple kernels, or vice versa</li>
	 *   <li>Previously non-isolated computations may become isolated, changing memory
	 *       allocation patterns</li>
	 *   <li>This is a global (static) flag — it affects all {@link OperationList} instances
	 *       in the JVM, including framework internals</li>
	 * </ul>
	 *
	 * <p><strong>Risks of disabling (when currently enabled):</strong></p>
	 * <ul>
	 *   <li>Computations requiring isolation will be embedded into parent expressions,
	 *       potentially causing compilation timeouts</li>
	 *   <li>Non-uniform lists will no longer be automatically segmented (if
	 *       {@link #enableSegmenting} is also {@code true})</li>
	 * </ul>
	 *
	 * <p><strong>Interaction with {@link #enableSegmenting}:</strong> Automatic optimization
	 * is the entry point that triggers segmentation. If this flag is {@code false},
	 * segmentation only runs when {@code optimize()} is called explicitly by the caller.</p>
	 *
	 * @see #enableSegmenting
	 * @see #get()
	 * @see <a href="docs/internals/operationlist-optimization-flags.md">Detailed Documentation</a>
	 */
	public static boolean enableAutomaticOptimization = false;

	/**
	 * Enable segmenting of non-uniform operation lists into groups of same-count operations.
	 *
	 * <p>When {@code true}, {@link #optimize(ProcessContext)} groups consecutive operations
	 * with the same parallelism count into sub-lists before delegating to the standard
	 * optimization pipeline. Each sub-list can then compile as a single kernel, reducing
	 * the total number of JNI transitions.</p>
	 *
	 * <p><strong>Example:</strong> Given operations {@code [scalar(1), scalar(1), vector(4096),
	 * vector(4096), scalar(1)]}, segmentation produces three groups:</p>
	 * <ol>
	 *   <li>{@code [scalar(1), scalar(1)]} — compiles as 1 kernel</li>
	 *   <li>{@code [vector(4096), vector(4096)]} — compiles as 1 kernel</li>
	 *   <li>{@code [scalar(1)]} — compiles as 1 kernel</li>
	 * </ol>
	 * <p>This reduces 5 individual kernel dispatches to 3.</p>
	 *
	 * <p><strong>Prerequisites for activation:</strong> Segmentation only runs when all
	 * of the following are true:</p>
	 * <ul>
	 *   <li>{@code enableSegmenting == true}</li>
	 *   <li>The list has more than 1 element</li>
	 *   <li>The list is non-uniform (children have different counts)</li>
	 *   <li>At least two adjacent operations have the same count</li>
	 * </ul>
	 * <p>If any condition is not met, {@code optimize()} falls through to the standard
	 * {@link io.almostrealism.code.ComputableParallelProcess#optimize(ProcessContext)} path.</p>
	 *
	 * <p><strong>Risks of enabling:</strong></p>
	 * <ul>
	 *   <li>The process tree is restructured: sub-lists are created, changing the shape
	 *       visible to profiling and inspection tools</li>
	 *   <li>Each segment compiles independently, potentially increasing total cold-start
	 *       compilation time</li>
	 *   <li>This is a global (static) flag affecting all {@link OperationList} instances</li>
	 * </ul>
	 *
	 * <p><strong>Risks of disabling (when currently enabled):</strong></p>
	 * <ul>
	 *   <li>Non-uniform lists dispatch each operation as a separate kernel call, increasing
	 *       JNI transition overhead</li>
	 *   <li>Performance regression in pipelines that rely on kernel fusion (e.g. AudioScene)</li>
	 * </ul>
	 *
	 * <p><strong>Important:</strong> Segmentation only runs during {@code optimize()}. If
	 * {@link #enableAutomaticOptimization} is {@code false}, segmentation only takes effect
	 * when the caller invokes {@code optimize()} explicitly.</p>
	 *
	 * @see #enableAutomaticOptimization
	 * @see #optimize(ProcessContext)
	 * @see <a href="docs/internals/operationlist-optimization-flags.md">Detailed Documentation</a>
	 */
	public static boolean enableSegmenting = false;

	/**
	 * Enable non-uniform compilation where operations with different counts can be compiled
	 * into a single hardware kernel.
	 *
	 * <p>When {@code true}, allows {@link #get()} to compile non-uniform lists directly
	 * into a single {@link AcceleratedOperation} kernel, bypassing the requirement that
	 * all operations must have the same parallelism count.</p>
	 *
	 * <p><strong>WARNING: This flag carries significant correctness risk.</strong> A kernel
	 * compiled from operations with counts 1 and 4096 must dispatch with a single work-item
	 * count. The generated code may not correctly handle mixed-count operations in a single
	 * kernel, potentially producing <strong>silent incorrect results</strong> (e.g., only
	 * computing the first element of a 4096-element vector).</p>
	 *
	 * <p><strong>Interaction with other flags:</strong> This check is evaluated after
	 * {@link #enableAutomaticOptimization}. If automatic optimization is enabled, non-uniform
	 * lists are routed to {@code optimize().get()} first, which typically segments them into
	 * uniform sub-lists. This flag is only reached when automatic optimization is disabled
	 * or when the list is already uniform.</p>
	 *
	 * <p><strong>Precedence in {@link #get()}:</strong></p>
	 * <ol>
	 *   <li>If {@code enableAutomaticOptimization && !isUniform()} → optimize and recurse</li>
	 *   <li>If {@code isComputation() && (enableNonUniformCompilation || isUniform())} → compile to single kernel</li>
	 *   <li>Otherwise → sequential {@link Runner} execution</li>
	 * </ol>
	 *
	 * @see #enableAutomaticOptimization
	 * @see #enableSegmenting
	 * @see <a href="docs/internals/operationlist-optimization-flags.md">Detailed Documentation</a>
	 */
	public static boolean enableNonUniformCompilation = false;

	/** Thread-local flag set when a kernel abort has been triggered; holds the offending MemoryData. */
	private static ThreadLocal<MemoryData> abortFlag;
	/** If true, abort when argument evaluation fails; if false, abort when scope construction fails. */
	private static boolean abortArgs;
	/** If true, abort when scope construction (rather than argument evaluation) fails. */
	private static boolean abortScope;
	/** Optional abort handler invoked when compilation or evaluation is aborted. */
	private static Abort abort;

	static {
		abortFlag = new ThreadLocal<>();
	}

	/** Maximum nesting depth of operation lists before compilation is forced. */
	private static int maxDepth = 500;
	/** Nesting depth at which an operation list is considered abortable. */
	private static int abortableDepth = 1000;
	/** Monotonically increasing counter used to generate unique function names. */
	private static long functionCount = 0;

	/** If true, this operation list may be compiled to a native kernel. */
	private boolean enableCompilation;
	/** Unique name used as the generated function name when this list is compiled. */
	private String functionName;
	/** Human-readable description for logging and profiling. */
	private String description;
	/** Optional fixed element count; when set, the kernel is sized to this value. */
	private Long count;

	/** Metadata describing the operation for profiling, naming, and context identification. */
	private OperationMetadata metadata;
	/** Optional profile for recording timing and invocation statistics per operation. */
	private OperationProfile profile;
	/** Compute requirements specifying which backend(s) this list should be executed on. */
	private List<ComputeRequirement> requirements;

	/**
	 * Creates an empty operation list with no description.
	 */
	public OperationList() { this(null); }

	/**
	 * Creates an empty operation list with a description and compilation enabled.
	 *
	 * @param description Human-readable description of this operation list
	 */
	public OperationList(String description) { this(description, true); }

	/**
	 * Creates an empty operation list with explicit compilation control.
	 *
	 * @param description Human-readable description of this operation list
	 * @param enableCompilation Whether to enable compilation to hardware kernels
	 */
	public OperationList(String description, boolean enableCompilation) {
		this.enableCompilation = enableCompilation;
		this.functionName = "operations_" + functionCount++;
		this.description = description;
	}

	/**
	 * Sets the function name for this operation list (used in compiled code).
	 *
	 * @param name The function name
	 */
	@Override
	public void setFunctionName(String name) { this.functionName = name; }

	/**
	 * Returns the function name for this operation list.
	 *
	 * @return The function name
	 */
	@Override
	public String getFunctionName() { return this.functionName; }

	/**
	 * Returns the operation metadata for profiling and identification.
	 *
	 * @return The operation metadata
	 */
	@Override
	public OperationMetadata getMetadata() {
		if (metadata == null) {
			metadata = OperationInfo.metadataForProcess(this, new OperationMetadata(functionName, description));
		}

		return metadata;
	}

	/**
	 * Returns the operation profile for timing collection.
	 *
	 * @return The operation profile, or null if not set
	 */
	public OperationProfile getProfile() { return profile; }

	/**
	 * Sets the operation profile for timing collection.
	 *
	 * @param profile The operation profile to use
	 */
	public void setProfile(OperationProfile profile) { this.profile = profile; }

	/**
	 * Sets compute requirements for context selection.
	 *
	 * <p>These requirements are pushed onto the computer's requirement stack
	 * during execution to force specific backend selection (GPU, CPU, etc.).</p>
	 *
	 * @param requirements The compute requirements
	 */
	public void setComputeRequirements(List<ComputeRequirement> requirements) { this.requirements = requirements; }

	/**
	 * Returns the compute requirements for this operation list.
	 *
	 * @return The compute requirements, or null if none specified
	 */
	@Override
	public List<ComputeRequirement> getComputeRequirements() { return requirements; }

	/**
	 * Adds a compiled operation supplier to this list.
	 *
	 * <p>The supplier is resolved when the operation list is executed.</p>
	 *
	 * @param op Supplier of the compiled runnable
	 */
	public void addCompiled(Supplier<Runnable> op) {
		add(() -> op.get());
	}

	/**
	 * Returns the count for this operation list.
	 *
	 * <p>If the list is uniform (all operations have the same count), returns
	 * that count. Otherwise returns 1.</p>
	 *
	 * @return The operation count
	 */
	@Override
	public long getCountLong() {
		if (count == null) {
			if (isEmpty()) {
				count = 0L;
			} else if (isUniform() && get(0) instanceof Countable) {
				count = ((Countable) get(0)).getCountLong();
			} else {
				count = 1L;
			}
		}

		return count;
	}

	/**
	 * Compiles and returns a runnable for this operation list.
	 *
	 * <p>Automatically selects the best execution strategy (compilation,
	 * optimization, or sequential execution) based on list characteristics.</p>
	 *
	 * @return Runnable that executes all operations in this list
	 */
	@Override
	public Runnable get() {
		return get(getProfile());
	}

	/**
	 * Compiles and returns a runnable with explicit profiling.
	 *
	 * @param profile The operation profile for timing collection, or null
	 * @return Runnable that executes all operations in this list
	 */
	public Runnable get(OperationProfile profile) {
		if (isFunctionallyEmpty()) return () -> { };

		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			if (profile instanceof OperationProfileNode) {
				((OperationProfileNode) profile).addChild(getMetadata());
			}

			if (enableAutomaticOptimization && !isUniform()) {
				return optimize().get();
			} else if (isComputation() && (enableNonUniformCompilation || isUniform())) {
				AcceleratedOperation op = (AcceleratedOperation) compileRunnable(this);
				op.setFunctionName(functionName);
				op.load();
				return op;
			} else {
				if (isComputation()) {
					warn("OperationList was not compiled (uniform = " + isUniform() + ")");
				}

				List<Runnable> run = stream().map(Supplier::get).collect(Collectors.toList());
				if (run.size() == 1) {
					return run.get(0);
				}

				return new Runner(getMetadata(), run, getComputeRequirements(),
						profile == null ? null : profile.getTimingListener());
			}
		} finally {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	/**
	 * Returns whether this list can be compiled as a single computation.
	 *
	 * <p>Returns true if compilation is enabled, depth is within limits,
	 * and all operations (including nested OperationLists) implement
	 * {@link Computation}. Compiled operation lists execute as a single
	 * hardware kernel dispatch.</p>
	 *
	 * @return true if this list can be compiled to a hardware kernel
	 */
	public boolean isComputation() {
		if (!enableCompilation) return false;
		if (getDepth() > maxDepth) return false;

		int nonComputations = stream().mapToInt(o -> {
			if (o instanceof OperationList) {
				return ((OperationList) o).isComputation() ? 0 : 1;
			} else {
				return o instanceof Computation ? 0 : 1;
			}
		}).sum();

		return nonComputations == 0;
	}

	/**
	 * Prepares arguments for all operations in this list.
	 *
	 * <p>Delegates to {@link ScopeLifecycle#prepareArguments} for all operations.
	 * If an abort flag is set, also prepares the abort operation.</p>
	 *
	 * @param map The argument map to populate
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(stream(), map);
		if (abortFlag != null & !abortArgs) {
			if (abort == null) abort = new Abort(abortFlag::get);
			abortArgs = true;
			abort.prepareArguments(map);
		}
	}

	/**
	 * Prepares scope for all operations in this list.
	 *
	 * <p>Delegates to {@link ScopeLifecycle#prepareScope} for all operations.
	 * If an abort flag is set, also prepares the abort operation's scope.</p>
	 *
	 * @param manager The scope input manager
	 * @param context The kernel structure context
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		ScopeLifecycle.prepareScope(stream(), manager, context);
		if (!abortScope) {
			if (abort == null) abort = new Abort(abortFlag::get);
			abortScope = true;
			abort.prepareScope(manager, context);
		}
	}

	/**
	 * Returns the combined scope for all operations in this list.
	 *
	 * <p>Concatenates the scopes from all operations into a single scope
	 * for compilation into a single hardware kernel.</p>
	 *
	 * @param context The kernel structure context
	 * @return Combined scope containing all operations
	 * @throws IllegalArgumentException if not all operations are compilable
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		if (!isComputation()) {
			throw new IllegalArgumentException(
					"OperationList cannot be compiled to a Scope " +
					"unless all embedded Operations are Computations");
		}

		Scope<Void> scope = new Scope<>(functionName, getMetadata());
		scope.setComputeRequirements(getComputeRequirements());

		if (getDepth() > abortableDepth) {
			stream().flatMap(c -> Stream.of(c, abort))
					.map(o -> ((Computation) o).getScope(context)).forEach(scope::add);
		} else {
			stream().map(o -> ((Computation) o).getScope(context)).forEach(scope::add);
		}

		return scope;
	}

	@Override
	public OperationList generate(List<Process<?, ?>> children) {
		OperationList list = new OperationList();
		list.enableCompilation = enableCompilation;
		list.setComputeRequirements(getComputeRequirements());
		children.forEach(c -> list.add((Supplier) c));
		return list;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return stream()
				.filter(o -> !(o instanceof OperationList) || !((OperationList) o).isFunctionallyEmpty())
				.map(o -> o instanceof Process ? (Process<?, ?>) o : Process.of(o))
				.collect(Collectors.toList());
	}

	/**
	 * Returns true if this operation list contains no operations, or contains only empty nested lists.
	 *
	 * <p>An operation list is functionally empty even when it has entries if all entries are
	 * themselves functionally empty {@link OperationList} instances.</p>
	 *
	 * @return True if there is no executable work in this list or any nested lists
	 */
	public boolean isFunctionallyEmpty() {
		if (isEmpty()) return true;
		return stream().noneMatch(o -> !(o instanceof OperationList) || !((OperationList) o).isFunctionallyEmpty());
	}

	/**
	 * Returns the maximum nesting depth of non-empty operation lists within this list.
	 *
	 * @return Nesting depth; 0 for a functionally empty list
	 */
	public int getDepth() {
		if (isFunctionallyEmpty()) return 0;

		return stream().map(c -> c instanceof OperationList ? (OperationList) c : null).filter(Objects::nonNull)
				.mapToInt(OperationList::getDepth).max().orElse(0) + 1;
	}

	/**
	 * Flattens nested operation lists into a single level where possible.
	 *
	 * <p>Nested lists that have no specific compute requirements are inlined into the parent.
	 * Lists with explicit requirements are kept as nested entries.</p>
	 *
	 * @return A new flattened {@link OperationList}
	 */
	public OperationList flatten() {
		OperationList flat = stream()
				.flatMap(o -> {
					if (o instanceof OperationList) {
						OperationList op = ((OperationList) o).flatten();

						if (op.getComputeRequirements() == null) {
							return op.stream();
						} else {
							return Stream.of(op);
						}
					} else {
						return Stream.of(o);
					}
				})
				.collect(OperationList.collector());
		flat.enableCompilation = enableCompilation;
		flat.setComputeRequirements(getComputeRequirements());
		return flat;
	}

	public void run() { get().run(); }

	/**
	 * Optimizes this operation list, optionally segmenting it by parallelism count.
	 *
	 * <p>When {@link #enableSegmenting} is {@code true} and the list is non-uniform with
	 * at least two adjacent same-count operations, this method groups consecutive operations
	 * with the same parallelism count into sub-{@link OperationList}s. Each sub-list is
	 * uniform and can compile as a single kernel, reducing JNI transition overhead.</p>
	 *
	 * <p><strong>Segmentation algorithm:</strong></p>
	 * <ol>
	 *   <li>If segmenting is disabled, list has &le;1 element, list is already uniform,
	 *       or no two adjacent operations have the same count, delegate to
	 *       {@link io.almostrealism.code.ComputableParallelProcess#optimize(ProcessContext)}</li>
	 *   <li>Otherwise, iterate through operations, grouping consecutive operations with
	 *       the same {@link Countable#countLong(Object)} value</li>
	 *   <li>Single-element groups are unwrapped (the operation is added directly)</li>
	 *   <li>Multi-element groups become new {@link OperationList} sub-lists</li>
	 *   <li>The resulting list is recursively optimized via {@code op.optimize(context)}.
	 *       Since each segment is now uniform, the recursion terminates immediately
	 *       (the {@code isUniform()} guard at the top returns {@code true}).</li>
	 * </ol>
	 *
	 * <p><strong>Note:</strong> This method only groups consecutive same-count operations.
	 * It does <em>not</em> reorder operations. Execution order is always preserved.</p>
	 *
	 * @param context The process context for optimization decisions
	 * @return The optimized process, potentially restructured with sub-lists
	 * @see #enableSegmenting
	 * @see #enableAutomaticOptimization
	 */
	@Override
	public ParallelProcess<Process<?, ?>, Runnable> optimize(ProcessContext context) {
		if (!enableSegmenting || size() <= 1 || isUniform()) return ComputableParallelProcess.super.optimize(context);

		boolean match = IntStream.range(1, size()).anyMatch(i -> Countable.countLong(get(i - 1)) == Countable.countLong(get(i)));
		if (!match) return ComputableParallelProcess.super.optimize(context);

		OperationList op = new OperationList();
		OperationList current = new OperationList();
		long currentCount = -1;

		for (int i = 0; i < size(); i++) {
			Supplier<Runnable> o = get(i);
			long count = Countable.countLong(o);

			if (currentCount == -1 || currentCount == count) {
				current.add(o);
			} else {
				op.add(current.size() == 1 ? current.get(0) : current);
				current = new OperationList();
				current.add(o);
			}

			currentCount = count;
		}

		if (current.size() > 0) op.add(current.size() == 1 ? current.get(0) : current);

		return op.optimize(context);
	}

	@Override
	public void destroy() {
		forEach(Destroyable::destroy);
	}

	@Override
	public Supplier<Runnable> set(int index, Supplier<Runnable> element) {
		count = null;
		metadata = null;
		return super.set(index, element);
	}

	@Override
	public boolean add(Supplier<Runnable> runnableSupplier) {
		count = null;
		metadata = null;
		return super.add(runnableSupplier);
	}

	@Override
	public void add(int index, Supplier<Runnable> element) {
		count = null;
		metadata = null;
		super.add(index, element);
	}

	@Override
	public Supplier<Runnable> remove(int index) {
		count = null;
		metadata = null;
		return super.remove(index);
	}

	@Override
	public boolean remove(Object o) {
		count = null;
		metadata = null;
		return super.remove(o);
	}

	@Override
	public void clear() {
		count = null;
		metadata = null;
		super.clear();
	}

	@Override
	public boolean addAll(Collection<? extends Supplier<Runnable>> c) {
		count = null;
		metadata = null;
		return super.addAll(c);
	}

	@Override
	public boolean addAll(int index, Collection<? extends Supplier<Runnable>> c) {
		count = null;
		metadata = null;
		return super.addAll(index, c);
	}

	@Override
	protected void removeRange(int fromIndex, int toIndex) {
		count = null;
		metadata = null;
		super.removeRange(fromIndex, toIndex);
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		if (super.removeAll(c)) {
			count = null;
			metadata = null;
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		if (super.retainAll(c)) {
			count = null;
			metadata = null;
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Returns a human-readable description of this operation list.
	 *
	 * @return Description including operation count and requirements
	 */
	@Override
	public String describe() {
		return Optional.ofNullable(getMetadata().getShortDescription()).orElse("") +
				" " + getCount() + "x " +
				(getComputeRequirements() == null ? "" : Arrays.toString(getComputeRequirements().toArray()));
	}

	/**
	 * Returns a collector that accumulates runnables into an {@link OperationList}.
	 *
	 * @return Stream collector for building operation lists
	 */
	public static Collector<Supplier<Runnable>, ?, OperationList> collector() {
		return Collectors.toCollection(OperationList::new);
	}

	/**
	 * Sets the thread-local abort flag for operation cancellation.
	 *
	 * @param flag Memory data flag for signaling abort
	 */
	public static void setAbortFlag(MemoryData flag) { abortFlag.set(flag); }

	/**
	 * Returns the current thread's abort flag.
	 *
	 * @return The abort flag, or null if not set
	 */
	public static MemoryData getAbortFlag() { return abortFlag.get(); }

	/**
	 * Removes the abort flag for the current thread.
	 */
	public static void removeAbortFlag() { abortFlag.remove(); }

	protected static void setMaxDepth(int depth) { maxDepth = depth; }

	protected static void setAbortableDepth(int depth) { abortableDepth = depth; }

	/**
	 * Compiled runner for executing a sequence of operations with metadata and timing support.
	 */
	public static class Runner implements Runnable, Destroyable, OperationInfo, ConsoleFeatures {
		/** Metadata describing this runner for profiling and identification. */
		private OperationMetadata metadata;
		/** Sequence of compiled runnables to execute when this runner is invoked. */
		private List<Runnable> run;
		/** Compute requirements constraining which backend executes this runner; may be null. */
		private List<ComputeRequirement> requirements;
		/** Listener notified with timing information after each run; may be null. */
		private OperationTimingListener timingListener;

		/**
		 * Creates a runner for the specified operations.
		 *
		 * @param metadata Operation metadata for identification
		 * @param run List of runnables to execute sequentially
		 * @param requirements Compute requirements (may be null)
		 * @param timingListener Timing listener for profiling (may be null)
		 */
		public Runner(OperationMetadata metadata, List<Runnable> run,
					  List<ComputeRequirement> requirements,
					  OperationTimingListener timingListener) {
			this.metadata = metadata;
			this.run = run;
			this.requirements = requirements;
			this.timingListener = timingListener;
		}

		/**
		 * Returns the operation metadata.
		 *
		 * @return The metadata
		 */
		@Override
		public OperationMetadata getMetadata() { return metadata; }

		/**
		 * Returns the list of operations to execute.
		 *
		 * @return List of runnables
		 */
		public List<Runnable> getOperations() { return run; }

		/**
		 * Executes all operations in sequence with compute requirements and timing.
		 */
		@Override
		public void run() {
			try {
				if (requirements != null) {
					Hardware.getLocalHardware().getComputer().pushRequirements(requirements);
				}

				if (timingListener == null) {
					for (int i = 0; i < run.size(); i++) {
						run.get(i).run();
					}
				} else {
					for (int i = 0; i < run.size(); i++) {
						if (enableRunLogging)
							log("Running " + OperationInfo.display(run.get(i)));
						timingListener.recordDuration(getMetadata(), run.get(i));
					}
				}
			} finally {
				if (requirements != null) {
					Hardware.getLocalHardware().getComputer().popRequirements();
				}
			}
		}

		/**
		 * Returns a description of this runner.
		 *
		 * @return The short description from metadata
		 */
		@Override
		public String describe() {
			return getMetadata().getShortDescription();
		}

		/**
		 * Destroys all operations and releases resources.
		 */
		@Override
		public void destroy() {
			if (run == null) return;

			run.forEach(Destroyable::destroy);
			run = null;
		}

		/**
		 * Returns the console for logging.
		 *
		 * @return The hardware console
		 */
		@Override
		public Console console() { return Hardware.console; }
	}
}
