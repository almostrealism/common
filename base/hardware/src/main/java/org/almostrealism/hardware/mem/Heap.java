/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Thread-local arena allocator with staged scopes and automatic dependency lifecycle management.
 *
 * <p>{@link Heap} provides a stack-based arena allocator for short-lived {@link Bytes} allocations.
 * Instead of individually allocating many small memory blocks (each requiring a separate call to
 * the underlying {@link MemoryProvider}), a {@link Heap} pre-allocates a single large memory block
 * and serves allocation requests by advancing a bump pointer within that block. This reduces
 * allocation overhead from O(n) provider calls to O(1) for n allocations.</p>
 *
 * <h2>Thread-Local Default</h2>
 *
 * <p>{@link Heap} maintains a thread-local default instance accessible via {@link #getDefault()}.
 * When a default heap is active on the current thread, several framework components automatically
 * use it:</p>
 * <ul>
 *   <li>{@code PackedCollection.factory()} returns a factory that allocates from the heap
 *       instead of the global {@link MemoryProvider} (see
 *       {@code PackedCollection.factory()})</li>
 *   <li>{@code MemoryDataAdapter.init()} delegates to the heap when a subclass overrides
 *       {@code getDefaultDelegate()} to return {@code Heap.getDefault()} (see
 *       {@link MemoryDataAdapter#getDefaultDelegate()}). The types {@code Vector}, {@code Pair},
 *       {@code TransformMatrix}, {@code RGBData192}, and {@code PolymorphicAudioData} all
 *       use this pattern.</li>
 *   <li>{@code DefaultComputer.compileRunnable()} calls {@link #addCompiled(OperationAdapter)}
 *       to register every compiled operation for lifecycle tracking</li>
 *   <li>{@code ProcessDetailsFactory} calls {@link #addCreatedMemory(MemoryData)} to register
 *       temporary kernel argument buffers for lifecycle tracking</li>
 * </ul>
 *
 * <p>The default is set and restored via the {@link #use(Runnable)} and {@link #use(Supplier)}
 * methods, which save and restore the previous default using a try/finally pattern:</p>
 * <pre>{@code
 * Heap myHeap = new Heap(10000);
 * myHeap.use(() -> {
 *     // Heap.getDefault() == myHeap on this thread
 *     Bytes temp = Heap.getDefault().allocate(100);
 *     // ...
 * });
 * // Heap.getDefault() restored to its prior value (typically null)
 * }</pre>
 *
 * <h2>Staged Allocation</h2>
 *
 * <p>A {@link Heap} contains a <em>root</em> {@link HeapStage} and a {@link Stack} of additional
 * stages. When {@link #push()} is called, a new stage of size {@link #stageSize} is created and
 * pushed onto the stack. All subsequent allocations go to this new stage. When {@link #pop()} is
 * called, the top stage is removed and destroyed, freeing all allocations made within it. The
 * root stage is only destroyed when the entire heap is destroyed via {@link #destroy()}.</p>
 *
 * <p>The static {@link #stage(Runnable)} method provides a convenient scoped API for this:</p>
 * <pre>{@code
 * Heap.stage(() -> {
 *     Bytes temp1 = Heap.getDefault().allocate(100);
 *     Bytes temp2 = Heap.getDefault().allocate(50);
 *
 *     Heap.stage(() -> {
 *         // Nested stage
 *         Bytes temp3 = Heap.getDefault().allocate(200);
 *         // temp3 destroyed when this inner stage exits
 *     });
 *
 *     // temp1, temp2 still valid here
 * });
 * // temp1, temp2 destroyed when outer stage exits
 * }</pre>
 *
 * <p><strong>Important:</strong> {@link #stage(Runnable)} is a <em>no-op</em> when
 * {@link #getDefault()} returns {@code null}. In that case the runnable executes directly
 * without any staging. This allows code that calls {@code Heap.stage()} to function correctly
 * whether or not a heap is active.</p>
 *
 * <h2>Dependency Tracking</h2>
 *
 * <p>Each {@link HeapStage} has an associated {@link HeapDependencies} that tracks three kinds
 * of resources created within the stage's scope:</p>
 * <ul>
 *   <li><strong>Dependent operations</strong> ({@link Supplier}): Registered via
 *       {@link #addOperation(Supplier)}. If the supplier implements {@link Destroyable},
 *       its {@code destroy()} method is called when the stage is destroyed.</li>
 *   <li><strong>Compiled operations</strong> ({@link OperationAdapter}): Registered via
 *       {@link #addCompiled(OperationAdapter)}. The adapter's {@code destroy()} method
 *       is called when the stage is destroyed. This is the primary mechanism for freeing
 *       compiled native kernels after a scoped computation completes.</li>
 *   <li><strong>Created memory</strong> ({@link MemoryData}): Registered via
 *       {@link #addCreatedMemory(MemoryData)}. The memory's {@code destroy()} method is
 *       called when the stage is destroyed.</li>
 * </ul>
 *
 * <p>All three static registration methods ({@link #addOperation}, {@link #addCompiled},
 * {@link #addCreatedMemory}) are <em>no-ops</em> when {@link #getDefault()} returns
 * {@code null}, allowing them to be called unconditionally.</p>
 *
 * <h2>Guards Against Heap-Active Contexts</h2>
 *
 * <p>Certain components that compile kernels with {@code PassThroughProducer} dynamic inputs
 * must not be instantiated while a heap is active, because the heap would interfere with
 * their argument setup. {@code AudioSumProvider} and {@code AudioProcessingUtils} throw
 * {@link RuntimeException} if {@code Heap.getDefault() != null} during construction.</p>
 *
 * <h2>Memory Layout</h2>
 *
 * <pre>
 * +------------------------- Heap --------------------------+
 * |                                                          |
 * |  +------------ Root Stage (rootSize) ----------------+  |
 * |  |  [alloc1][alloc2][alloc3]...        [free space]   |  |
 * |  |  ^                           ^end                  |  |
 * |  |  HeapDependencies: ops, compiled, memory           |  |
 * |  +----------------------------------------------------+  |
 * |                                                          |
 * |  +------------ Pushed Stage (stageSize) --------------+  |
 * |  |  [alloc4][alloc5]...                [free space]   |  |
 * |  |  ^                     ^end                        |  |
 * |  |  HeapDependencies: ops, compiled, memory           |  |
 * |  +----------------------------------------------------+  |
 * |                                                          |
 * |  (additional pushed stages as needed)                    |
 * +----------------------------------------------------------+
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The thread-local default ({@link #defaultHeap}) ensures that different threads cannot
 * interfere with each other's heap state. Within a single thread, the heap is not designed
 * for concurrent access. The {@link HeapStage#allocate(int)} method is synchronized to
 * protect against rare cases where a callback might re-enter allocation on the same thread
 * (e.g., during operation compilation), but typical usage is single-threaded per heap.</p>
 *
 * <h2>Example: Full Lifecycle</h2>
 *
 * <pre>{@code
 * // Create a heap with 100KB root and 25KB stages
 * Heap heap = new Heap(100_000);
 *
 * heap.use(() -> {
 *     // Root allocations
 *     Bytes workspace = Heap.getDefault().allocate(1000);
 *
 *     // Scoped stage
 *     Heap.stage(() -> {
 *         // Stage allocations
 *         PackedCollection temp = PackedCollection.factory().apply(500);
 *
 *         // Compile an operation (automatically tracked)
 *         Runnable compiled = someComputation.get();
 *         compiled.run();
 *
 *         // Stage destroyed here: temp and compiled freed
 *     });
 *
 *     // workspace still valid
 * });
 *
 * // Explicit cleanup (frees root stage and everything in it)
 * heap.destroy();
 * }</pre>
 *
 * @see Bytes
 * @see HeapStage
 * @see HeapDependencies
 * @see MemoryDataAdapter#getDefaultDelegate()
 *
 * @author Michael Murray
 */
public class Heap {
	/** Console for logging heap-related warnings and errors. */
	private static final Console heapConsole = Console.root().child();

	/**
	 * Thread-local storage for the default heap.
	 *
	 * <p>Each thread has its own default heap (or {@code null} if none is active).
	 * The default is set and restored by {@link #use(Runnable)}, {@link #use(Supplier)},
	 * and {@link #wrap(Callable)}. It is read by {@link #getDefault()} and by the static
	 * methods {@link #stage(Runnable)}, {@link #addOperation(Supplier)},
	 * {@link #addCompiled(OperationAdapter)}, and {@link #addCreatedMemory(MemoryData)},
	 * all of which are no-ops when the default is {@code null}.</p>
	 */
	private static ThreadLocal<Heap> defaultHeap = new ThreadLocal<>();

	/**
	 * The memory provider used for allocating stage backing memory.
	 *
	 * <p>When {@code null}, stages allocate using the default {@link Bytes} constructor
	 * (which in turn uses {@code Hardware.getLocalHardware().getMemoryProvider()}).
	 * When non-null, stages allocate via {@code Bytes.of(memory.allocate(size), size)},
	 * using the specified provider directly.</p>
	 */
	private MemoryProvider memory;

	/**
	 * The size in memory units for each pushed stage.
	 *
	 * <p>Set during construction. When using the single-argument constructor
	 * {@link #Heap(int)}, this defaults to {@code rootSize / 4}.</p>
	 */
	private int stageSize;

	/**
	 * The root stage, created during construction.
	 *
	 * <p>The root stage persists for the lifetime of the heap and is only destroyed
	 * when {@link #destroy()} is called. It serves as the allocation target when no
	 * pushed stages exist on the {@link #stages} stack.</p>
	 */
	private HeapStage root;

	/**
	 * Stack of pushed stages above the root.
	 *
	 * <p>Lazily initialized on the first call to {@link #push()}. When non-null and
	 * non-empty, the top of this stack is the active stage (returned by
	 * {@link #getStage()}). When null or empty, the {@link #root} is the active stage.</p>
	 */
	private Stack<HeapStage> stages;

	/**
	 * Creates a heap with the specified root size and a default stage size of {@code size / 4}.
	 *
	 * <p>Equivalent to {@code new Heap(null, size, size / 4)}.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * // 100KB root, 25KB per pushed stage
	 * Heap heap = new Heap(100_000);
	 * }</pre>
	 *
	 * @param size the root stage allocation size in memory units
	 */
	public Heap(int size) {
		this(null, size, size / 4);
	}

	/**
	 * Creates a heap with explicit root and stage sizes, using the default memory provider.
	 *
	 * <p>Equivalent to {@code new Heap(null, rootSize, stageSize)}.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * // 100KB root, 10KB per pushed stage
	 * Heap heap = new Heap(100_000, 10_000);
	 * }</pre>
	 *
	 * @param rootSize the root stage allocation size in memory units
	 * @param stageSize the allocation size for each pushed stage in memory units
	 */
	public Heap(int rootSize, int stageSize) {
		this(null, rootSize, stageSize);
	}

	/**
	 * Creates a heap with a specific memory provider and explicit root and stage sizes.
	 *
	 * <p>This is the primary constructor. It stores the memory provider and stage size,
	 * then creates the root {@link HeapStage} with the given {@code rootSize}.</p>
	 *
	 * <p>If {@code memory} is {@code null}, stages allocate backing memory using
	 * {@code new Bytes(size)}, which delegates to the default hardware memory provider.
	 * If {@code memory} is non-null, stages allocate via
	 * {@code Bytes.of(memory.allocate(size), size)}, bypassing the default provider.</p>
	 *
	 * @param memory the memory provider for allocations, or {@code null} for default
	 * @param rootSize the root stage allocation size in memory units
	 * @param stageSize the allocation size for each pushed stage in memory units
	 */
	public Heap(MemoryProvider memory, int rootSize, int stageSize) {
		this.memory = memory;
		this.stageSize = stageSize;
		this.root = new HeapStage(rootSize);
	}

	/**
	 * Returns the currently active stage.
	 *
	 * <p>If one or more stages have been pushed via {@link #push()}, returns the
	 * top of the stage stack. Otherwise, returns the {@link #root} stage.</p>
	 *
	 * <p>This method determines where {@link #allocate(int)} will suballocate from
	 * and where dependency tracking methods ({@link #getDependentOperations()},
	 * {@link #getCompiledDependencies()}, {@link #getCreatedMemory()}) will register
	 * resources.</p>
	 *
	 * @return the active {@link HeapStage} (never {@code null} for a live heap)
	 */
	public HeapStage getStage() {
		return (stages == null || stages.isEmpty()) ? root : stages.peek();
	}

	/**
	 * Allocates memory from the currently active stage via bump-pointer advancement.
	 *
	 * <p>Delegates to {@link HeapStage#allocate(int)} on the stage returned by
	 * {@link #getStage()}. The returned {@link Bytes} is a zero-copy view into the
	 * stage's pre-allocated backing block, meaning no new memory provider call is made.</p>
	 *
	 * <p>The returned {@link Bytes} remains valid until the stage that produced it
	 * is destroyed (via {@link #pop()} for pushed stages, or {@link #destroy()} for
	 * the root stage).</p>
	 *
	 * @param count the number of memory units to allocate
	 * @return a {@link Bytes} instance backed by the current stage's memory block
	 * @throws IllegalArgumentException if the current stage does not have {@code count}
	 *         units of free space remaining
	 */
	public Bytes allocate(int count) {
		return getStage().allocate(count);
	}

	/**
	 * Wraps a {@link Callable} so that this heap is the thread-local default during its execution.
	 *
	 * <p>The returned callable, when invoked, will:</p>
	 * <ol>
	 *   <li>Save the current thread-local default heap</li>
	 *   <li>Set this heap as the default</li>
	 *   <li>Execute the wrapped callable</li>
	 *   <li>Restore the previous default (in a {@code finally} block)</li>
	 * </ol>
	 *
	 * <p>This is useful for deferred execution where the heap must be active at call time
	 * rather than at creation time. For example, wrapping an evaluable's computation so
	 * that temporary allocations during evaluation use this heap.</p>
	 *
	 * @param <T> the return type of the callable
	 * @param r the callable to wrap
	 * @return a new callable that activates this heap during execution
	 */
	public <T> Callable<T> wrap(Callable<T> r) {
		return () -> {
			Heap old = defaultHeap.get();
			defaultHeap.set(this);

			try {
				return r.call();
			} finally {
				defaultHeap.set(old);
			}
		};
	}

	/**
	 * Executes a {@link Runnable} with this heap as the thread-local default.
	 *
	 * <p>Sets this heap as the default for the current thread, executes the runnable,
	 * and restores the previous default in a {@code finally} block. This guarantees
	 * restoration even if the runnable throws an exception.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * Heap heap = new Heap(10_000);
	 * heap.use(() -> {
	 *     // Heap.getDefault() == heap
	 *     PackedCollection temp = PackedCollection.factory().apply(100);
	 *     // temp is heap-backed
	 * });
	 * // Heap.getDefault() restored
	 * }</pre>
	 *
	 * @param r the runnable to execute with this heap active
	 * @return this heap, for method chaining
	 */
	public Heap use(Runnable r) {
		Heap old = defaultHeap.get();
		defaultHeap.set(this);

		try {
			r.run();
		} finally {
			defaultHeap.set(old);
		}

		return this;
	}

	/**
	 * Executes a {@link Supplier} with this heap as the thread-local default and returns
	 * the result.
	 *
	 * <p>Sets this heap as the default for the current thread, evaluates the supplier,
	 * and restores the previous default in a {@code finally} block.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * Heap heap = new Heap(10_000);
	 * PackedCollection result = heap.use(() -> {
	 *     return someProducer.get().evaluate();
	 * });
	 * }</pre>
	 *
	 * @param <T> the return type of the supplier
	 * @param r the supplier to execute with this heap active
	 * @return the value produced by the supplier
	 */
	public <T> T use(Supplier<T> r) {
		Heap old = defaultHeap.get();
		defaultHeap.set(this);

		try {
			return r.get();
		} finally {
			defaultHeap.set(old);
		}
	}

	/**
	 * Pushes a new stage onto the stage stack.
	 *
	 * <p>Creates a new {@link HeapStage} of size {@link #stageSize} and pushes it onto
	 * the internal stack. After this call, {@link #getStage()} returns the new stage,
	 * and all allocations via {@link #allocate(int)} go to it. Dependency registrations
	 * via the private accessor methods also target the new stage.</p>
	 *
	 * <p>The stage stack is lazily initialized on the first call to this method.</p>
	 *
	 * <p>This method is called internally by {@link #stage(Runnable)} and should
	 * not normally be called directly. Use {@link #stage(Runnable)} for scoped
	 * stage management.</p>
	 *
	 * @see #pop()
	 * @see #stage(Runnable)
	 */
	protected void push() {
		if (stages == null) {
			stages = new Stack<>();
		}

		stages.push(new HeapStage(stageSize));
	}

	/**
	 * Pops and destroys the top stage from the stage stack.
	 *
	 * <p>Removes the topmost stage and calls {@link HeapStage#destroy()} on it,
	 * which:</p>
	 * <ol>
	 *   <li>Clears the allocation entry list</li>
	 *   <li>Resets the bump pointer to zero</li>
	 *   <li>Destroys the backing {@link Bytes} memory block</li>
	 *   <li>Destroys all tracked dependencies ({@link HeapDependencies#destroy()})</li>
	 * </ol>
	 *
	 * <p>After this call, allocations revert to the next stage on the stack (or the
	 * root if the stack is now empty).</p>
	 *
	 * <p>If the stack is null or empty, this method is a no-op.</p>
	 *
	 * <p>This method is called internally by {@link #stage(Runnable)} and should
	 * not normally be called directly.</p>
	 *
	 * @see #push()
	 * @see #stage(Runnable)
	 */
	protected void pop() {
		if (stages != null && !stages.isEmpty()) {
			stages.pop().destroy();
		}
	}

	/**
	 * Destroys this heap and all its stages, freeing all tracked resources.
	 *
	 * <p>Pops and destroys every stage on the stack (from top to bottom), then
	 * destroys the root stage. After this call, the heap is no longer usable.</p>
	 *
	 * <p>The destroy order is:</p>
	 * <ol>
	 *   <li>All pushed stages, from top of stack to bottom (each stage's
	 *       {@link HeapStage#destroy()} is called, which destroys its
	 *       dependencies and backing memory)</li>
	 *   <li>The root stage</li>
	 * </ol>
	 *
	 * <p>This method is {@code synchronized} to prevent concurrent destruction
	 * in rare cases where a background thread might attempt cleanup.</p>
	 */
	public synchronized void destroy() {
		if (stages != null) {
			while (!stages.isEmpty()) {
				stages.pop().destroy();
			}
		}

		root.destroy();
	}

	/**
	 * Returns the dependent operations list from the currently active stage.
	 *
	 * <p>If pushed stages exist, returns the list from the top stage.
	 * Otherwise, returns the list from the root stage (or {@code null}
	 * if the root's dependencies have been destroyed).</p>
	 *
	 * @return the mutable list of dependent operations for the active stage,
	 *         or {@code null} if the root has no dependencies
	 */
	private List<Supplier> getDependentOperations() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.dependentOperations;
		}

		return root.dependencies == null ? null : root.dependencies.dependentOperations;
	}

	/**
	 * Returns the compiled dependencies list from the currently active stage.
	 *
	 * <p>If pushed stages exist, returns the list from the top stage.
	 * Otherwise, returns the list from the root stage (or {@code null}
	 * if the root's dependencies have been destroyed).</p>
	 *
	 * @return the mutable list of compiled dependencies for the active stage,
	 *         or {@code null} if the root has no dependencies
	 */
	private List<OperationAdapter> getCompiledDependencies() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.compiledDependencies;
		}

		return root.dependencies == null ? null : root.dependencies.compiledDependencies;
	}

	/**
	 * Returns the created memory list from the currently active stage.
	 *
	 * <p>If pushed stages exist, returns the list from the top stage.
	 * Otherwise, returns the list from the root stage (or {@code null}
	 * if the root's dependencies have been destroyed).</p>
	 *
	 * @return the mutable list of created memory for the active stage,
	 *         or {@code null} if the root has no dependencies
	 */
	private List<MemoryData> getCreatedMemory() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.createdMemory;
		}

		return root.dependencies == null ? null : root.dependencies.createdMemory;
	}

	/**
	 * A single allocation stage within a {@link Heap}, functioning as a bump allocator
	 * over a pre-allocated {@link Bytes} block.
	 *
	 * <p>Each stage maintains:</p>
	 * <ul>
	 *   <li>A backing {@link Bytes} block ({@link #data}) allocated at construction time</li>
	 *   <li>A bump pointer ({@link #end}) that advances with each allocation</li>
	 *   <li>A list of allocated {@link Bytes} entries ({@link #entries}) for indexed access</li>
	 *   <li>A {@link HeapDependencies} instance for tracking operations and memory
	 *       created within this stage's scope</li>
	 * </ul>
	 *
	 * <h3>Allocation Mechanism</h3>
	 *
	 * <p>When {@link #allocate(int)} is called with a requested {@code count}:</p>
	 * <ol>
	 *   <li>The method checks that {@code end + count <= data.getMemLength()}</li>
	 *   <li>A new {@link Bytes} is created as a zero-copy view into {@link #data}
	 *       at offset {@link #end}</li>
	 *   <li>The bump pointer {@link #end} is advanced by {@code count}</li>
	 *   <li>The new {@link Bytes} is added to the {@link #entries} list</li>
	 * </ol>
	 *
	 * <p>Because the returned {@link Bytes} delegates to the stage's backing block,
	 * no new memory is allocated from the provider. This is the key performance
	 * advantage of arena allocation.</p>
	 *
	 * <h3>Destruction</h3>
	 *
	 * <p>When {@link #destroy()} is called:</p>
	 * <ol>
	 *   <li>The entries list is cleared</li>
	 *   <li>The bump pointer is reset to zero</li>
	 *   <li>The backing {@link Bytes} block is destroyed (freeing the underlying memory)</li>
	 *   <li>The {@link HeapDependencies} are destroyed (freeing all tracked resources)</li>
	 * </ol>
	 *
	 * <p>After destruction, any {@link Bytes} previously returned by {@link #allocate(int)}
	 * become invalid (they still reference the destroyed backing block).</p>
	 *
	 * <h3>Thread Safety</h3>
	 *
	 * <p>The {@link #allocate(int)} method is {@code synchronized} to guard against
	 * re-entrant allocation from the same thread (e.g., during operation compilation
	 * callbacks). Normal single-threaded usage does not contend on this lock.</p>
	 *
	 * @see Heap
	 * @see HeapDependencies
	 * @see Bytes
	 */
	public class HeapStage implements Destroyable {
		/** Timeout in milliseconds for waiting on pending kernel semaphores during destroy. */
		private static final long PENDING_KERNEL_TIMEOUT_MS = 30_000;

		/**
		 * List of all {@link Bytes} instances allocated from this stage, in allocation order.
		 *
		 * <p>Provides indexed access via {@link #get(int)} and streaming via {@link #stream()}.
		 * Cleared on {@link #destroy()}.</p>
		 */
		private List<Bytes> entries;

		/**
		 * The pre-allocated backing memory block for this stage.
		 *
		 * <p>All allocations returned by {@link #allocate(int)} are zero-copy views
		 * into this block. Allocated either via {@code new Bytes(size)} (when the
		 * heap's memory provider is {@code null}) or via
		 * {@code Bytes.of(memory.allocate(size), size)} (when a provider is specified).</p>
		 */
		private Bytes data;

		/**
		 * Bump pointer tracking the next free position in the backing block.
		 *
		 * <p>Starts at zero. After each allocation of {@code count} units, advances
		 * by {@code count}. An allocation request is rejected if
		 * {@code end + count > data.getMemLength()}.</p>
		 */
		private int end;

		/**
		 * Dependencies tracked within this stage's scope.
		 *
		 * <p>Holds lists of dependent operations, compiled operations, and created
		 * memory. Destroyed when this stage is destroyed. Set to {@code null} after
		 * destruction.</p>
		 */
		HeapDependencies dependencies;

		/**
		 * Semaphores for kernels dispatched during this stage's lifetime.
		 *
		 * <p>When a kernel is dispatched while this stage is active, its completion
		 * {@link Semaphore} is registered here via {@link #addPendingKernel(Semaphore)}.
		 * Before destroying this stage's memory, {@link #destroy()} waits for all
		 * pending kernel semaphores to complete, ensuring that no in-flight kernel
		 * is still reading from or writing to memory owned by this stage.</p>
		 *
		 * <p>This list is only accessed from the thread that owns the heap stage
		 * (heap stages are thread-local), so a plain {@link ArrayList} is sufficient.</p>
		 */
		private List<Semaphore> pendingKernels;

		/**
		 * Creates a new stage with the specified backing block size.
		 *
		 * <p>Allocates a {@link Bytes} block of the given size (using the heap's
		 * {@link #memory} provider if non-null, or the default provider otherwise)
		 * and initializes an empty entry list and a fresh {@link HeapDependencies}.</p>
		 *
		 * @param size the size of the backing memory block in memory units
		 */
		public HeapStage(int size) {
			entries = new ArrayList<>();
			data = memory == null ? new Bytes(size) : Bytes.of(memory.allocate(size), size);
			dependencies = new HeapDependencies();
			pendingKernels = new ArrayList<>();
		}

		/**
		 * Allocates a contiguous block of memory from this stage via bump-pointer advancement.
		 *
		 * <p>Creates a zero-copy {@link Bytes} view into the backing block at the
		 * current bump pointer position, then advances the pointer by {@code count}.</p>
		 *
		 * <p>This method is {@code synchronized} to protect against re-entrant allocation
		 * from the same thread.</p>
		 *
		 * @param count the number of memory units to allocate
		 * @return a {@link Bytes} view into the backing block at the allocated position
		 * @throws IllegalArgumentException if there is not enough free space in the
		 *         backing block ({@code end + count > data.getMemLength()})
		 */
		public synchronized Bytes allocate(int count) {
			if (end + count > data.getMemLength()) {
				throw new IllegalArgumentException("No room remaining in Heap");
			}

			Bytes allocated = new Bytes(count, data, end);
			end = end + count;
			entries.add(allocated);
			return allocated;
		}

		/**
		 * Returns the allocation at the specified index.
		 *
		 * @param index the zero-based index of the allocation (in allocation order)
		 * @return the {@link Bytes} at the given index
		 * @throws IndexOutOfBoundsException if the index is out of range
		 */
		public Bytes get(int index) { return entries.get(index); }

		/**
		 * Returns the backing memory block for this stage.
		 *
		 * <p>This is the pre-allocated block from which all allocations are suballocated.
		 * Useful for inspecting total stage capacity via {@code getBytes().getMemLength()}
		 * and current usage via the bump pointer.</p>
		 *
		 * @return the backing {@link Bytes} block
		 */
		public Bytes getBytes() { return data; }

		/**
		 * Returns a stream of all allocations made from this stage.
		 *
		 * @return a {@link Stream} of {@link Bytes} instances in allocation order
		 */
		public Stream<Bytes> stream() { return entries.stream(); }

		/**
		 * Registers a kernel completion semaphore with this stage.
		 *
		 * <p>When a kernel is dispatched while this stage is active, the kernel's
		 * completion {@link Semaphore} should be registered here so that
		 * {@link #destroy()} can wait for all in-flight kernels to complete
		 * before freeing this stage's memory.</p>
		 *
		 * <p>If the semaphore is {@code null} (e.g., synchronous execution where
		 * the kernel has already completed), this method is a no-op.</p>
		 *
		 * @param sem the kernel completion semaphore, or {@code null}
		 */
		public void addPendingKernel(Semaphore sem) {
			if (sem != null) {
				pendingKernels.add(sem);
			}
		}

		/**
		 * Destroys this stage, freeing all resources.
		 *
		 * <p>Performs the following cleanup in order:</p>
		 * <ol>
		 *   <li>Waits for all pending kernel semaphores to complete, ensuring
		 *       no in-flight kernel is still using this stage's memory</li>
		 *   <li>Clears the allocation entries list</li>
		 *   <li>Resets the bump pointer ({@link #end}) to zero</li>
		 *   <li>Destroys the backing {@link Bytes} block, deallocating its
		 *       underlying memory from the provider</li>
		 *   <li>Destroys the {@link HeapDependencies}, which in turn:</li>
		 *   <ul>
		 *     <li>Destroys all dependent operations (if they implement {@link Destroyable})</li>
		 *     <li>Destroys all compiled operations (calls {@link OperationAdapter#destroy()})</li>
		 *     <li>Destroys all created memory (calls {@link MemoryData#destroy()})</li>
		 *   </ul>
		 *   <li>Sets dependencies to {@code null}</li>
		 * </ol>
		 *
		 * <p>After this method returns, all {@link Bytes} previously returned by
		 * {@link #allocate(int)} are invalid and must not be accessed.</p>
		 */
		@Override
		public void destroy() {
			for (Semaphore sem : pendingKernels) {
				try {
					sem.waitFor();
				} catch (Exception e) {
					heapConsole.warn("Pending kernel wait failed during HeapStage destroy", e);
				}
			}
			pendingKernels.clear();

			entries.clear();
			end = 0;
			data.destroy();

			if (dependencies != null) {
				dependencies.destroy();
				dependencies = null;
			}
		}
	}

	/**
	 * Tracks resources created within a {@link HeapStage} for automatic lifecycle management.
	 *
	 * <p>When a heap stage is active, various framework components register resources
	 * they create so that those resources can be automatically cleaned up when the stage
	 * is destroyed. This class holds three categories of tracked resources:</p>
	 *
	 * <ul>
	 *   <li><strong>{@link #dependentOperations}</strong>: {@link Supplier} instances
	 *       registered via {@link Heap#addOperation(Supplier)}. If a supplier implements
	 *       {@link Destroyable}, its {@code destroy()} method is called during cleanup.</li>
	 *   <li><strong>{@link #compiledDependencies}</strong>: {@link OperationAdapter} instances
	 *       registered via {@link Heap#addCompiled(OperationAdapter)}. These represent
	 *       compiled native kernels. Their {@link OperationAdapter#destroy()} method is
	 *       called during cleanup, which releases the associated
	 *       {@code ScopeInstructionsManager} and native resources. This is how
	 *       {@code DefaultComputer.compileRunnable()} ensures compiled operations are
	 *       freed when the enclosing heap stage exits.</li>
	 *   <li><strong>{@link #createdMemory}</strong>: {@link MemoryData} instances registered
	 *       via {@link Heap#addCreatedMemory(MemoryData)}. These are temporary memory
	 *       allocations made during kernel argument preparation by
	 *       {@code ProcessDetailsFactory}. Their {@link MemoryData#destroy()} method is
	 *       called during cleanup.</li>
	 * </ul>
	 *
	 * <h3>Destruction Order</h3>
	 *
	 * <p>{@link #destroy()} processes the three lists in order: dependent operations first,
	 * then compiled dependencies, then created memory. Each list is set to {@code null}
	 * after processing to prevent double-destruction.</p>
	 *
	 * <h3>Implications for Kernel Reuse</h3>
	 *
	 * <p>Because {@link #compiledDependencies} are destroyed when the stage exits,
	 * compiled operations created within a {@code Heap.stage()} scope are freed after
	 * the scope completes. This means that if the same computation is evaluated again
	 * in a later stage, it will be recompiled (or retrieved from the separate
	 * {@code DefaultComputer.instructionsCache} if the signature matches). The heap's
	 * dependency tracking is for lifecycle management, not for kernel caching.</p>
	 *
	 * @see Heap#addOperation(Supplier)
	 * @see Heap#addCompiled(OperationAdapter)
	 * @see Heap#addCreatedMemory(MemoryData)
	 */
	private class HeapDependencies implements Destroyable {
		/**
		 * Operations registered via {@link Heap#addOperation(Supplier)}.
		 *
		 * <p>If a supplier implements {@link Destroyable}, its {@code destroy()} method
		 * is called when this {@link HeapDependencies} is destroyed. Non-destroyable
		 * suppliers are simply dereferenced.</p>
		 */
		private List<Supplier> dependentOperations;

		/**
		 * Compiled operations registered via {@link Heap#addCompiled(OperationAdapter)}.
		 *
		 * <p>Each adapter's {@link OperationAdapter#destroy()} method is called when
		 * this {@link HeapDependencies} is destroyed. This releases the
		 * {@code ScopeInstructionsManager} associated with the compiled operation,
		 * which in turn may release native kernel resources.</p>
		 *
		 * <p>Note that this is independent of the signature-based instruction cache
		 * in {@code DefaultComputer}. The instruction cache may still hold a reference
		 * to the same {@code ScopeInstructionsManager} if the signature has not been
		 * evicted.</p>
		 */
		private List<OperationAdapter> compiledDependencies;

		/**
		 * Temporary memory registered via {@link Heap#addCreatedMemory(MemoryData)}.
		 *
		 * <p>Typically populated by {@code ProcessDetailsFactory} when creating
		 * temporary buffers for kernel arguments that need sized destinations.
		 * Each memory's {@link MemoryData#destroy()} method is called during cleanup.</p>
		 */
		private List<MemoryData> createdMemory;

		/**
		 * Creates a new dependencies tracker with empty lists for all three categories.
		 */
		public HeapDependencies() {
			dependentOperations = new ArrayList<>();
			compiledDependencies = new ArrayList<>();
			createdMemory = new ArrayList<>();
		}

		/**
		 * Destroys all tracked resources in order: operations, compiled, memory.
		 *
		 * <p>For each category:</p>
		 * <ol>
		 *   <li>Iterates the list and calls the appropriate destroy method</li>
		 *   <li>Sets the list reference to {@code null} to prevent double-destruction
		 *       and release references for garbage collection</li>
		 * </ol>
		 *
		 * <p>The destroy order is significant: dependent operations are destroyed first
		 * because they may reference compiled operations or created memory. Compiled
		 * operations are destroyed before created memory because kernel teardown may
		 * need to access argument memory during cleanup.</p>
		 */
		@Override
		public void destroy() {
			if (dependentOperations != null) {
				dependentOperations.forEach(o -> {
					if (o instanceof Destroyable)
						((Destroyable) o).destroy();
				});
				dependentOperations = null;
			}

			if (compiledDependencies != null) {
				compiledDependencies.forEach(OperationAdapter::destroy);
				compiledDependencies = null;
			}

			if (createdMemory != null) {
				createdMemory.forEach(MemoryData::destroy);
				createdMemory = null;
			}
		}
	}

	/**
	 * Returns the thread-local default heap for the current thread.
	 *
	 * <p>Returns {@code null} if no heap has been activated on this thread via
	 * {@link #use(Runnable)}, {@link #use(Supplier)}, or {@link #wrap(Callable)}.
	 * Many framework components check this value to decide whether to use arena
	 * allocation or standard provider allocation:</p>
	 * <ul>
	 *   <li>{@code PackedCollection.factory()}: Returns a heap-backed factory when
	 *       non-null, or a standard factory when null</li>
	 *   <li>{@code MemoryDataAdapter.init()}: Allocates from the heap when
	 *       {@code getDefaultDelegate()} returns non-null (delegates to this method)</li>
	 *   <li>{@link #stage(Runnable)}: Pushes/pops a stage when non-null, or executes
	 *       the runnable directly when null</li>
	 *   <li>{@link #addCompiled(OperationAdapter)}: Registers the operation when non-null,
	 *       or is a no-op when null</li>
	 *   <li>{@link #addCreatedMemory(MemoryData)}: Registers the memory when non-null,
	 *       or is a no-op when null</li>
	 *   <li>{@code AudioSumProvider} and {@code AudioProcessingUtils}: Throw
	 *       {@link RuntimeException} if non-null during construction, because these
	 *       classes compile kernels with {@code PassThroughProducer} dynamic inputs
	 *       that are incompatible with heap-backed memory</li>
	 * </ul>
	 *
	 * @return the default heap for the current thread, or {@code null}
	 */
	public static Heap getDefault() {
		return defaultHeap.get();
	}

	/**
	 * Executes a {@link Runnable} within a nested heap stage on the thread-local default heap.
	 *
	 * <p>This is the primary API for scoped allocation and dependency tracking. It:</p>
	 * <ol>
	 *   <li>Reads the thread-local default heap via {@link #getDefault()}</li>
	 *   <li>If the default is {@code null}, executes the runnable directly (no staging)</li>
	 *   <li>If the default is non-null:
	 *     <ol type="a">
	 *       <li>Calls {@link #push()} on the default heap, creating a new stage</li>
	 *       <li>Executes the runnable</li>
	 *       <li>Calls {@link #pop()} in a {@code finally} block, destroying the stage
	 *           and all resources tracked within it</li>
	 *     </ol>
	 *   </li>
	 * </ol>
	 *
	 * <p>The no-op behavior when no default heap is set is critical: it allows code
	 * to unconditionally call {@code Heap.stage(() -> ...)} without checking whether
	 * a heap is active. When no heap is active, the code executes normally with standard
	 * allocation. When a heap is active, the code benefits from staged allocation and
	 * automatic cleanup.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * // This works whether or not a heap is active:
	 * Heap.stage(() -> {
	 *     PackedCollection temp = PackedCollection.factory().apply(1000);
	 *     Runnable compiled = someComputation.get();
	 *     compiled.run();
	 *     // If heap active: temp and compiled are freed here
	 *     // If no heap: temp and compiled follow normal GC rules
	 * });
	 * }</pre>
	 *
	 * @param r the runnable to execute within the new stage
	 */
	public static void stage(Runnable r) {
		Heap defaultHeap = getDefault();

		if (defaultHeap == null) {
			r.run();
			return;
		}

		try {
			defaultHeap.push();
			r.run();
		} finally {
			defaultHeap.pop();
		}
	}

	/**
	 * Registers a dependent operation with the current heap stage for lifecycle tracking.
	 *
	 * <p>If a default heap is active on the current thread, adds the operation to the
	 * active stage's {@link HeapDependencies#dependentOperations} list. When the stage
	 * is destroyed, the operation's {@code destroy()} method will be called if it
	 * implements {@link Destroyable}.</p>
	 *
	 * <p>If no default heap is active ({@link #getDefault()} returns {@code null}),
	 * this method is a no-op and simply returns the operation unchanged.</p>
	 *
	 * @param <T> the type parameter of the supplier
	 * @param operation the operation to track
	 * @return the same operation, for use in fluent/chaining patterns
	 */
	public static <T> Supplier<T> addOperation(Supplier<T> operation) {
		if (getDefault() != null) {
			getDefault().getDependentOperations().add(operation);
		}

		return operation;
	}

	/**
	 * Registers a compiled operation with the current heap stage for lifecycle tracking.
	 *
	 * <p>If a default heap is active on the current thread, adds the operation to the
	 * active stage's {@link HeapDependencies#compiledDependencies} list. When the stage
	 * is destroyed, the operation's {@link OperationAdapter#destroy()} method will be
	 * called, releasing its {@code ScopeInstructionsManager} and associated native
	 * resources.</p>
	 *
	 * <p>If no default heap is active ({@link #getDefault()} returns {@code null}),
	 * this method is a no-op and simply returns the operation unchanged.</p>
	 *
	 * <p>This method is called by {@code DefaultComputer.compileRunnable()} for every
	 * {@code AcceleratedComputationOperation} it creates:</p>
	 * <pre>{@code
	 * // In DefaultComputer.compileRunnable():
	 * return Heap.addCompiled(new AcceleratedComputationOperation<>(context, c, true));
	 * }</pre>
	 *
	 * @param <T> the operation type (extends {@link OperationAdapter})
	 * @param operation the compiled operation to track
	 * @return the same operation, for use in fluent/chaining patterns
	 */
	public static <T extends OperationAdapter> T addCompiled(T operation) {
		if (getDefault() != null) {
			getDefault().getCompiledDependencies().add(operation);
		}

		return operation;
	}

	/**
	 * Registers created memory with the current heap stage for lifecycle tracking.
	 *
	 * <p>If a default heap is active on the current thread, adds the memory to the
	 * active stage's {@link HeapDependencies#createdMemory} list. When the stage is
	 * destroyed, the memory's {@link MemoryData#destroy()} method will be called,
	 * deallocating the underlying native memory.</p>
	 *
	 * <p>If no default heap is active ({@link #getDefault()} returns {@code null}),
	 * this method is a no-op and simply returns the memory unchanged.</p>
	 *
	 * <p>This method is called by {@code ProcessDetailsFactory} when creating temporary
	 * buffers for kernel arguments that require sized destinations:</p>
	 * <pre>{@code
	 * // In ProcessDetailsFactory:
	 * MemoryData result = (MemoryData) kernelArgEvaluables[i].createDestination(size);
	 * Heap.addCreatedMemory(result);
	 * }</pre>
	 *
	 * @param <T> the memory type (extends {@link MemoryData})
	 * @param memory the memory to track
	 * @return the same memory, for use in fluent/chaining patterns
	 */
	public static <T extends MemoryData> T addCreatedMemory(T memory) {
		if (getDefault() != null) {
			getDefault().getCreatedMemory().add(memory);
		}

		return memory;
	}

	/**
	 * Registers a kernel completion semaphore with the current heap stage.
	 *
	 * <p>If a default heap is active on the current thread, delegates to
	 * {@link HeapStage#addPendingKernel(Semaphore)} on the active stage.
	 * When the stage is later destroyed (via {@link #pop()}), it will wait
	 * for all registered semaphores to complete before freeing memory.</p>
	 *
	 * <p>If no default heap is active ({@link #getDefault()} returns {@code null}),
	 * or if the semaphore is {@code null}, this method is a no-op.</p>
	 *
	 * @param sem the kernel completion semaphore, or {@code null}
	 */
	public static void addPendingKernel(Semaphore sem) {
		Heap heap = getDefault();
		if (heap != null) {
			heap.getStage().addPendingKernel(sem);
		}
	}
}
