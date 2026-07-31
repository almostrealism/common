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

package org.almostrealism.persist.assets;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.protobuf.Collections;

/**
 * Read-only {@link MemoryProvider} that treats protobuf
 * {@link Collections.CollectionData} messages as their own kind of device:
 * reads are served element-by-element from the message, at whichever
 * precision it was encoded, and data reaches the compute device only when a
 * kernel first requires it. Weights that only the host ever reads never
 * occupy device memory at all.
 *
 * <p>This is a source provider: memory is created only from existing messages
 * via {@link #allocate(Collections.CollectionData)}, and the
 * {@link MemoryProvider} defaults reject empty allocation and writes —
 * migration to a device is one-way, so a write into message-backed memory
 * could only be silently lost.</p>
 *
 * @see CollectionDataMemory
 * @see CollectionEncoder#decode(Collections.CollectionData, boolean)
 */
public class CollectionDataMemoryProvider implements MemoryProvider<Memory> {
	/** The shared provider instance. */
	private static final CollectionDataMemoryProvider instance = new CollectionDataMemoryProvider();

	/**
	 * Returns the shared provider instance.
	 */
	public static CollectionDataMemoryProvider getInstance() { return instance; }

	/**
	 * Returns the provider name for identification.
	 *
	 * @return "PROTOBUF"
	 */
	@Override
	public String getName() { return "PROTOBUF"; }

	/**
	 * Returns the size of each number in bytes, as served to readers.
	 *
	 * @return 8 (values are read as FP64 regardless of the message's encoded precision)
	 */
	@Override
	public int getNumberSize() { return 8; }

	/**
	 * Creates memory backed by the given protobuf message.
	 *
	 * @param data the message serving as the backing store
	 * @return read-only memory over the message contents
	 */
	public Memory allocate(Collections.CollectionData data) {
		return new CollectionDataMemory(this, data);
	}

	/**
	 * Releases the message reference held by the given memory.
	 *
	 * @param size the number of elements originally wrapped (ignored)
	 * @param mem the memory to release
	 */
	@Override
	public void deallocate(int size, Memory mem) {
		((CollectionDataMemory) mem).destroy();
	}

	/**
	 * Reads elements from the backing message, converting to double as needed.
	 *
	 * @param mem the source memory region
	 * @param sOffset the starting position in the source memory
	 * @param out the destination double array
	 * @param oOffset the starting position in the output array
	 * @param length the number of elements to read
	 */
	@Override
	public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
		CollectionDataMemory src = (CollectionDataMemory) mem;
		for (int i = 0; i < length; i++) {
			out[oOffset + i] = src.valueAt(sOffset + i);
		}
	}
}
