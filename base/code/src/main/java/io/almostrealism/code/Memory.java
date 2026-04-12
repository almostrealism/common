/*
 * Copyright 2021 Michael Murray
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

import java.nio.ByteBuffer;

/**
 * Represents a handle to an allocated memory region, potentially residing on a GPU or other device.
 *
 * <p>A {@code Memory} instance is an opaque reference to memory managed by a {@link MemoryProvider}.
 * It does not hold data directly; instead, it delegates all data access operations to its provider.
 * This abstraction enables uniform treatment of CPU, GPU, and other memory backends.</p>
 *
 * @see MemoryProvider
 * @see DataContext
 */
public interface Memory {
	/**
	 * Returns the {@link MemoryProvider} that manages this memory region.
	 *
	 * @return the memory provider
	 */
	MemoryProvider getProvider();

	/**
	 * Writes the given double values into this memory region starting at offset 0.
	 *
	 * @param values the values to write
	 */
	default void set(double... values) {
		set(0, values);
	}

	/**
	 * Writes the given double values into this memory region starting at the specified offset.
	 *
	 * @param offset the starting position in this memory region
	 * @param values the values to write
	 */
	default void set(int offset, double... values) {
		getProvider().setMem(this, offset, values, 0, values.length);
	}

	/**
	 * Reads {@code length} double values from this memory region starting at offset 0.
	 *
	 * @param length the number of values to read
	 * @return an array of the read values
	 */
	default double[] toArray(int length) {
		return toArray(0, length);
	}

	/**
	 * Reads {@code length} double values from this memory region starting at the specified offset.
	 *
	 * @param offset the starting position in this memory region
	 * @param length the number of values to read
	 * @return an array of the read values
	 */
	default double[] toArray(int offset, int length) {
		return getProvider().toArray(this, offset, length);
	}

	/**
	 * Returns the raw bytes for the first {@code length} double values in this memory region.
	 *
	 * @param length the number of double values to read
	 * @return a {@link ByteBuffer} containing the raw bytes
	 */
	default ByteBuffer getBytes(int length) {
		return getBytes(0, length);
	}

	/**
	 * Returns the raw bytes for {@code length} double values starting at the given offset.
	 *
	 * @param offset the starting position in this memory region
	 * @param length the number of double values to read
	 * @return a {@link ByteBuffer} containing the raw bytes
	 */
	default ByteBuffer getBytes(int offset, int length) {
		double data[] = toArray(offset, length);
		ByteBuffer buf = ByteBuffer.allocate(data.length * 8);
		for (double d : data) buf.putDouble(d);
		return buf;
	}
}
