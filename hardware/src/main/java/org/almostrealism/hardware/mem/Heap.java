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
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Thread-local memory arena for temporary allocations with automatic lifecycle management.
 *
 * <p>{@link Heap} provides a stack-based arena allocator for short-lived {@link Bytes} allocations.
 * It uses thread-local storage to avoid synchronization overhead and supports staged allocation
 * for nested scopes with automatic cleanup.</p>
 *
 * <h2>Core Concept</h2>
 *
 * <p>Instead of individually allocating many small {@link Bytes} objects, {@link Heap} pre-allocates
 * a large memory block and suballocates from it:</p>
 *
 * <pre>
 * Traditional Approach (slow):
 *   Bytes b1 = new Bytes(100);  // Allocation 1
 *   Bytes b2 = new Bytes(50);   // Allocation 2
 *   Bytes b3 = new Bytes(200);  // Allocation 3
 *   Total: 3 allocations
 *
 * Heap Approach (fast):
 *   Heap heap = new Heap(1000);
 *   Bytes b1 = heap.allocate(100);  // Suballocation (fast)
 *   Bytes b2 = heap.allocate(50);   // Suballocation (fast)
 *   Bytes b3 = heap.allocate(200);  // Suballocation (fast)
 *   Total: 1 allocation, 3 suballocations
 * </pre>
 *
 * <h2>Thread-Local Default Heap</h2>
 *
 * <p>{@link Heap} maintains a thread-local default instance accessible via {@link #getDefault()}:</p>
 * <pre>{@code
 * // Set default heap for current thread
 * Heap myHeap = new Heap(10000);
 * myHeap.use(() -> {
 *     // All allocations within this scope use myHeap
 *     Bytes temp = Heap.getDefault().allocate(100);
 *     // Work with temp...
 * });
 * // myHeap auto-restored after scope
 * }</pre>
 *
 * <h2>Staged Allocation</h2>
 *
 * <p>Stages create nested allocation scopes that are automatically cleaned up:</p>
 * <pre>{@code
 * Heap.stage(() -> {
 *     // Allocations in this stage
 *     Bytes temp1 = Heap.getDefault().allocate(100);
 *     Bytes temp2 = Heap.getDefault().allocate(50);
 *
 *     Heap.stage(() -> {
 *         // Nested stage
 *         Bytes temp3 = Heap.getDefault().allocate(200);
 *         // temp3 destroyed on exit
 *     });
 *
 *     // temp1, temp2 still valid here
 * });
 * // All stage allocations destroyed
 * }</pre>
 *
 * <h2>Dependency Tracking</h2>
 *
 * <p>Heaps track operations and memory created within their scope for automatic cleanup:</p>
 * <pre>{@code
 * Heap heap = new Heap(10000);
 * heap.use(() -> {
 *     // Track compiled operation
 *     Runnable op = compileOperation();
 *     Heap.addCompiled(op);
 *
 *     // Track created memory
 *     PackedCollection data = new PackedCollection(100);
 *     Heap.addCreatedMemory(data);
 * });
 * heap.destroy();  // Destroys op and data automatically
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Temporary Allocations in Computation</h3>
 * <pre>{@code
 * public Evaluable<?> createEvaluable() {
 *     Heap heap = new Heap(1000);
 *     return heap.wrap(() -> {
 *         // Temporary allocations during evaluation
 *         Bytes workspace = heap.allocate(100);
 *         // Use workspace...
 *         return result;
 *     });
 * }
 * }</pre>
 *
 * <h3>Scoped Resource Management</h3>
 * <pre>{@code
 * Heap.stage(() -> {
 *     // All allocations and operations tracked
 *     Bytes temp = Heap.getDefault().allocate(500);
 *     Runnable op = compileKernel();
 *     Heap.addOperation(() -> op);
 *     // Everything auto-destroyed on scope exit
 * });
 * }</pre>
 *
 * @see Bytes
 * @see HeapStage
 */
public class Heap {
	private static ThreadLocal<Heap> defaultHeap = new ThreadLocal<>();

	private MemoryProvider memory;
	private int stageSize;

	private HeapStage root;
	private Stack<HeapStage> stages;

	/**
	 * Creates a heap with the specified root size and default stage size (rootSize / 4).
	 *
	 * @param size The root allocation size in bytes
	 */
	public Heap(int size) {
		this(null, size, size / 4);
	}

	/**
	 * Creates a heap with explicit root and stage sizes.
	 *
	 * @param rootSize The root allocation size in bytes
	 * @param stageSize The size for each nested stage in bytes
	 */
	public Heap(int rootSize, int stageSize) {
		this(null, rootSize, stageSize);
	}

	/**
	 * Creates a heap using a specific memory provider.
	 *
	 * @param memory The memory provider for allocations (null for default)
	 * @param rootSize The root allocation size in bytes
	 * @param stageSize The size for each nested stage in bytes
	 */
	public Heap(MemoryProvider memory, int rootSize, int stageSize) {
		this.memory = memory;
		this.stageSize = stageSize;
		this.root = new HeapStage(rootSize);
	}

	/**
	 * Returns the currently active heap stage (top of stack, or root if no stages).
	 *
	 * @return The active heap stage
	 */
	public HeapStage getStage() {
		return (stages == null || stages.isEmpty()) ? root : stages.peek();
	}

	/**
	 * Allocates memory from the current heap stage.
	 *
	 * <p>Returns a {@link Bytes} instance backed by this heap's memory.
	 * The allocation is destroyed when the current stage is destroyed.</p>
	 *
	 * @param count Number of bytes to allocate
	 * @return Allocated bytes backed by this heap
	 * @throws IllegalArgumentException if insufficient space remains in the current stage
	 */
	public Bytes allocate(int count) {
		return getStage().allocate(count);
	}

	/**
	 * Wraps a callable to execute with this heap as the default.
	 *
	 * <p>The returned callable sets this heap as the thread-local default before
	 * executing and restores the previous default afterwards.</p>
	 *
	 * @param <T> The return type of the callable
	 * @param r The callable to wrap
	 * @return A wrapped callable that uses this heap as default during execution
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
	 * Executes a runnable with this heap as the thread-local default.
	 *
	 * <p>Sets this heap as the default, runs the runnable, and restores the
	 * previous default regardless of exceptions.</p>
	 *
	 * @param r The runnable to execute
	 * @return This heap (for method chaining)
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
	 * Executes a supplier with this heap as the thread-local default.
	 *
	 * <p>Sets this heap as the default, evaluates the supplier, and restores
	 * the previous default regardless of exceptions.</p>
	 *
	 * @param <T> The return type of the supplier
	 * @param r The supplier to execute
	 * @return The result of the supplier
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
	 * Pushes a new stage onto the stack.
	 *
	 * <p>Creates a new {@link HeapStage} with the configured stage size
	 * and adds it to the stage stack. Allocations will use this new stage
	 * until it is popped.</p>
	 */
	protected void push() {
		if (stages == null) {
			stages = new Stack<>();
		}

		stages.push(new HeapStage(stageSize));
	}

	/**
	 * Pops and destroys the top stage from the stack.
	 *
	 * <p>Removes the topmost stage and calls its {@code destroy()} method,
	 * freeing all allocations made within that stage.</p>
	 */
	protected void pop() {
		if (stages != null && !stages.isEmpty()) {
			stages.pop().destroy();
		}
	}

	/**
	 * Destroys this heap and all its stages.
	 *
	 * <p>Pops and destroys all stages on the stack, then destroys the root stage.
	 * All allocations and tracked dependencies are freed.</p>
	 */
	public synchronized void destroy() {
		if (stages != null) {
			while (!stages.isEmpty()) {
				stages.pop().destroy();
			}
		}

		root.destroy();
	}

	private List<Supplier> getDependentOperations() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.dependentOperations;
		}

		return root.dependencies == null ? null : root.dependencies.dependentOperations;
	}

	private List<OperationAdapter> getCompiledDependencies() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.compiledDependencies;
		}

		return root.dependencies == null ? null : root.dependencies.compiledDependencies;
	}

	private List<MemoryData> getCreatedMemory() {
		if (stages != null && !stages.isEmpty()) {
			return stages.peek().dependencies.createdMemory;
		}

		return root.dependencies == null ? null : root.dependencies.createdMemory;
	}

	public class HeapStage implements Destroyable {
		private List<Bytes> entries;
		private Bytes data;
		private int end;

		HeapDependencies dependencies;

		public HeapStage(int size) {
			entries = new ArrayList<>();
			data = memory == null ? new Bytes(size) : Bytes.of(memory.allocate(size), size);
			dependencies = new HeapDependencies();
		}

		public synchronized Bytes allocate(int count) {
			if (end + count > data.getMemLength()) {
				throw new IllegalArgumentException("No room remaining in Heap");
			}

			Bytes allocated = new Bytes(count, data, end);
			end = end + count;
			entries.add(allocated);
			return allocated;
		}

		public Bytes get(int index) { return entries.get(index); }

		public Bytes getBytes() { return data; }

		public Stream<Bytes> stream() { return entries.stream(); }

		@Override
		public void destroy() {
			entries.clear();
			end = 0;
			data.destroy();

			if (dependencies != null) {
				dependencies.destroy();
				dependencies = null;
			}
		}
	}

	private class HeapDependencies implements Destroyable {
		private List<Supplier> dependentOperations;
		private List<OperationAdapter> compiledDependencies;
		private List<MemoryData> createdMemory;

		public HeapDependencies() {
			dependentOperations = new ArrayList<>();
			compiledDependencies = new ArrayList<>();
			createdMemory = new ArrayList<>();
		}

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
	 * Returns the thread-local default heap, or null if none is set.
	 *
	 * @return The default heap for this thread
	 */
	public static Heap getDefault() {
		return defaultHeap.get();
	}

	/**
	 * Executes a {@link Runnable} in a nested heap stage.
	 *
	 * <p>Creates a new stage on the default heap, executes the runnable,
	 * and automatically destroys the stage (and all allocations within it)
	 * when the runnable completes.</p>
	 *
	 * <p>If no default heap is set, the runnable executes without staging.</p>
	 *
	 * @param r The runnable to execute in a staged context
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
	 * Adds an operation to the current heap's dependency list for automatic cleanup.
	 *
	 * <p>Operations added are destroyed when the heap is destroyed.</p>
	 *
	 * @param operation The operation to track
	 * @param <T> The operation type
	 * @return The same operation (for chaining)
	 */
	public static <T> Supplier<T> addOperation(Supplier<T> operation) {
		if (getDefault() != null) {
			getDefault().getDependentOperations().add(operation);
		}

		return operation;
	}

	/**
	 * Adds a compiled operation to the current heap's dependency list.
	 *
	 * <p>The operation is destroyed when the heap is destroyed.</p>
	 *
	 * @param operation The compiled operation to track
	 * @param <T> The operation type
	 * @return The same operation (for chaining)
	 */
	public static <T extends OperationAdapter> T addCompiled(T operation) {
		if (getDefault() != null) {
			getDefault().getCompiledDependencies().add(operation);
		}

		return operation;
	}

	/**
	 * Adds memory data to the current heap's dependency list for automatic cleanup.
	 *
	 * <p>The memory is destroyed when the heap is destroyed.</p>
	 *
	 * @param memory The memory to track
	 * @param <T> The memory type
	 * @return The same memory (for chaining)
	 */
	public static <T extends MemoryData> T addCreatedMemory(T memory) {
		if (getDefault() != null) {
			getDefault().getCreatedMemory().add(memory);
		}

		return memory;
	}
}

