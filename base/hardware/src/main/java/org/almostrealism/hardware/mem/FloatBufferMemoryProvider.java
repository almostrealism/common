/*
 * Copyright 2026 Michael Murray
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

import java.nio.FloatBuffer;

/**
 * Read-only {@link SourceMemoryProvider} over java.nio {@link FloatBuffer}s,
 * so results handed over by external runtimes act as their own kind of device:
 * reads are served from the buffer, and data reaches the compute device only
 * when a kernel first requires it. Results that only the host ever reads
 * never occupy device memory at all.
 *
 * @see FloatBufferMemory
 */
public class FloatBufferMemoryProvider extends SourceMemoryProvider {
	/** The shared provider instance. */
	private static final FloatBufferMemoryProvider instance = new FloatBufferMemoryProvider();

	/**
	 * Returns the shared provider instance.
	 */
	public static FloatBufferMemoryProvider getInstance() { return instance; }

	/**
	 * Returns the provider name for identification.
	 *
	 * @return "NIO"
	 */
	@Override
	public String getName() { return "NIO"; }

	/**
	 * Creates memory backed by the given buffer.
	 *
	 * <p>The buffer must not be modified after it is handed over: the returned
	 * memory reads from it until the framework migrates the data to a device,
	 * and treats it as immutable throughout.</p>
	 *
	 * @param data the buffer serving as the backing store
	 * @return read-only memory over the buffer contents
	 */
	public Memory allocate(FloatBuffer data) {
		return new FloatBufferMemory(this, data);
	}

	/**
	 * Releases the buffer reference held by the given memory.
	 *
	 * @param size the number of elements originally wrapped (ignored)
	 * @param mem the memory to release
	 */
	@Override
	public void deallocate(int size, Memory mem) {
		((FloatBufferMemory) mem).destroy();
	}

	/**
	 * Reads elements from the backing buffer, converting to double.
	 *
	 * @param mem the source memory region
	 * @param sOffset the starting position in the source memory
	 * @param out the destination double array
	 * @param oOffset the starting position in the output array
	 * @param length the number of elements to read
	 */
	@Override
	public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
		FloatBufferMemory src = (FloatBufferMemory) mem;
		for (int i = 0; i < length; i++) {
			out[oOffset + i] = src.valueAt(sOffset + i);
		}
	}
}
