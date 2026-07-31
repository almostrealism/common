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
 * {@link Collections.CollectionData} messages as their own kind of device.
 *
 * <p>Collections backed by this provider hold no host arrays and no device
 * memory: reads are served element-by-element from the message, and the first
 * time a kernel needs the data the framework migrates the root reservation to
 * the compute provider (see {@code HardwareOperator.reassignMemory}), reading
 * through {@link #getMem} exactly once. Data that only the host ever reads
 * never reaches a device at all.</p>
 *
 * <p>This is a <em>source</em> provider: migration is one-way, so writes into
 * message-backed memory are rejected rather than silently lost. Nothing ever
 * writes back to the message.</p>
 *
 * @see CollectionDataMemory
 * @see CollectionEncoder#decodeDeferred(Collections.CollectionData)
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
	 * Unsupported: this provider only wraps existing messages via
	 * {@link #allocate(Collections.CollectionData)}; it cannot create
	 * empty regions.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public Memory allocate(int size) {
		throw new UnsupportedOperationException(
				"CollectionDataMemoryProvider is a read-only source; " +
						"memory is created from a CollectionData message");
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
	 * Unsupported: message-backed memory is read-only, and migration to a
	 * device is one-way, so a write here could only be silently lost.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public void setMem(Memory mem, int offset, Memory source, int srcOffset, int length) {
		throw new UnsupportedOperationException(
				"CollectionData backed memory is read-only");
	}

	/**
	 * Unsupported: message-backed memory is read-only, and migration to a
	 * device is one-way, so a write here could only be silently lost.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		throw new UnsupportedOperationException(
				"CollectionData backed memory is read-only");
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

	/**
	 * Releases all resources held by this memory provider.
	 *
	 * <p>Nothing to release: each memory's message reference is dropped by
	 * {@link #deallocate} when its owner is destroyed.</p>
	 */
	@Override
	public void destroy() { }
}
