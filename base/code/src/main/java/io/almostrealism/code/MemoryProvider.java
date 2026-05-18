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

package io.almostrealism.code;

import io.almostrealism.uml.Named;

import java.util.stream.IntStream;

/**
 * Manages allocation, deallocation, and data transfer for a specific type of {@link Memory}.
 *
 * <p>Concrete implementations of {@code MemoryProvider} back the memory model for a particular
 * device or compute backend (e.g., CPU heap, Metal buffer, OpenCL buffer). The provider is
 * responsible for allocating new memory regions and for reading and writing data to them.</p>
 *
 * @param <T> the concrete {@link Memory} type managed by this provider
 *
 * @see Memory
 * @see DataContext
 */
public interface MemoryProvider<T extends Memory> extends Named {
	/**
	 * The maximum number of elements that may be reserved, based on FP64 element size.
	 */
	int MAX_RESERVATION = Integer.MAX_VALUE / Precision.FP64.bytes();

	/**
	 * Returns the size in bytes of each element managed by this provider.
	 *
	 * @return the per-element byte count
	 */
	int getNumberSize();

	/**
	 * Allocates a new memory region capable of holding the specified number of elements.
	 *
	 * @param size the number of elements to allocate
	 * @return the newly allocated memory handle
	 */
	T allocate(int size);

	/**
	 * Releases the memory region previously allocated with the given size.
	 *
	 * @param size the number of elements originally allocated
	 * @param mem the memory handle to release
	 */
	void deallocate(int size, T mem);

	/**
	 * Allocates a new memory region and copies a sub-range from an existing memory region into it.
	 *
	 * @param mem the source memory region
	 * @param offset the starting position within the source
	 * @param length the number of elements to copy
	 * @return a new memory handle containing the copied data
	 */
	default T reallocate(Memory mem, int offset, int length) {
		T newMem = allocate(length);
		setMem(newMem, 0, mem, offset, length);
		return newMem;
	}

	/**
	 * Reads all {@code length} elements from the given memory into a new double array.
	 *
	 * @param mem the source memory region
	 * @param length the number of elements to read
	 * @return an array containing the read values
	 */
	default double[] toArray(T mem, int length) {
		return toArray(mem, 0, length);
	}

	/**
	 * Reads {@code length} elements from the given memory starting at the specified offset.
	 *
	 * @param mem the source memory region
	 * @param offset the starting position within the memory region
	 * @param length the number of elements to read
	 * @return an array containing the read values
	 */
	default double[] toArray(T mem, int offset, int length) {
		double d[] = new double[length];
		getMem(mem, offset, d, 0, length);
		return d;
	}

	/**
	 * Copies data from a source memory region into the destination memory region.
	 *
	 * @param mem the destination memory region
	 * @param offset the starting position in the destination
	 * @param source the source memory region
	 * @param srcOffset the starting position in the source
	 * @param length the number of elements to copy
	 */
	void setMem(T mem, int offset, Memory source, int srcOffset, int length);

	/**
	 * Copies data from a float array into the destination memory region, converting to double.
	 *
	 * @param mem the destination memory region
	 * @param offset the starting position in the destination
	 * @param source the source float array
	 * @param srcOffset the starting position in the source array
	 * @param length the number of elements to copy
	 */
	default void setMem(T mem, int offset, float[] source, int srcOffset, int length) {
		setMem(mem, offset, IntStream.range(0, source.length).mapToDouble(i -> source[i]).toArray(), srcOffset, length);
	}

	/**
	 * Copies data from a double array into the destination memory region.
	 *
	 * @param mem the destination memory region
	 * @param offset the starting position in the destination
	 * @param source the source double array
	 * @param srcOffset the starting position in the source array
	 * @param length the number of elements to copy
	 */
	void setMem(T mem, int offset, double[] source, int srcOffset, int length);

	/**
	 * Reads elements from the source memory region into a float array.
	 *
	 * @param mem the source memory region
	 * @param sOffset the starting position in the source memory
	 * @param out the destination float array
	 * @param oOffset the starting position in the output array
	 * @param length the number of elements to read
	 */
	default void getMem(T mem, int sOffset, float out[], int oOffset, int length) {
		double d[] = new double[length];
		getMem(mem, sOffset, d, 0, length);
		for (int i = 0; i < length; i++) {
			out[i] = (float) d[i];
		}
	}

	/**
	 * Reads elements from the source memory region into a double array.
	 *
	 * @param mem the source memory region
	 * @param sOffset the starting position in the source memory
	 * @param out the destination double array
	 * @param oOffset the starting position in the output array
	 * @param length the number of elements to read
	 */
	void getMem(T mem, int sOffset, double out[], int oOffset, int length);

	/**
	 * Releases all resources held by this memory provider.
	 */
	void destroy();
}
