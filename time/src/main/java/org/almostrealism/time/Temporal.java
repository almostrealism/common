/*
 * Copyright 2022 Michael Murray
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

import java.util.function.Supplier;

/**
 * Represents an operation that is performed as a sequence of discrete time steps (ticks),
 * enabling synchronization and coordination between groups of sequential operations.
 *
 * <p>The {@link Temporal} interface is a functional interface that provides a foundation for
 * time-based operations in signal processing, audio synthesis, and iterative computations.
 * Operations implementing this interface can be easily synchronized, buffered, and iterated
 * a specified number of times.</p>
 *
 * <h2>Core Concepts</h2>
 *
 * <h3>Tick-Based Execution</h3>
 * <p>A "tick" represents one discrete step of execution in a temporal sequence. For example:</p>
 * <ul>
 *   <li>In audio synthesis: one sample or one buffer of samples</li>
 *   <li>In animation: one frame</li>
 *   <li>In iterative algorithms: one iteration step</li>
 *   <li>In simulations: one time step</li>
 * </ul>
 *
 * <h3>Synchronization</h3>
 * <p>Multiple {@link Temporal} operations can be synchronized by orchestrating their ticks
 * to execute in lockstep, ensuring coordinated progression through time.</p>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Basic Implementation</h3>
 * <pre>{@code
 * public class Counter implements Temporal {
 *     private int count = 0;
 *
 *     @Override
 *     public Supplier<Runnable> tick() {
 *         return () -> () -> count++;
 *     }
 * }
 * }</pre>
 *
 * <h3>Iteration</h3>
 * <pre>{@code
 * Temporal operation = ...;
 *
 * // Run operation 10 times
 * Supplier<Runnable> loop = operation.iter(10);
 * loop.get().run();
 *
 * // Run with lifecycle reset after completion
 * Supplier<Runnable> loopWithReset = operation.iter(10, true);
 * }</pre>
 *
 * <h3>Buffering for Performance</h3>
 * <pre>{@code
 * Temporal operation = ...;
 *
 * // Create a runner that buffers 1024 frames
 * TemporalRunner buffered = operation.buffer(1024);
 * buffered.get().run();  // Runs setup once, then executes 1024 ticks
 * }</pre>
 *
 * <h3>Combining Multiple Temporals</h3>
 * <pre>{@code
 * Temporal op1 = ...;
 * Temporal op2 = ...;
 * Temporal op3 = ...;
 *
 * TemporalList combined = new TemporalList();
 * combined.add(op1);
 * combined.add(op2);
 * combined.add(op3);
 *
 * // All three operations tick together
 * Supplier<Runnable> synchronizedTick = combined.tick();
 * }</pre>
 *
 * <h2>Integration with Hardware Acceleration</h2>
 * <p>Temporal operations integrate seamlessly with hardware acceleration:</p>
 * <ul>
 *   <li>The {@link #tick()} method returns compilable computations that can be optimized
 *       and executed on GPUs or other accelerators</li>
 *   <li>{@link TemporalRunner} can optimize and flatten operation trees for efficient execution</li>
 *   <li>Supports both CPU and GPU-accelerated implementations transparently</li>
 * </ul>
 *
 * <h2>Lifecycle Integration</h2>
 * <p>When combined with {@link io.almostrealism.lifecycle.Lifecycle} or
 * {@link io.almostrealism.cycle.Setup}, temporal operations gain additional capabilities:</p>
 * <pre>{@code
 * public class StatefulOperation implements Temporal, Lifecycle, Setup {
 *     private PackedCollection state;
 *
 *     @Override
 *     public Supplier<Runnable> setup() {
 *         return () -> () -> {
 *             state = new PackedCollection(1024);
 *             // Initialize state...
 *         };
 *     }
 *
 *     @Override
 *     public Supplier<Runnable> tick() {
 *         return () -> () -> {
 *             // Process using state...
 *         };
 *     }
 *
 *     @Override
 *     public void reset() {
 *         state.setMem(0, 0.0);  // Reset to initial state
 *     }
 * }
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li><strong>Audio Processing:</strong> Sample-by-sample or buffer-by-buffer signal processing</li>
 *   <li><strong>Synthesis:</strong> Oscillators, envelopes, and modulators that progress over time</li>
 *   <li><strong>Filtering:</strong> Time-series filtering operations with state</li>
 *   <li><strong>Animation:</strong> Frame-by-frame rendering or state updates</li>
 *   <li><strong>Simulations:</strong> Discrete-time physical or mathematical simulations</li>
 *   <li><strong>Iterative Algorithms:</strong> Gradient descent, optimization, etc.</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li>The {@link #tick()} method should be lightweight and return quickly</li>
 *   <li>For hardware acceleration, avoid returning lambda expressions; prefer returning
 *       {@link org.almostrealism.hardware.OperationList} or compiled computations</li>
 *   <li>Use {@link #buffer(int)} to amortize setup costs across multiple ticks</li>
 *   <li>Consider implementing {@link io.almostrealism.lifecycle.Destroyable} to clean up
 *       resources when operations are no longer needed</li>
 * </ul>
 *
 * @see TemporalFeatures
 * @see TemporalRunner
 * @see TemporalList
 * @see io.almostrealism.lifecycle.Lifecycle
 * @see io.almostrealism.cycle.Setup
 *
 * @author  Michael Murray
 */
@FunctionalInterface
public interface Temporal extends TemporalFeatures {
	/**
	 * Returns a {@link Supplier} that produces a {@link Runnable} representing one discrete
	 * time step (tick) of this temporal operation.
	 *
	 * <p>This is the core method of the {@link Temporal} interface. Each invocation of
	 * {@code tick()} should return a supplier that, when called, produces a fresh runnable
	 * that executes one step of the temporal operation.</p>
	 *
	 * <h3>Implementation Guidelines</h3>
	 * <ul>
	 *   <li><strong>Stateless Supplier:</strong> The returned supplier should produce a new
	 *       runnable each time it's called (unless deliberately caching compiled operations)</li>
	 *   <li><strong>Idempotent Tick:</strong> Running the same tick multiple times should
	 *       produce the same result (unless the operation is inherently stateful)</li>
	 *   <li><strong>Lightweight:</strong> This method should execute quickly; heavy computation
	 *       belongs in the returned runnable</li>
	 * </ul>
	 *
	 * <h3>Hardware Acceleration</h3>
	 * <p>For hardware-accelerated operations, return a compilation-friendly type:</p>
	 * <pre>{@code
	 * @Override
	 * public Supplier<Runnable> tick() {
	 *     return a("MyOperation", output, computation(input));
	 * }
	 * }</pre>
	 *
	 * <h3>Simple CPU Operations</h3>
	 * <p>For simple CPU operations, a lambda is sufficient:</p>
	 * <pre>{@code
	 * @Override
	 * public Supplier<Runnable> tick() {
	 *     return () -> () -> {
	 *         // Perform one step of work
	 *     };
	 * }
	 * }</pre>
	 *
	 * @return A supplier that produces a runnable representing one tick of execution
	 */
	Supplier<Runnable> tick();

	/**
	 * Creates a {@link TemporalRunner} that buffers this temporal operation to run for
	 * a specified number of frames.
	 *
	 * <p>Buffering amortizes the setup cost of an operation across multiple ticks, improving
	 * performance when the operation has expensive initialization or compilation steps.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Temporal synth = ...;
	 * TemporalRunner buffered = synth.buffer(1024);
	 *
	 * // Setup runs once, then executes 1024 ticks
	 * buffered.get().run();
	 *
	 * // Continue execution (only runs the ticks, skips setup)
	 * buffered.getContinue().run();
	 * }</pre>
	 *
	 * @param frames The number of frames (ticks) to execute per invocation
	 * @return A {@link TemporalRunner} configured to run this operation for the specified frames
	 *
	 * @see TemporalRunner
	 */
	default TemporalRunner buffer(int frames) {
		return new TemporalRunner(this, frames);
	}

	/**
	 * Creates a {@link Supplier} that runs this temporal operation for a specified number of
	 * iterations, with lifecycle reset enabled by default.
	 *
	 * <p>This is a convenience method equivalent to calling {@code iter(iter, true)}.</p>
	 *
	 * <h3>Behavior</h3>
	 * <ul>
	 *   <li>If this operation implements {@link io.almostrealism.cycle.Setup}, the setup
	 *       runs once before iterations</li>
	 *   <li>The {@link #tick()} method executes {@code iter} times</li>
	 *   <li>If this operation implements {@link io.almostrealism.lifecycle.Lifecycle},
	 *       {@code reset()} is called after all iterations complete</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Temporal operation = ...;
	 * Supplier<Runnable> loop = operation.iter(100);
	 * loop.get().run();  // Runs setup, 100 ticks, then reset
	 * }</pre>
	 *
	 * @param iter The number of iterations to execute
	 * @return A supplier that runs this operation the specified number of times
	 *
	 * @see #iter(int, boolean)
	 * @see TemporalFeatures#iter(Temporal, int)
	 */
	default Supplier<Runnable> iter(int iter) {
		return iter(this, iter);
	}

	/**
	 * Creates a {@link Supplier} that runs this temporal operation for a specified number of
	 * iterations, with optional lifecycle reset.
	 *
	 * <h3>Execution Sequence</h3>
	 * <ol>
	 *   <li>If this implements {@link io.almostrealism.cycle.Setup}: run {@code setup()}</li>
	 *   <li>Run {@link #tick()} {@code iter} times</li>
	 *   <li>If {@code resetAfter} is true and this implements {@link io.almostrealism.lifecycle.Lifecycle}:
	 *       call {@code reset()}</li>
	 * </ol>
	 *
	 * <h3>Use Cases</h3>
	 * <ul>
	 *   <li><strong>{@code resetAfter = true}:</strong> Each iteration sequence is independent,
	 *       useful for repeated batch processing</li>
	 *   <li><strong>{@code resetAfter = false}:</strong> State carries over between iteration
	 *       sequences, useful for continuing long-running processes</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Temporal statefulOp = ...;
	 *
	 * // Process 1000 steps with reset
	 * statefulOp.iter(1000, true).get().run();
	 *
	 * // Continue from current state for another 1000 steps
	 * statefulOp.iter(1000, false).get().run();
	 * }</pre>
	 *
	 * @param iter The number of iterations to execute
	 * @param resetAfter If true, reset the operation's state after iterations complete
	 * @return A supplier that runs this operation the specified number of times
	 *
	 * @see TemporalFeatures#iter(Temporal, int, boolean)
	 */
	default Supplier<Runnable> iter(int iter, boolean resetAfter) {
		return iter(this, iter, resetAfter);
	}
}
