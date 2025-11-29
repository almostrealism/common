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

package org.almostrealism.hardware.mem;

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Coordinates kernel execution by managing arguments, memory replacements, and completion notifications.
 *
 * <p>{@link AcceleratedProcessDetails} encapsulates all information needed to execute a hardware-accelerated
 * kernel, including argument gathering, memory substitution, and asynchronous result handling. It supports
 * both synchronous and asynchronous execution with listener-based completion notification.</p>
 *
 * <h2>Argument Lifecycle</h2>
 *
 * <p>Kernel arguments progress through three stages:</p>
 *
 * <h3>1. Original Arguments (Raw)</h3>
 * <p>Initial arguments provided to the kernel, which may be null if not yet available:</p>
 * <pre>{@code
 * Object[] originalArgs = new Object[3];
 * originalArgs[0] = inputData;  // Available immediately
 * originalArgs[1] = null;       // Result from async computation A
 * originalArgs[2] = null;       // Result from async computation B
 * }</pre>
 *
 * <h3>2. Replacement Processing</h3>
 * <p>When all original arguments are available, {@link MemoryReplacementManager} processes them
 * to substitute memory references:</p>
 * <pre>{@code
 * // All arguments now available
 * originalArgs[1] = resultA;
 * originalArgs[2] = resultB;
 *
 * // MemoryReplacementManager processes arguments
 * Object[] processedArgs = replacementManager.processArguments(originalArgs);
 * // Memory references may be substituted for optimization
 * }</pre>
 *
 * <h3>3. Final Arguments</h3>
 * <p>Processed arguments ready for kernel execution.</p>
 *
 * <h2>Asynchronous Result Gathering</h2>
 *
 * <p>Arguments can be set asynchronously as they become available:</p>
 * <pre>{@code
 * AcceleratedProcessDetails details = new AcceleratedProcessDetails(
 *     new Object[3], kernelSize, replacementManager, executor);
 *
 * // Set initial argument
 * details.result(0, inputData);
 *
 * // Async computations provide results when ready
 * asyncComputation1.whenComplete((result, error) -> {
 *     details.result(1, result);  // Triggers checkReady()
 * });
 *
 * asyncComputation2.whenComplete((result, error) -> {
 *     details.result(2, result);  // Triggers checkReady() again
 * });
 *
 * // When all results available, listeners are notified
 * details.whenReady(() -> executeKernel(details.getArguments()));
 * }</pre>
 *
 * <h2>Completion Notification</h2>
 *
 * <p>The {@link #whenReady(Runnable)} method registers listeners that execute when all
 * arguments are available:</p>
 * <pre>{@code
 * details.whenReady(() -> {
 *     // All arguments available
 *     Object[] args = details.getArguments();
 *     executeKernel(args, details.getKernelSize());
 * });
 *
 * // If already ready, listener executes immediately
 * // Otherwise, waits for remaining arguments
 * }</pre>
 *
 * <h2>Synchronous vs Asynchronous Execution</h2>
 *
 * <p>Listener execution mode depends on {@link Hardware#isAsync()}:</p>
 *
 * <h3>Synchronous (Hardware.isAsync() = false)</h3>
 * <pre>{@code
 * // Listeners execute immediately on calling thread
 * details.result(2, lastResult);  // Last argument
 * // -> checkReady() -> notifyListeners() executes immediately
 * // -> Listeners run on current thread
 * }</pre>
 *
 * <h3>Asynchronous (Hardware.isAsync() = true)</h3>
 * <pre>{@code
 * // Listeners execute via Executor
 * details.result(2, lastResult);  // Last argument
 * // -> checkReady() -> executor.execute(this::notifyListeners)
 * // -> Listeners run on executor thread pool
 * }</pre>
 *
 * <h2>Semaphore Integration</h2>
 *
 * <p>Optional {@link Semaphore} integration for coordinating multiple kernel executions:</p>
 * <pre>{@code
 * DefaultLatchSemaphore sem = new DefaultLatchSemaphore(3);  // 3 permits
 * details.setSemaphore(sem);
 *
 * details.whenReady(() -> kernel1.execute());
 * details.whenReady(() -> kernel2.execute());
 * details.whenReady(() -> kernel3.execute());
 *
 * // After all listeners execute
 * // sem.countDown() called 3 times -> semaphore released
 * }</pre>
 *
 * <h2>Memory Replacement Operations</h2>
 *
 * <p>Integration with {@link MemoryReplacementManager} provides prepare/postprocess operations:</p>
 * <pre>{@code
 * OperationList prepare = details.getPrepare();
 * prepare.get().run();  // Prepare memory substitutions
 *
 * // Execute kernel with processed arguments
 * executeKernel(details.getArguments());
 *
 * OperationList postprocess = details.getPostprocess();
 * postprocess.get().run();  // Restore original memory
 * }</pre>
 *
 * @see MemoryReplacementManager
 * @see OperationList
 */
public class AcceleratedProcessDetails implements ConsoleFeatures {

	/** Original unprocessed arguments, potentially containing nulls for async results. */
	private Object[] originalArguments;

	/** Final processed arguments after memory replacement, ready for kernel execution. */
	private Object[] arguments;

	/** Number of work items for kernel execution (global work size). */
	private int kernelSize;

	/** Manages memory substitutions and prepare/postprocess operations. */
	private MemoryReplacementManager replacementManager;

	/** Executes completion listeners asynchronously when {@link Hardware#isAsync()} is true. */
	private Executor executor;

	/** Optional semaphore for coordinating kernel execution completion. */
	private DefaultLatchSemaphore semaphore;

	/** Listeners to notify when all arguments are ready. */
	private List<Runnable> listeners;

	/**
	 * Creates a new process details instance with the specified configuration.
	 *
	 * @param args                Original arguments (may contain nulls for async results)
	 * @param kernelSize          Number of work items for kernel execution
	 * @param replacementManager  Manages memory substitutions
	 * @param executor            Executes listeners asynchronously if {@link Hardware#isAsync()}
	 */
	public AcceleratedProcessDetails(Object[] args, int kernelSize,
									 MemoryReplacementManager replacementManager,
									 Executor executor) {
		this.originalArguments = args;
		this.kernelSize = kernelSize;
		this.replacementManager = replacementManager;
		this.executor = executor;
		this.listeners = new ArrayList<>();
	}

	/**
	 * Returns the {@link OperationList} for prepare operations before kernel execution.
	 *
	 * @return the prepare operations
	 */
	public OperationList getPrepare() { return replacementManager.getPrepare(); }

	/**
	 * Returns the {@link OperationList} for postprocess operations after kernel execution.
	 *
	 * @return the postprocess operations
	 */
	public OperationList getPostprocess() { return replacementManager.getPostprocess(); }

	/**
	 * Returns true if there are no memory replacements to perform.
	 *
	 * @return true if no replacements needed, false otherwise
	 */
	public boolean isEmpty() { return replacementManager.isEmpty(); }

	/**
	 * Returns the {@link Semaphore} for coordinating kernel completion, or null if not set.
	 *
	 * @return the semaphore, or null if not set
	 */
	public Semaphore getSemaphore() { return semaphore; }

	/**
	 * Sets the {@link Semaphore} for coordinating kernel completion notifications.
	 *
	 * @param semaphore the semaphore to set
	 */
	public void setSemaphore(DefaultLatchSemaphore semaphore) { this.semaphore = semaphore; }

	/**
	 * Returns the processed arguments as a typed array.
	 *
	 * @param generator Function to create the array of the desired type
	 * @param <A>       Array element type
	 * @return Typed array of processed arguments
	 */
	public <A> A[] getArguments(IntFunction<A[]> generator) {
		return Stream.of(getArguments()).toArray(generator);
	}

	/**
	 * Returns the processed arguments ready for kernel execution, or null if not yet ready.
	 *
	 * @return the processed arguments array, or null
	 */
	public Object[] getArguments() { return arguments; }

	/**
	 * Returns the original unprocessed arguments, potentially containing nulls.
	 *
	 * @return the original arguments array
	 */
	public Object[] getOriginalArguments() { return originalArguments; }

	/**
	 * Returns the number of work items for kernel execution (global work size).
	 *
	 * @return the kernel size
	 */
	public int getKernelSize() { return kernelSize; }

	/**
	 * Notifies all registered listeners that arguments are ready.
	 *
	 * <p>Executes each listener and counts down the semaphore (if set) after each execution.
	 * This method is synchronized to prevent concurrent notification.</p>
	 */
	private synchronized void notifyListeners() {
		listeners.forEach(r -> {
			try {
				r.run();
			} finally {
				if (semaphore != null) {
					semaphore.countDown();
				}
			}
		});

		listeners.clear();
	}

	/**
	 * Checks if all arguments are ready and triggers listener notification if so.
	 *
	 * <p>If all arguments are available (no nulls in originalArguments), this method:
	 * <ol>
	 * <li>Processes arguments via {@link MemoryReplacementManager#processArguments}</li>
	 * <li>Notifies listeners either synchronously or asynchronously based on {@link Hardware#isAsync()}</li>
	 * </ol>
	 *
	 * <p>This method is synchronized to ensure atomic check-and-notify behavior.</p>
	 */
	protected synchronized void checkReady() {
		if (!isReady()) return;

		if (arguments == null) {
			arguments = replacementManager.processArguments(originalArguments);
		}

		if (Hardware.getLocalHardware().isAsync()) {
			executor.execute(this::notifyListeners);
		} else {
			notifyListeners();
		}
	}

	/**
	 * Returns true if all original arguments are available (no null values).
	 *
	 * @return true if all arguments are ready for processing, false otherwise
	 */
	public boolean isReady() {
		return Stream.of(originalArguments).noneMatch(Objects::isNull);
	}

	/**
	 * Sets an asynchronous result for the specified argument index.
	 *
	 * <p>This method is called when an async computation completes and provides its result.
	 * After setting the result, it checks if all arguments are now available and triggers
	 * listener notification if so.</p>
	 *
	 * @param index  the argument index to set (0-based)
	 * @param result the result value to set at the specified index
	 * @throws IllegalArgumentException if a result has already been set for this index
	 * @throws IllegalStateException    if all arguments are already available
	 */
	public void result(int index, Object result) {
		if (originalArguments[index] != null) {
			throw new IllegalArgumentException("Duplicate result for argument index " + index);
		} else if (isReady()) {
			throw new IllegalStateException("Received result when details are already available");
		}

		originalArguments[index] = result;

		// TODO  This check should not block the
		// TODO  return of the results method
		checkReady();
	}

	/**
	 * Registers a listener to be notified when all arguments are ready.
	 *
	 * <p>If all arguments are already available, the listener may execute immediately
	 * (synchronously or asynchronously based on {@link Hardware#isAsync()}).
	 * Otherwise, the listener is queued and will execute when the last argument
	 * is provided via {@link #result(int, Object)}.</p>
	 *
	 * @param r the listener to execute when all arguments are ready
	 */
	public synchronized void whenReady(Runnable r) {
		this.listeners.add(r);
		checkReady();
	}

	/**
	 * Returns the console for logging hardware-related messages.
	 *
	 * @return the hardware console
	 */
	@Override
	public Console console() { return Hardware.console; }
}
