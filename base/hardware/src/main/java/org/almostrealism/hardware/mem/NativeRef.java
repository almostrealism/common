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

import java.lang.ref.ReferenceQueue;

/**
 * Phantom reference to native memory with cached address and size for post-GC deallocation.
 *
 * <p>{@link NativeRef} extends {@link MemoryReference} to provide a phantom reference that caches
 * the native memory address and size. This enables native deallocation after the Java {@link RAM}
 * object has been garbage collected, when the original object is no longer accessible.</p>
 *
 * <h2>Why Cache Address and Size?</h2>
 *
 * <p>After garbage collection, the referenced {@link RAM} object is no longer accessible via
 * {@link #get()} (always returns null for phantom references). However, native memory must still
 * be freed. Caching the address and size allows deallocation without accessing the collected object:</p>
 *
 * <pre>{@code
 * // At allocation
 * RAM memory = allocateNative(1000);
 * NativeRef<RAM> ref = new NativeRef<>(memory, queue);
 * // Caches: address = memory.getContainerPointer()
 * //         size = memory.getSize()
 *
 * // After GC
 * memory = null;
 * System.gc();
 *
 * // Background thread detects collection
 * NativeRef<RAM> collected = (NativeRef<RAM>) queue.remove();
 * // collected.get() returns null (object is gone)
 * // But we can still free native memory:
 * freeNative(collected.getAddress(), collected.getSize());
 * }</pre>
 *
 * <h2>Integration with HardwareMemoryProvider</h2>
 *
 * <p>{@link HardwareMemoryProvider} uses {@link NativeRef} to track all allocations and
 * automatically deallocate when garbage collected:</p>
 *
 * <pre>{@code
 * public class CLMemoryProvider extends HardwareMemoryProvider<CLMemory> {
 *     private Map<Long, NativeRef<CLMemory>> allocated = new HashMap<>();
 *     private ReferenceQueue<CLMemory> queue = new ReferenceQueue<>();
 *
 *     public CLMemory allocate(int size) {
 *         CLMemory memory = clCreateBuffer(...);
 *         NativeRef<CLMemory> ref = new NativeRef<>(memory, queue);
 *         allocated.put(ref.getAddress(), ref);
 *         return memory;
 *     }
 *
 *     // Background thread
 *     void deallocationThread() {
 *         while (true) {
 *             NativeRef<CLMemory> ref = (NativeRef<CLMemory>) queue.remove();
 *             clReleaseMemObject(ref.getAddress());  // Free using cached address
 *             allocated.remove(ref.getAddress());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Equality and Hashing</h2>
 *
 * <p>Equality is based on address and size, not object identity. This allows lookup even after
 * the original {@link RAM} object is garbage collected:</p>
 *
 * <pre>{@code
 * RAM memory = allocate(1000);
 * NativeRef<RAM> ref1 = new NativeRef<>(memory, queue);
 * NativeRef<RAM> ref2 = new NativeRef<>(memory, queue);
 *
 * ref1.equals(ref2)  // true (same address and size)
 * ref1 == ref2       // false (different reference objects)
 *
 * // After GC, can still lookup by address
 * Map<Long, NativeRef<RAM>> map = new HashMap<>();
 * map.put(ref1.getAddress(), ref1);
 * NativeRef<RAM> found = map.get(ref1.getAddress());  // Works even after GC
 * }</pre>
 *
 * <h2>Phantom Reference Behavior</h2>
 *
 * <p><b>Important:</b> {@link NativeRef} is a phantom reference (via {@link MemoryReference} which
 * extends {@link java.lang.ref.WeakReference}), meaning:</p>
 * <ul>
 *   <li>{@code get()} returns {@code null} after the referent is finalized</li>
 *   <li>Enqueued only after finalization completes</li>
 *   <li>Does not prevent or delay garbage collection</li>
 * </ul>
 *
 * @param <T> RAM type being referenced
 * @see MemoryReference
 * @see HardwareMemoryProvider
 * @see RAM
 */
public class NativeRef<T extends RAM> extends MemoryReference<T> {
	private long address;
	private long size;

	public NativeRef(T ref, ReferenceQueue<? super T> queue) {
		super(ref, queue);
		this.address = ref.getContainerPointer();
		this.size = ref.getSize();
		setAllocationStackTrace(ref.getAllocationStackTrace());
	}

	public long getAddress() { return address; }

	public long getSize() { return size; }

	@Override
	public boolean equals(Object obj) {
		return (obj instanceof NativeRef<?> other) &&
				other.address == address &&
				other.size == size;
	}

	@Override
	public int hashCode() {
		return (Long.hashCode(address) * 31) + Long.hashCode(size);
	}

	@Override
	public String toString() {
		return String.format("%s[%d]", getClass().getSimpleName(), getSize());
	}
}
