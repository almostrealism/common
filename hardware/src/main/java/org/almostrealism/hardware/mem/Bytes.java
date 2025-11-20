/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;

/**
 * Lightweight wrapper for raw memory with atomic element support and zero-copy views.
 *
 * <p>{@link Bytes} provides a flexible memory container that can represent both simple byte
 * buffers and typed arrays. It implements {@link MemoryBank} to support indexed access to
 * fixed-size elements and extends {@link MemoryDataAdapter} for delegation-based memory views.</p>
 *
 * <h2>Core Concepts</h2>
 *
 * <h3>Memory Length vs Atomic Length</h3>
 *
 * <p>Two key dimensions define a {@link Bytes} instance:</p>
 * <ul>
 *   <li><b>memLength</b>: Total size in bytes of the underlying memory</li>
 *   <li><b>atomicLength</b>: Size in bytes of a single element/atom (must divide memLength evenly)</li>
 * </ul>
 *
 * <pre>
 * Example:
 *   Bytes buffer = new Bytes(1000);           // memLength=1000, atomicLength=1000 (single element)
 *   Bytes floats = new Bytes(400, 4);         // memLength=400, atomicLength=4 (100 floats)
 *   Bytes vectors = new Bytes(1200, 12);      // memLength=1200, atomicLength=12 (100 vec3s)
 *
 *   floats.getCountLong() -> 100               // 400 / 4 = 100 elements
 *   vectors.getCountLong() -> 100              // 1200 / 12 = 100 elements
 * </pre>
 *
 * <h3>Zero-Copy Delegation</h3>
 *
 * <p>{@link Bytes} supports zero-copy views into existing memory through delegation. This allows
 * creating lightweight "windows" into larger memory blocks without copying data:</p>
 *
 * <pre>{@code
 * // Original memory block
 * Bytes original = new Bytes(1000);
 *
 * // Zero-copy view of bytes 100-200
 * Bytes view = new Bytes(100, original, 100);
 *
 * // Modifications to view affect original
 * view.setMem(0, 42.0);  // Also modifies original at offset 100
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Simple Byte Buffer</h3>
 * <pre>{@code
 * // Allocate 500 bytes
 * Bytes buffer = new Bytes(500);
 * buffer.setMem(0, 1.0f);
 * buffer.setMem(1, 2.0f);
 * }</pre>
 *
 * <h3>Typed Arrays</h3>
 * <pre>{@code
 * // 100 floats (4 bytes each)
 * Bytes floats = new Bytes(400, 4);
 * System.out.println(floats.getCountLong());  // 100
 *
 * // Access 10th float via MemoryBank interface
 * Bytes tenthFloat = floats.get(9);  // Returns Bytes view at offset 36
 * }</pre>
 *
 * <h3>Range Operations for Suballocation</h3>
 * <pre>{@code
 * // Allocate workspace
 * Bytes workspace = new Bytes(10000);
 *
 * // Create views for different purposes (zero-copy)
 * Bytes inputBuffer = workspace.range(0, 1000);
 * Bytes outputBuffer = workspace.range(1000, 2000);
 * Bytes tempBuffer = workspace.range(2000, 3000);
 *
 * // All views share the same underlying memory
 * }</pre>
 *
 * <h3>Integration with Heap</h3>
 * <pre>{@code
 * Heap heap = new Heap(10000);
 *
 * // Heap.allocate() returns Bytes instances
 * Bytes temp1 = heap.allocate(100);  // Bytes delegating to heap memory
 * Bytes temp2 = heap.allocate(50);   // Adjacent in heap's memory block
 *
 * // All destroyed when heap is destroyed
 * heap.destroy();
 * }</pre>
 *
 * <h2>MemoryBank Implementation</h2>
 *
 * <p>{@link Bytes} implements {@link MemoryBank} with the following behavior:</p>
 * <ul>
 *   <li>{@code get(int index)}: Returns zero-copy view at {@code index * atomicLength}</li>
 *   <li>{@code set(int index, Bytes value)}: Not supported (throws UnsupportedOperationException)</li>
 *   <li>{@code getCountLong()}: Returns {@code memLength / atomicLength}</li>
 *   <li>{@code getAtomicMemLength()}: Returns size of single element</li>
 * </ul>
 *
 * <h2>Memory Ownership</h2>
 *
 * <p><b>Owned Memory</b>: Created via {@code new Bytes(int)} constructors. Allocated directly and
 * destroyed when {@link #destroy()} is called.</p>
 *
 * <p><b>Delegated Memory</b>: Created via constructors taking {@code MemoryData delegate} or via
 * {@link #range(int, int)}. Does not own memory; destruction does not free underlying memory.</p>
 *
 * @see MemoryDataAdapter
 * @see MemoryBank
 * @see Heap
 * @see RAM
 */
public class Bytes extends MemoryDataAdapter implements MemoryBank<Bytes> {
	private final int atomicLength;
	private final int memLength;

	private Bytes(Memory mem, int memLength) {
		this.atomicLength = memLength;
		this.memLength = memLength;
		init(mem);
	}

	public Bytes(int memLength) {
		this(memLength, memLength);
	}

	public Bytes(int memLength, int atomicLength) {
		if (atomicLength == 0) {
			throw new IllegalArgumentException();
		}

		if (memLength % atomicLength != 0) {
			throw new IllegalArgumentException("Memory length must be a multiple of atomic length");
		}

		this.atomicLength = atomicLength;
		this.memLength = memLength;
		init();
	}

	public Bytes(int memLength, MemoryData delegate, int delegateOffset) {
		this(memLength, memLength, delegate, delegateOffset);
	}

	public Bytes(int memLength, int atomicLength, MemoryData delegate, int delegateOffset) {
		this.atomicLength = atomicLength;
		this.memLength = memLength;
		setDelegate(delegate, delegateOffset);
	}

	@Override
	public void set(int index, Bytes value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bytes get(int index) {
		return range(index * getAtomicMemLength(), getAtomicMemLength());
	}

	@Override
	public long getCountLong() { return getMemLength() / getAtomicMemLength(); }

	@Override
	public int getAtomicMemLength() { return atomicLength; }

	@Override
	public int getMemLength() {
		return memLength;
	}

	public Bytes range(int start, int length) {
		return range(start, length, length);
	}

	public Bytes range(int start, int length, int atomicLength) {
		if (start < 0 || start + length > getMemLength()) {
			throw new IllegalArgumentException();
		}

		return new Bytes(length, atomicLength, this, start);
	}

	public static Bytes of(Memory mem, int memLength) {
		return new Bytes(mem, memLength);
	}
}
