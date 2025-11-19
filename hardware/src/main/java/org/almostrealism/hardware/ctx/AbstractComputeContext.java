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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.profile.CompilationTimingListener;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.MemoryData;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Base class for compute contexts that manages asynchronous execution and compilation timing.
 *
 * <p>{@link AbstractComputeContext} provides the foundation for backend-specific compute contexts
 * (OpenCL, Metal, JNI) by managing a thread pool for asynchronous kernel compilation and execution,
 * and optionally recording compilation timing data for profiling.</p>
 *
 * <h2>Thread Pool Management</h2>
 *
 * <p>Each compute context maintains a fixed-size thread pool for asynchronous operations. The pool
 * size is determined by {@link KernelPreferences#getEvaluationParallelism()}, which defaults to the
 * number of available CPU cores:</p>
 * <pre>{@code
 * // Context automatically creates thread pool
 * ComputeContext<?> context = new OpenCLComputeContext(dataContext);
 *
 * // Execute compilation asynchronously on thread pool
 * context.runLater(() -> {
 *     compileKernel(operation);
 * });
 *
 * // Check if current thread is from the executor pool
 * if (context.isExecutorThread()) {
 *     // Running on context's thread pool
 * }
 * }</pre>
 *
 * <h2>Thread Identification</h2>
 *
 * <p>All threads created by compute contexts are organized in a {@link ThreadGroup} named
 * "ComputeContext" and individually named "ComputeContext-N" where N is a unique incrementing
 * ID. This aids debugging and profiling.</p>
 *
 * <h2>Compilation Timing</h2>
 *
 * <p>If a {@link CompilationTimingListener} is registered via the static {@code compilationTimingListener}
 * field, compilation events are recorded for profiling:</p>
 * <pre>{@code
 * // Enable compilation timing
 * AbstractComputeContext.compilationTimingListener = new CompilationTimingListener() {
 *     @Override
 *     public void recordCompilation(Scope<?> scope, String source, long nanos) {
 *         System.out.println("Compiled " + scope + " in " + (nanos / 1_000_000) + "ms");
 *     }
 * };
 *
 * // Compilations will now be recorded
 * context.compile(operation);  // Triggers recordCompilation callback
 * }</pre>
 *
 * <h2>Subclass Responsibilities</h2>
 *
 * <p>Concrete implementations must provide:</p>
 * <ul>
 *   <li><b>Kernel compilation</b>: Implement methods to compile operations into executable kernels
 *       for the specific backend (OpenCL programs, Metal shaders, native code, etc.)</li>
 *   <li><b>Kernel execution</b>: Implement methods to execute compiled kernels with arguments</li>
 *   <li><b>Resource cleanup</b>: Override cleanup methods to release backend-specific resources</li>
 * </ul>
 *
 * <h2>Integration with DataContext</h2>
 *
 * <p>Each {@link AbstractComputeContext} wraps a {@link DataContext} (typically a subclass of
 * {@link HardwareDataContext}) that handles memory allocation and management for the same backend:</p>
 * <pre>{@code
 * DataContext<MemoryData> dataContext = new OpenCLDataContext(...);
 * ComputeContext<MemoryData> computeContext = new OpenCLComputeContext(dataContext);
 *
 * // Data context handles memory
 * MemoryData data = dataContext.allocate(1024);
 *
 * // Compute context handles operations
 * Evaluable<?> operation = computeContext.compile(producer);
 * }</pre>
 *
 * @param <T> The type of {@link DataContext} this compute context wraps
 * @see HardwareDataContext
 * @see ComputeContext
 * @see KernelPreferences
 */
public abstract class AbstractComputeContext<T extends DataContext<MemoryData>> implements ComputeContext<MemoryData> {
	/**
	 * Global compilation timing listener for profiling kernel compilation.
	 *
	 * <p>If set, all compute contexts will report compilation events to this listener.</p>
	 */
	public static CompilationTimingListener compilationTimingListener;

	/**
	 * Global counter for assigning unique IDs to executor threads.
	 */
	public static int threadId = 0;

	private final T dc;
	private final Executor executor;
	private final ThreadGroup executorGroup;

	/**
	 * Constructs a compute context wrapping the given data context.
	 *
	 * <p>Creates a fixed-size thread pool for asynchronous operations with size determined by
	 * {@link KernelPreferences#getEvaluationParallelism()}.</p>
	 *
	 * @param dc Data context to use for memory operations
	 */
	protected AbstractComputeContext(T dc) {
		this.dc = dc;
		this.executorGroup = new ThreadGroup("ComputeContext");
		this.executor = Executors.newFixedThreadPool(KernelPreferences.getEvaluationParallelism(),
				r -> new Thread(executorGroup, r, "ComputeContext-" + (threadId++)));
	}

	/**
	 * Executes the given runnable asynchronously on this context's thread pool.
	 *
	 * <p>Typically used for kernel compilation and other potentially long-running operations
	 * that should not block the calling thread.</p>
	 *
	 * @param runnable Operation to execute asynchronously
	 */
	@Override
	public void runLater(Runnable runnable) {
		executor.execute(runnable);
	}

	/**
	 * Returns whether the current thread is from this context's executor thread pool.
	 *
	 * <p>Useful for conditional logic that should only execute on executor threads,
	 * or for debugging to identify which threads are performing work.</p>
	 *
	 * @return true if current thread is from this context's thread pool, false otherwise
	 */
	@Override
	public boolean isExecutorThread() {
		return Thread.currentThread().getThreadGroup() == executorGroup;
	}

	/**
	 * Returns the data context wrapped by this compute context.
	 *
	 * @return The data context for memory operations
	 */
	public T getDataContext() { return dc; }

	/**
	 * Records a compilation event if a timing listener is registered.
	 *
	 * <p>Subclasses should call this method after compiling a kernel to enable profiling.</p>
	 *
	 * @param scope The scope that was compiled
	 * @param source Supplier for the generated source code (lazily evaluated)
	 * @param nanos Compilation time in nanoseconds
	 */
	protected void recordCompilation(Scope<?> scope, Supplier<String> source, long nanos) {
		if (compilationTimingListener != null) {
			compilationTimingListener.recordCompilation(scope, source.get(), nanos);
		}
	}
}
