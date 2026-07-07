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
import io.almostrealism.streams.Semaphore;
import io.almostrealism.concurrent.Submittable;
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
 * <h2>Two-stage synchronization: readiness latch vs. completion semaphore</h2>
 *
 * <p>Because {@code apply} returns this process <em>before</em> its {@link #whenReady(Runnable)}
 * listener has run (the listener may run later on an {@link Executor} thread), two distinct
 * synchronization points are tracked. They are <em>not</em> the same event:</p>
 *
 * <ul>
 *   <li><b>Readiness latch</b> ({@link #setReadyLatch}/{@link #awaitReady()}) — a host-thread
 *       {@link DefaultLatchSemaphore} that fires once the {@code whenReady} listener has run,
 *       meaning the arguments are processed and the dispatch has been <em>issued</em>. This is an
 *       internal gate, not the operation's result.</li>
 *   <li><b>Completion semaphore</b> ({@link #setSemaphore(Semaphore)}/{@link #getSemaphore()}) —
 *       the device-completion {@link Semaphore} published by the operator dispatch, which fires
 *       once the kernel has actually <em>finished</em>. This is the operation's true completion
 *       and the handle a dependent operation chains on.</li>
 * </ul>
 *
 * <p>Under fully synchronous dispatch these two collapse to the same instant; under batched
 * (sustained) dispatch the kernel completion comes strictly after the dispatch is issued, which is
 * why the two are tracked separately. A typical caller waits for both, in order:</p>
 * <pre>{@code
 * AcceleratedProcessDetails details = operation.apply(output, args);
 * details.awaitReady();                 // dispatch has been issued
 * details.getSemaphore().waitFor();     // kernel has completed
 * }</pre>
 *
 * <h2>Memory Replacement Operations</h2>
 *
 * <p>Integration with {@link MemoryReplacementManager} provides prepare/postprocess operations:</p>
 * <pre>{@code
 * Semaphore prepared = Submittable.submit(details.getPrepareOperations(), dependsOn);
 *
 * // Execute kernel with processed arguments, ordered after the prepare copies
 * Semaphore done = executeKernel(details.getArguments(), prepared);
 *
 * // Restore original memory, ordered after the kernel
 * Submittable.submit(details.getPostprocessOperations(), done);
 * }</pre>
 *
 * @see MemoryReplacementManager
 * @see OperationList
 */
public class AcceleratedProcessDetails implements ConsoleFeatures {

	/** Original unprocessed arguments, potentially containing nulls for async results. */
	private Object[] originalArguments;

	/**
	 * Completion {@link Semaphore}s delivered with asynchronously produced arguments, indexed
	 * like {@link #originalArguments}. An entry is non-null when the producer delivered the
	 * argument via {@link #result(int, Object, Semaphore)} before the work filling it had
	 * completed; the kernel dispatch must then be ordered after that completion (by merging
	 * {@link #getArgumentCompletions()} into its {@code dependsOn}) instead of the host
	 * waiting for the argument.
	 */
	private Semaphore[] argumentCompletions;

	/** Final processed arguments after memory replacement, ready for kernel execution. */
	private Object[] arguments;

	/** Number of work items for kernel execution (global work size). */
	private int kernelSize;

	/** Manages memory substitutions and prepare/postprocess operations. */
	private MemoryReplacementManager replacementManager;

	/** Executes completion listeners asynchronously when {@link Hardware#isAsync()} is true. */
	private Executor executor;

	/**
	 * Host-thread readiness latch for the asynchronous {@link #whenReady(Runnable)} mechanism.
	 *
	 * <p>This is <em>not</em> the operation's completion. It is counted down by
	 * {@link #notifyListeners()} once the {@code whenReady} listener has finished running — i.e.
	 * once the arguments have been processed and the dispatch has been issued (and the
	 * {@link #semaphore completion semaphore} has been published). Because {@code apply} returns
	 * the process before that listener runs (it may run later on an {@link Executor} thread),
	 * callers use {@link #awaitReady()} to block on this latch and guarantee the dispatch has
	 * happened before they read {@link #getSemaphore()} or issue a dependent operation.</p>
	 */
	private DefaultLatchSemaphore readyLatch;

	/**
	 * The operation's completion {@link Semaphore}, published by the operator dispatch (e.g. a
	 * {@code MetalSemaphore} or {@code CLSemaphore} backing a device event). When set it is the
	 * operation's true completion and is returned by {@link #getSemaphore()}, so callers wait on
	 * (and can chain via {@code dependsOn}) actual kernel completion rather than the host enqueue.
	 *
	 * <p>It may be {@code null} for a fully synchronous provider whose dispatch returns no device
	 * event; in that case {@link #getSemaphore()} falls back to the {@link #readyLatch}, which by
	 * the time it is read has already fired (so it behaves as an already-completed latch).</p>
	 */
	private Semaphore semaphore;

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
		this.argumentCompletions = new Semaphore[args.length];
		this.kernelSize = kernelSize;
		this.replacementManager = replacementManager;
		this.executor = executor;
		this.listeners = new ArrayList<>();
	}

	/**
	 * Returns the replacement copies that run before kernel execution (originals into
	 * temporary buffers), each chaining on a {@link Semaphore} via
	 * {@link io.almostrealism.concurrent.Submittable#submit(Semaphore)}.
	 *
	 * @return the prepare copies
	 */
	public List<Submittable> getPrepareOperations() { return replacementManager.getPrepareOperations(); }

	/**
	 * Returns the replacement copies that run after kernel execution (temporary buffers
	 * back into originals), each chaining on a {@link Semaphore} via
	 * {@link io.almostrealism.concurrent.Submittable#submit(Semaphore)}.
	 *
	 * @return the postprocess copies
	 */
	public List<Submittable> getPostprocessOperations() { return replacementManager.getPostprocessOperations(); }

	/**
	 * Returns true if there are no memory replacements to perform.
	 *
	 * @return true if no replacements needed, false otherwise
	 */
	public boolean isEmpty() { return replacementManager.isEmpty(); }

	/**
	 * Returns the operation's completion {@link Semaphore} — the {@link #semaphore} published by
	 * the operator dispatch (a {@code MetalSemaphore}, a {@code CLSemaphore}, or another device
	 * event). It is valid once {@link #awaitReady()} has returned, which {@code apply} ensures
	 * before handing the process back.
	 *
	 * <p>When the provider published no completion (a fully synchronous dispatch), this falls back
	 * to the host {@link #readyLatch}; because {@code awaitReady} has already returned, that latch
	 * has fired, so waiting on it returns immediately.</p>
	 *
	 * @return the completion semaphore, or the already-fired readiness latch as a fallback
	 */
	public Semaphore getSemaphore() {
		return semaphore != null ? semaphore : readyLatch;
	}

	/**
	 * Blocks on the {@link #readyLatch} until this operation's arguments have been processed and
	 * the dispatch has been issued — i.e. until {@link #setSemaphore(Semaphore)} has been called
	 * with the operator's completion. This is an internal readiness gate, <em>not</em> the
	 * operation's completion; {@code apply} waits on it before returning so the completion from
	 * {@link #getSemaphore()} is available, and so a chained dependent operation is issued after
	 * the one it depends on.
	 */
	public void awaitReady() {
		if (readyLatch != null) readyLatch.waitFor();
	}

	/**
	 * Sets the host-thread {@link #readyLatch readiness latch} that {@link #awaitReady()} blocks
	 * on. It is counted down by {@link #notifyListeners()} once the {@link #whenReady(Runnable)}
	 * listener has run and the dispatch has been issued.
	 *
	 * @param readyLatch the readiness latch to set
	 */
	public void setReadyLatch(DefaultLatchSemaphore readyLatch) { this.readyLatch = readyLatch; }

	/**
	 * Sets the operation's completion {@link Semaphore}, as published by the operator dispatch.
	 * When non-null this is returned by {@link #getSemaphore()} so callers wait on (and can chain)
	 * actual kernel completion rather than the host {@link #readyLatch readiness latch}.
	 *
	 * @param semaphore the operator's completion semaphore, or null for a synchronous provider
	 */
	public void setSemaphore(Semaphore semaphore) {
		this.semaphore = semaphore;
	}

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
	 * <p>Executes each listener and counts down the {@link #readyLatch readiness latch} (if set)
	 * after each execution. This method is synchronized to prevent concurrent notification.</p>
	 */
	private synchronized void notifyListeners() {
		listeners.forEach(r -> {
			try {
				r.run();
			} finally {
				if (readyLatch != null) {
					readyLatch.countDown();
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
		result(index, result, null);
	}

	/**
	 * Sets an asynchronous result for the specified argument index along with the completion of
	 * the work producing it. When {@code completion} is non-null the argument's contents are not
	 * yet valid; the caller of {@link #getArguments()} must order the kernel after every
	 * completion in {@link #getArgumentCompletions()} (typically by merging them into the
	 * dispatch's {@code dependsOn}) rather than reading the argument on the host first.
	 *
	 * @param index      the argument index to set (0-based)
	 * @param result     the result value to set at the specified index
	 * @param completion the completion of the work filling {@code result}, or {@code null}
	 *                   when the value is already complete
	 * @throws IllegalArgumentException if a result has already been set for this index
	 * @throws IllegalStateException    if all arguments are already available
	 */
	public void result(int index, Object result, Semaphore completion) {
		if (originalArguments[index] != null) {
			throw new IllegalArgumentException("Duplicate result for argument index " + index);
		} else if (isReady()) {
			throw new IllegalStateException("Received result when details are already available");
		}

		argumentCompletions[index] = completion;
		originalArguments[index] = result;

		// TODO  This check should not block the
		// TODO  return of the results method
		checkReady();
	}

	/**
	 * Returns the completions of any asynchronously produced arguments that were delivered
	 * before their contents were valid (see {@link #result(int, Object, Semaphore)}). The
	 * kernel dispatch must be ordered after all of them. The list is safe to read from a
	 * {@link #whenReady(Runnable)} listener, since every delivery precedes the readiness
	 * notification.
	 *
	 * @return the non-null argument completions, possibly empty
	 */
	public List<Semaphore> getArgumentCompletions() {
		List<Semaphore> completions = new ArrayList<>();
		for (Semaphore s : argumentCompletions) {
			if (s != null) completions.add(s);
		}
		return completions;
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
