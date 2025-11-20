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

package org.almostrealism.hardware.jvm;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;

/**
 * {@link MemoryProvider} for pure Java heap memory allocation.
 *
 * <p>Simplest possible memory provider that allocates standard Java double arrays
 * on the heap. Used for testing, development, and non-hardware-accelerated scenarios
 * where {@link org.almostrealism.hardware.mem.Heap} overhead is not needed.</p>
 *
 * <h2>Characteristics</h2>
 *
 * <ul>
 *   <li><strong>Storage:</strong> Java heap ({@code double[]})</li>
 *   <li><strong>Precision:</strong> FP64 only (8 bytes per element)</li>
 *   <li><strong>Performance:</strong> No native memory overhead, but no hardware acceleration</li>
 *   <li><strong>GC Integration:</strong> Automatic garbage collection of arrays</li>
 * </ul>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Testing:</strong> Unit tests that don't require hardware acceleration</li>
 *   <li><strong>Prototyping:</strong> Quick development without backend setup</li>
 *   <li><strong>Fallback:</strong> When no hardware backends are available</li>
 *   <li><strong>Small Data:</strong> Workloads too small to benefit from acceleration</li>
 * </ul>
 *
 * <h2>Comparison to Other Providers</h2>
 *
 * <table border="1">
 *   <tr>
 *     <th>Provider</th>
 *     <th>Storage</th>
 *     <th>Overhead</th>
 *     <th>Use Case</th>
 *   </tr>
 *   <tr>
 *     <td>JVMMemoryProvider</td>
 *     <td>Java heap</td>
 *     <td>None</td>
 *     <td>Testing, fallback</td>
 *   </tr>
 *   <tr>
 *     <td>{@link org.almostrealism.hardware.mem.Heap}</td>
 *     <td>Off-heap</td>
 *     <td>Low</td>
 *     <td>Temporary memory staging</td>
 *   </tr>
 *   <tr>
 *     <td>{@link org.almostrealism.hardware.cl.CLMemoryProvider}</td>
 *     <td>GPU memory</td>
 *     <td>Medium</td>
 *     <td>GPU computation</td>
 *   </tr>
 * </table>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * JVMMemoryProvider provider = new JVMMemoryProvider();
 * Memory mem = provider.allocate(1000);
 * provider.setMem(mem, 0, data, 0, 1000);
 * provider.getMem(mem, 0, result, 0, 1000);
 * }</pre>
 *
 * @see JVMMemory
 * @see org.almostrealism.hardware.mem.Heap
 */
public class JVMMemoryProvider implements MemoryProvider<Memory> {
	/**
	 * Creates a JVM memory provider for Java heap allocation.
	 */
	public JVMMemoryProvider() { }

	/**
	 * Returns the provider name for identification.
	 *
	 * @return "JVM" to indicate Java heap storage
	 */
	@Override
	public String getName() { return "JVM"; }

	/**
	 * Returns the size of each number in bytes.
	 *
	 * @return 8 (FP64 double precision)
	 */
	@Override
	public int getNumberSize() { return 8; }

	/**
	 * Allocates Java heap memory for the specified number of elements.
	 *
	 * @param size Number of doubles to allocate (must be positive)
	 * @return {@link JVMMemory} backed by a Java double array
	 * @throws IllegalArgumentException if size is not positive
	 */
	@Override
	public Memory allocate(int size) {
		if (size <= 0)
			throw new IllegalArgumentException();
		return new JVMMemory(this, size);
	}

	/**
	 * Deallocates Java heap memory by destroying the memory instance.
	 *
	 * @param size Size of the memory (ignored)
	 * @param mem Memory to deallocate
	 */
	@Override
	public void deallocate(int size, Memory mem) {
		((JVMMemory) mem).destroy();
	}

	/**
	 * Copies data from source memory to destination memory.
	 *
	 * <p>If both source and destination are {@link JVMMemory}, performs
	 * direct array copy. Otherwise, converts source to array first.</p>
	 *
	 * @param mem Destination memory
	 * @param offset Destination offset
	 * @param source Source memory
	 * @param srcOffset Source offset
	 * @param length Number of elements to copy
	 */
	@Override
	public void setMem(Memory mem, int offset, Memory source, int srcOffset, int length) {
		if (source instanceof JVMMemory) {
			JVMMemory src = (JVMMemory) source;
			JVMMemory dest = (JVMMemory) mem;
			for (int i = 0; i < length; i++) {
				dest.data[offset + i] = src.data[srcOffset + i];
			}
		} else {
			setMem(mem, offset, source.toArray(srcOffset, length), 0, length);
		}
	}

	/**
	 * Copies data from double array to memory.
	 *
	 * @param mem Destination memory
	 * @param offset Destination offset
	 * @param source Source array
	 * @param srcOffset Source array offset
	 * @param length Number of elements to copy
	 */
	@Override
	public void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		JVMMemory dest = (JVMMemory) mem;
		for (int i = 0; i < length; i++) {
			dest.data[offset + i] = source[srcOffset + i];
		}
	}

	/**
	 * Copies data from memory to double array.
	 *
	 * @param mem Source memory
	 * @param sOffset Source memory offset
	 * @param out Destination array
	 * @param oOffset Destination array offset
	 * @param length Number of elements to copy
	 * @throws IllegalArgumentException if source offset is negative
	 * @throws ArrayIndexOutOfBoundsException if bounds are exceeded
	 */
	@Override
	public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
		JVMMemory src = (JVMMemory) mem;

		if (sOffset < 0) {
			throw new IllegalArgumentException();
		}

		for (int i = 0; i < length; i++) {
			if ((sOffset + i) >= src.data.length) {
				throw new ArrayIndexOutOfBoundsException();
			} else if ((oOffset + i) >= out.length) {
				throw new ArrayIndexOutOfBoundsException();
			}

			out[oOffset + i] = src.data[sOffset + i];
		}
	}

	/**
	 * Destroys the provider and all allocated memory.
	 *
	 * <p>Currently not implemented - memory is garbage collected automatically.</p>
	 */
	@Override
	public void destroy() {
		// TODO  Destroy all JVMMemory
	}
}
