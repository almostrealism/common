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
import org.almostrealism.hardware.mem.FileMapping;
import org.almostrealism.protobuf.Collections;

import java.io.File;
import java.nio.ByteOrder;

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
	/**
	 * Byte order protobuf writes packed {@code double} and {@code float} in.
	 *
	 * <p>Fixed by the wire format rather than by the machine, which is what
	 * lets a file written anywhere be read anywhere.</p>
	 */
	public static final ByteOrder VALUE_ORDER = ByteOrder.LITTLE_ENDIAN;

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
	 * Reports that this memory can only be read.
	 *
	 * <p>The backing store is a parsed message, which is the asset as it was
	 * written and not a place to put results. {@link #setMem} is therefore left
	 * unimplemented, and this says so in advance — a caller that would arrange
	 * a write finds out while it is deciding rather than when the write runs.</p>
	 *
	 * @return true
	 */
	@Override
	public boolean isReadOnly() { return true; }

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
		return new ParsedCollectionDataMemory(this, data);
	}

	/**
	 * Creates memory over collection data still in the file it was written to.
	 *
	 * <p>This is the form that costs nothing to hold. The message is never
	 * parsed: only where its values are was worked out, and they are read from
	 * a mapping of the file when something reads them. A tensor nothing uses
	 * occupies neither the Java heap nor a device.</p>
	 *
	 * @param reference where the values are, and what they are
	 * @param file      the file holding them
	 * @return read-only memory over those values
	 */
	public Memory allocate(CollectionDataReference reference, File file) {
		return new MappedCollectionDataMemory(this, reference,
				FileMapping.of(file, VALUE_ORDER));
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
