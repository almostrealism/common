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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.expression.Expression;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.HashMap;
import java.util.Map;

/**
 * Base implementation of {@link MemoryData} with delegation support and memory versioning.
 *
 * <p>{@link MemoryDataAdapter} provides the foundation for all hardware-accessible memory types,
 * supporting both direct memory ownership and zero-copy delegation to other memory. It implements
 * memory versioning to enable efficient switching between different hardware backends without
 * unnecessary copying.</p>
 *
 * <h2>Memory Ownership Models</h2>
 *
 * <p>A {@link MemoryDataAdapter} instance operates in one of two modes:</p>
 *
 * <h3>1. Direct Ownership (Root Memory)</h3>
 * <p>The instance directly owns a {@link Memory} object allocated from a {@link MemoryProvider}:</p>
 * <pre>{@code
 * class MyMemory extends MemoryDataAdapter {
 *     public MyMemory(int size) {
 *         init();  // Allocates Memory from Hardware.getLocalHardware()
 *     }
 *
 *     public int getMemLength() { return 1000; }
 * }
 *
 * MyMemory memory = new MyMemory(1000);
 * // memory.getMem() returns directly-owned Memory instance
 * }</pre>
 *
 * <h3>2. Delegated Memory (Zero-Copy View)</h3>
 * <p>The instance is a lightweight view into another {@link MemoryData} at a specified offset:</p>
 * <pre>{@code
 * MemoryData original = new MyMemory(10000);
 * MemoryData view = new MyMemory(100);
 * view.setDelegate(original, 500, null);  // View bytes 500-600 of original
 *
 * // view.getMem() returns original.getMem()
 * // view modifications affect original at offset 500
 * }</pre>
 *
 * <p>Delegation enables zero-copy patterns like:</p>
 * <ul>
 *   <li>Range views (see {@link Bytes#range(int, int)})</li>
 *   <li>Heap suballocation (see {@link Heap#allocate(int)})</li>
 *   <li>Reshaping and subsetting collections</li>
 * </ul>
 *
 * <h2>Memory Versioning</h2>
 *
 * <p>When memory is reallocated for a different {@link MemoryProvider} (e.g., switching from
 * CPU to GPU), {@link MemoryDataAdapter} maintains versioned copies to avoid redundant transfers:</p>
 *
 * <pre>{@code
 * MemoryData data = new MyMemory(1000);
 *
 * // First allocation on CPU provider
 * data.reallocate(cpuProvider);  // Allocates and copies data
 *
 * // Switch to GPU provider
 * data.reallocate(gpuProvider);  // Allocates GPU memory, keeps CPU version
 *
 * // Switch back to CPU
 * data.reallocate(cpuProvider);  // Reuses existing CPU version (fast!)
 * }</pre>
 *
 * <p>This optimization is controlled by {@code enableMemVersions} (default: true).</p>
 *
 * <h2>Delegation Safety</h2>
 *
 * <p>Delegation is subject to safety constraints to prevent invalid memory access:</p>
 * <ul>
 *   <li><b>No circular delegation</b>: A cannot delegate to itself</li>
 *   <li><b>Depth limit</b>: Maximum delegation depth is 25 (prevents stack overflow)</li>
 *   <li><b>Bounds checking</b>: Offset + length must fit within delegate's bounds</li>
 * </ul>
 *
 * <pre>{@code
 * MemoryData a = new MyMemory(100);
 * MemoryData b = new MyMemory(50);
 *
 * b.setDelegate(a, 0, null);    // OK: 50 bytes within a's 100 bytes
 * b.setDelegate(a, 75, null);   // FAIL: 75 + 50 > 100 (out of bounds)
 * a.setDelegate(a, 0, null);    // FAIL: Circular reference
 * }</pre>
 *
 * <h2>Integration with Heap</h2>
 *
 * <p>Subclasses can override {@link #getDefaultDelegate()} to auto-delegate to the thread-local
 * {@link Heap}, enabling arena allocation without explicit delegation:</p>
 *
 * <pre>{@code
 * // Subclass with heap integration
 * class HeapMemory extends MemoryDataAdapter {
 *     protected Heap getDefaultDelegate() {
 *         return Heap.getDefault();  // Auto-use heap if available
 *     }
 * }
 *
 * // Usage with heap
 * Heap heap = new Heap(10000);
 * heap.use(() -> {
 *     HeapMemory temp = new HeapMemory(100);  // Auto-allocated from heap
 * });
 * }</pre>
 *
 * <h2>Lifecycle Management</h2>
 *
 * <p><b>Destroy:</b> Explicitly destroys owned memory. Delegated instances do not destroy
 * their delegate:</p>
 * <pre>{@code
 * MemoryData root = new MyMemory(1000);
 * MemoryData view = new MyMemory(100);
 * view.setDelegate(root, 0, null);
 *
 * view.destroy();  // Does nothing (delegated)
 * root.destroy();  // Deallocates memory
 * }</pre>
 *
 * <p><b>Finalize:</b> Java finalizer integration is disabled by default ({@code enableFinalizer = false})
 * due to unreliable timing. Explicit {@link #destroy()} is required.</p>
 *
 * <h2>Common Subclasses</h2>
 *
 * <ul>
 *   <li>{@link Bytes}: Raw memory wrapper with atomic element support</li>
 *   <li>PackedCollection: Typed collections with shape and ordering</li>
 *   <li>MemoryBankAdapter: Multi-element banks with indexed access</li>
 * </ul>
 *
 * @see MemoryData
 * @see Bytes
 * @see Heap
 * @see MemoryProvider
 */
public abstract class MemoryDataAdapter implements MemoryData, ConsoleFeatures {
	public static boolean enableMemVersions = true;
	public static boolean enableFinalizer = false;

	private Memory mem;
	private Map<MemoryProvider, Memory> memVersions;

	private MemoryData delegateMem;
	private int delegateMemOffset;
	private TraversalOrdering delegateOrder;

	protected void init() {
		if (getDelegate() == null) {
			Heap heap = getDefaultDelegate();

			if (heap == null) {
				mem = Hardware.getLocalHardware().getMemoryProvider(getMemLength()).allocate(getMemLength());
			} else {
				Bytes data = heap.allocate(getMemLength());
				setDelegate(data.getDelegate(), data.getDelegateOffset(), data.getDelegateOrdering());
				setMem(new double[getMemLength()]);
			}
		}
	}

	protected void init(Memory mem) {
		this.mem = mem;
	}

	@Override
	public Memory getMem() { return getDelegate() == null ? mem : getDelegate().getMem(); }

	@Override
	public MemoryData getDelegate() { return delegateMem; }

	@Override
	public int getDelegateOffset() { return delegateMemOffset; }

	@Override
	public TraversalOrdering getDelegateOrdering() { return delegateOrder; }

	@Override
	public void setDelegate(MemoryData m, int offset, TraversalOrdering order) {
		this.delegateOrder = order;

		if (m != null) {
			if (m == this) {
				throw new IllegalArgumentException("Circular delegate reference");
			} else if (m.getDelegateDepth() > 25) {
				throw new IllegalStateException("Delegation depth exceeded");
			} else if (offset >= m.getMemLength()) {
				throw new HardwareException("Delegate offset is out of bounds");
			} else if (offset + getDelegatedLength() > m.getMemLength()) {
				throw new HardwareException("MemoryData extends beyond the length of the delegate");
			}
		}

		this.delegateMem = m;
		this.delegateMemOffset = offset;
	}

	public Heap getDefaultDelegate() { return null; }

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return MemoryData.super.containsIndex(index);
	}

	@Override
	public void reallocate(MemoryProvider<?> provider) {
		if (getOffset() != 0) {
			throw new HardwareException("Cannot reallocate memory with non-zero offset");
		} if (memVersions == null || !memVersions.containsKey(provider)) {
			MemoryData.super.reallocate(provider);
		} else {
			Memory mem = memVersions.get(provider);
			mem.getProvider().setMem(mem, 0, this.mem, 0, getMemLength());
			reassign(mem);
		}
	}

	@Override
	public void reassign(Memory mem) {
		if (delegateMem != null || mem == null) {
			throw new HardwareException("Only root memory can be reassigned");
		}

		if (enableMemVersions && memVersions == null)
			memVersions = new HashMap<>();

		if (memVersions == null) {
			this.mem.getProvider().deallocate(getMemLength(), this.mem);
		} else {
			memVersions.put(this.mem.getProvider(), this.mem);
		}

		this.mem = mem;
	}

	@Override
	public void destroy() {
		if (delegateMem != null && !delegateMem.isDestroyed()) {
			if (mem == null) {
				if (RAM.enableWarnings) warn("Attempting to destroy memory alias");
			} else {
				warn("MemoryData has a delegate, but also directly reserved memory");
			}
		}

		if (mem != null) {
			mem.getProvider().deallocate(getMemLength(), mem);
			mem = null;
		}
	}

	@Override
	public void finalize() {
		if (mem != null) {
			if (enableFinalizer) {
				destroy();
			} else {
				mem = null;
			}
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
