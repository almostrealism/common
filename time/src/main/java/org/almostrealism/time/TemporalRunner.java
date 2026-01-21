/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.time;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.compute.Process;
import io.almostrealism.cycle.Setup;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.OperationList;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestrates the setup and execution of {@link Temporal} operations, managing initialization,
 * iteration, optimization, and resource lifecycle for efficient hardware-accelerated execution.
 *
 * <p>{@link TemporalRunner} acts as the execution engine for temporal operations, separating
 * one-time setup costs from repeated tick execution. This separation enables significant performance
 * improvements by compiling and optimizing operations once, then executing them many times.</p>
 *
 * <h2>Core Responsibilities</h2>
 * <ul>
 *   <li><strong>Setup Management:</strong> Executes initialization operations once before ticking</li>
 *   <li><strong>Iteration Control:</strong> Runs tick operations for a specified number of iterations</li>
 *   <li><strong>Optimization:</strong> Optionally applies hardware-specific optimizations</li>
 *   <li><strong>Compilation:</strong> Compiles operations for GPU/accelerator execution</li>
 *   <li><strong>Lifecycle Management:</strong> Properly manages operation resources and cleanup</li>
 * </ul>
 *
 * <h2>Execution Model</h2>
 *
 * <h3>Two-Phase Execution</h3>
 * <p>Temporal operations typically follow a two-phase execution model:</p>
 * <ol>
 *   <li><strong>Setup Phase:</strong> Initialize state, allocate memory, compile kernels (runs once)</li>
 *   <li><strong>Tick Phase:</strong> Execute the main operation logic (runs multiple times)</li>
 * </ol>
 *
 * <h3>Compilation and Caching</h3>
 * <p>When {@link #get()} is first called, the runner:</p>
 * <ol>
 *   <li>Compiles both setup and tick operations</li>
 *   <li>Caches the compiled runnables</li>
 *   <li>Returns a combined runnable that executes setup then tick</li>
 * </ol>
 *
 * <p>Subsequent calls reuse the cached compilation, avoiding overhead.</p>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Basic Buffered Execution</h3>
 * <pre>{@code
 * Temporal oscillator = createOscillator(440.0);
 *
 * // Buffer 1024 ticks (e.g., audio samples)
 * TemporalRunner runner = oscillator.buffer(1024);
 *
 * // First call: runs setup + 1024 ticks
 * runner.get().run();
 *
 * // Subsequent calls: skip setup, run only ticks
 * runner.getContinue().run();
 * runner.getContinue().run();
 * }</pre>
 *
 * <h3>Manual Construction</h3>
 * <pre>{@code
 * Supplier<Runnable> setup = () -> () -> {
 *     // Initialize buffers, compile kernels, etc.
 * };
 *
 * Supplier<Runnable> tick = () -> () -> {
 *     // Process one step
 * };
 *
 * // Run tick 512 times per invocation
 * TemporalRunner runner = new TemporalRunner(setup, tick, 512);
 * runner.get().run();  // Setup once, tick 512 times
 * }</pre>
 *
 * <h3>Optimization Control</h3>
 * <pre>{@code
 * Temporal operation = ...;
 *
 * // Enable optimization for single-iteration execution
 * TemporalRunner.enableOptimization = true;
 * TemporalRunner optimized = new TemporalRunner(
 *     operation.setup(),
 *     operation.tick(),
 *     1,  // Single iteration
 *     true  // Force optimization
 * );
 * }</pre>
 *
 * <h2>Configuration Flags</h2>
 * <p>Static configuration flags control runner behavior:</p>
 * <ul>
 *   <li><strong>{@link #enableFlatten} (default: true):</strong> Flatten nested OperationLists
 *       for better performance</li>
 *   <li><strong>{@link #enableOptimization} (default: false):</strong> Apply hardware-specific
 *       optimizations automatically</li>
 *   <li><strong>{@link #enableIsolation} (default: false):</strong> Isolate operations for
 *       independent execution</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 *
 * <h3>Setup Cost Amortization</h3>
 * <pre>
 * Cost without buffering:  N * (setup + tick)
 * Cost with buffering:     1 * setup + N * tick
 *
 * For N=1000 iterations:
 *   Without buffering: 1000 setups + 1000 ticks
 *   With buffering:    1 setup + 1000 ticks
 * </pre>
 *
 * <h3>Optimization Impact</h3>
 * <p>Optimization can provide 2-10* speedup by:</p>
 * <ul>
 *   <li>Fusing operations to reduce kernel launches</li>
 *   <li>Eliminating redundant memory transfers</li>
 *   <li>Applying platform-specific optimizations</li>
 * </ul>
 *
 * <h2>Common Use Cases</h2>
 *
 * <h3>Audio Processing</h3>
 * <pre>{@code
 * // Process audio in 512-sample buffers
 * Temporal audioChain = ...;
 * TemporalRunner runner = audioChain.buffer(512);
 *
 * while (streaming) {
 *     runner.getContinue().run();  // Process next 512 samples
 * }
 * }</pre>
 *
 * <h3>Real-Time Rendering</h3>
 * <pre>{@code
 * // Render frames at 60 FPS
 * Temporal renderPipeline = ...;
 * TemporalRunner runner = renderPipeline.buffer(1);
 *
 * while (running) {
 *     runner.getContinue().run();  // Render one frame
 *     display.refresh();
 * }
 * }</pre>
 *
 * <h3>Batch Processing</h3>
 * <pre>{@code
 * // Process 10,000 iterations efficiently
 * Temporal simulation = ...;
 * TemporalRunner runner = simulation.buffer(10000);
 *
 * runner.get().run();  // Setup + 10,000 iterations
 * }</pre>
 *
 * <h2>Resource Management</h2>
 * <p>{@link TemporalRunner} implements {@link Destroyable} for proper resource cleanup:</p>
 * <pre>{@code
 * TemporalRunner runner = operation.buffer(1024);
 * try {
 *     runner.get().run();
 * } finally {
 *     runner.destroy();  // Release GPU memory, close files, etc.
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link TemporalRunner} is not thread-safe. For concurrent execution:</p>
 * <ul>
 *   <li>Create separate runner instances per thread</li>
 *   <li>Use external synchronization if sharing a runner</li>
 *   <li>Consider using {@link #enableIsolation} for independent execution contexts</li>
 * </ul>
 *
 * @see Temporal
 * @see Setup
 * @see OperationComputation
 * @see Destroyable
 *
 * @author Michael Murray
 */
public class TemporalRunner implements OperationComputation<Void>, Setup, Temporal, Destroyable {
	/**
	 * When true, flattens nested {@link OperationList} structures to reduce overhead.
	 * Default: true
	 */
	public static boolean enableFlatten = true;

	/**
	 * When true, applies hardware-specific optimizations to operations.
	 * Default: false (due to potential compilation overhead)
	 */
	public static boolean enableOptimization = false;

	/**
	 * When true, isolates operations for independent execution contexts.
	 * Default: false
	 */
	public static boolean enableIsolation = false;

	private Supplier<Runnable> setup, run;
	private Runnable s, r;

	private OperationProfile profile;

	/**
	 * Constructs a runner for a temporal operation with setup/tick separation.
	 *
	 * <p>This constructor extracts setup and tick from the temporal operation and
	 * creates a runner that executes the specified number of iterations.</p>
	 *
	 * @param o The temporal operation (must also implement {@link Setup})
	 * @param iter The number of tick iterations per execution
	 * @throws ClassCastException if {@code o} does not implement {@link Setup}
	 */
	public TemporalRunner(Temporal o, int iter) {
		this(((Setup) o).setup(), o.tick(), iter);
	}

	/**
	 * Constructs a runner with explicit setup and tick suppliers, single iteration.
	 *
	 * @param setup The setup operation supplier
	 * @param tick The tick operation supplier
	 */
	public TemporalRunner(Supplier<Runnable> setup, Supplier<Runnable> tick) {
		this(setup, tick, 1);
	}

	/**
	 * Constructs a runner with explicit setup and tick suppliers and optimization control.
	 *
	 * @param setup The setup operation supplier
	 * @param tick The tick operation supplier
	 * @param optimize If true, apply optimizations to both setup and tick
	 */
	public TemporalRunner(Supplier<Runnable> setup, Supplier<Runnable> tick, boolean optimize) {
		this(setup, tick, 1, optimize);
	}

	/**
	 * Constructs a runner with explicit setup, tick, and iteration count.
	 *
	 * <p>Optimization is automatically enabled if {@link #enableOptimization} is true,
	 * or if the iteration count is greater than 1 (since multi-iteration loops require
	 * proper expression isolation to avoid exponential slowdown).</p>
	 *
	 * @param setup The setup operation supplier
	 * @param tick The tick operation supplier
	 * @param iter The number of tick iterations per execution
	 */
	public TemporalRunner(Supplier<Runnable> setup, Supplier<Runnable> tick, int iter) {
		this(setup, tick, iter, enableOptimization || iter > 1);
	}

	/**
	 * Constructs a runner with full control over all parameters.
	 *
	 * <p>This is the primary constructor that all others delegate to. It performs:</p>
	 * <ol>
	 *   <li>Optional flattening of nested OperationLists (if {@link #enableFlatten})</li>
	 *   <li>Optional isolation of operations (if {@link #enableIsolation})</li>
	 *   <li>Creation of iteration loop (if {@code iter > 1})</li>
	 *   <li>Optional optimization (if {@code optimize == true})</li>
	 * </ol>
	 *
	 * @param setup The setup operation supplier
	 * @param tick The tick operation supplier
	 * @param iter The number of tick iterations per execution
	 * @param optimize If true, apply hardware-specific optimizations
	 */
	public TemporalRunner(Supplier<Runnable> setup, Supplier<Runnable> tick, int iter, boolean optimize) {
		if (enableFlatten && tick instanceof OperationList) {
			tick = ((OperationList) tick).flatten();
		}

		if (enableIsolation) {
			tick = Process.isolated(tick);
		}

		this.run = iter == 1 ? tick : loop(tick, iter);

		if (optimize) {
			run = Process.optimized(run);
		}

		if (enableFlatten && setup instanceof OperationList) {
			setup = ((OperationList) setup).flatten();
		}

		this.setup = optimize ? Process.optimized(setup) : setup;
	}

	/**
	 * Returns the operation profile used for compilation hints.
	 *
	 * @return The current operation profile, or null if not set
	 */
	public OperationProfile getProfile() {
		return profile;
	}

	/**
	 * Sets the operation profile for compilation hints.
	 *
	 * <p>The profile provides hints to the compiler about expected execution
	 * characteristics, enabling platform-specific optimizations.</p>
	 *
	 * @param profile The operation profile to use
	 */
	public void setProfile(OperationProfile profile) {
		this.profile = profile;
	}

	/**
	 * Returns the setup operation supplier.
	 *
	 * @return The setup supplier that initializes the operation
	 */
	@Override
	public Supplier<Runnable> setup() { return setup; }

	/**
	 * Returns the tick operation supplier.
	 *
	 * <p>Note: This returns the iteration-wrapped tick, not the original tick supplier.</p>
	 *
	 * @return The tick supplier that executes one or more iterations
	 */
	@Override
	public Supplier<Runnable> tick() { return run; }

	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(Stream.of(setup), map);
		ScopeLifecycle.prepareArguments(Stream.of(run), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		ScopeLifecycle.prepareScope(Stream.of(setup), manager, context);
		ScopeLifecycle.prepareScope(Stream.of(run), manager, context);
	}

	/**
	 * Compiles the setup and tick operations if not already compiled.
	 *
	 * <p>This method is idempotent - calling it multiple times has no effect after
	 * the first compilation. Compilation results are cached in {@code s} and {@code r}.</p>
	 *
	 * <h3>Compilation Process</h3>
	 * <ol>
	 *   <li>Check if already compiled (if s or r is non-null, return immediately)</li>
	 *   <li>Compile setup using the profile if available</li>
	 *   <li>Compile run (tick) using the profile if available</li>
	 *   <li>Cache compiled runnables for reuse</li>
	 * </ol>
	 */
	public void compile() {
		if (s != null || r != null) return;

		s = setup instanceof OperationList ? ((OperationList) setup).get(profile) : setup.get();
		r = run instanceof OperationList ? ((OperationList) run).get(profile) : run.get();
	}

	/**
	 * Returns a runnable that executes setup followed by the tick iterations.
	 *
	 * <p>This method triggers compilation if not already done, then returns a runnable
	 * that executes both the setup and tick phases. Subsequent calls return the same
	 * compiled runnable.</p>
	 *
	 * <h3>Usage Pattern</h3>
	 * <pre>{@code
	 * TemporalRunner runner = operation.buffer(1024);
	 *
	 * // First execution: setup + 1024 ticks
	 * runner.get().run();
	 *
	 * // For subsequent executions, use getContinue() to skip setup
	 * runner.getContinue().run();
	 * }</pre>
	 *
	 * @return A runnable that executes setup then tick
	 */
	@Override
	public Runnable get() {
		compile();

		return () -> {
			s.run();
			r.run();
		};
	}

	/**
	 * Returns a runnable that executes only the tick iterations, skipping setup.
	 *
	 * <p>This method is useful for continuing execution after the initial setup has
	 * been performed. It triggers compilation if needed, then returns a runnable that
	 * executes only the tick phase.</p>
	 *
	 * <h3>Use Case: Streaming Audio</h3>
	 * <pre>{@code
	 * TemporalRunner processor = audioChain.buffer(512);
	 *
	 * // Initialize once
	 * processor.get().run();  // Setup + first 512 samples
	 *
	 * // Stream subsequent buffers
	 * while (streaming) {
	 *     processor.getContinue().run();  // Skip setup, just process 512 samples
	 *     sendToOutput(buffer);
	 * }
	 * }</pre>
	 *
	 * @return A runnable that executes only tick iterations
	 */
	public Runnable getContinue() {
		compile();
		return r;
	}

	/**
	 * Throws {@link UnsupportedOperationException}.
	 *
	 * <p>{@link TemporalRunner} does not support scope extraction as it manages
	 * execution through compiled runnables rather than direct scope manipulation.</p>
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the child processes contained in this runner.
	 *
	 * <p>This method is used for operation tree analysis and optimization.
	 * It returns the compiled setup and tick runnables if they are processes.</p>
	 *
	 * @return Collection of child processes
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() {
		return Stream.of(s, r)
				.map(o -> o instanceof Process ? (Process<?, ?>) o : null)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
	}

	/**
	 * Destroys this runner and releases all associated resources.
	 *
	 * <p>This method propagates destruction to the setup and run operations,
	 * allowing them to release GPU memory, close files, and perform other cleanup.
	 * After calling destroy, the runner should not be used.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * TemporalRunner runner = operation.buffer(1024);
	 * try {
	 *     for (int i = 0; i < batches; i++) {
	 *         runner.getContinue().run();
	 *     }
	 * } finally {
	 *     runner.destroy();
	 * }
	 * }</pre>
	 */
	@Override
	public void destroy() {
		Stream.of(setup).forEach(Destroyable::destroy);
		Stream.of(run).forEach(Destroyable::destroy);
	}
}
