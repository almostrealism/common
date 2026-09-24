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
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;

import java.io.File;
import java.nio.ByteOrder;

/**
 * Read-only {@link MemoryProvider} that treats a file of values as its own kind of device: reads
 * are served from a mapping of the file, at whichever width the file stores, and the values reach a
 * compute device only when a kernel first requires them. Values that only the host ever reads never
 * occupy device memory at all, and values nothing reads are never touched.
 *
 * <p>This is a source provider: memory is created only from files that already hold values, via
 * {@link #allocate(File, Precision, long, int)}, and the {@link MemoryProvider} defaults reject
 * empty allocation and writes — migration to a device is one-way, so a write into file-backed
 * memory could only be silently lost. A file being <em>produced</em> rather than read is not this
 * provider's business.</p>
 *
 * @see MappedMemory
 * @see FileMapping
 */
public class MappedMemoryProvider implements MemoryProvider<Memory> {
	/**
	 * Byte order values in a mapped file are read in.
	 *
	 * <p>Fixed rather than taken from the machine, which is what lets a file written anywhere be
	 * read anywhere; it matches the order the extraction and serialization tooling writes.</p>
	 */
	public static final ByteOrder VALUE_ORDER = ByteOrder.LITTLE_ENDIAN;

	/** The shared provider instance. */
	private static final MappedMemoryProvider instance = new MappedMemoryProvider();

	/**
	 * Returns the shared provider instance.
	 *
	 * @return the provider
	 */
	public static MappedMemoryProvider getInstance() { return instance; }

	/**
	 * Returns the provider name for identification.
	 *
	 * @return "MAPPED"
	 */
	@Override
	public String getName() { return "MAPPED"; }

	/**
	 * Reports that this memory can only be read.
	 *
	 * <p>The backing store is a file as it was written, not a place to put results, so
	 * {@link #setMem} is left unimplemented and this says so in advance — a caller that would
	 * arrange a write finds out while it is deciding rather than when the write runs.</p>
	 *
	 * @return true
	 */
	@Override
	public boolean isReadOnly() { return true; }

	/**
	 * Returns the size of each number in bytes, as served to readers.
	 *
	 * @return 8 (values are served as FP64 whatever width the file stores them at)
	 */
	@Override
	public int getNumberSize() { return 8; }

	/**
	 * Creates memory over values held in the given file.
	 *
	 * <p>The file is mapped, not read: only where the values are was worked out, and they are read
	 * from the mapping when something reads them. Every reader of one file shares one mapping, so
	 * many tensors in one file cost one mapping between them.</p>
	 *
	 * @param file        the file holding the values
	 * @param precision   the width the values are stored at
	 * @param valueOffset the byte position of the first value, past any header
	 * @param count       the number of values
	 * @return read-only memory over those values
	 * @throws IllegalArgumentException if the file does not hold {@code count} values at that offset
	 */
	public Memory allocate(File file, Precision precision, long valueOffset, int count) {
		long required = valueOffset + (long) count * precision.bytes();

		if (count < 0 || valueOffset < 0 || file.length() < required) {
			throw new IllegalArgumentException(file + " holds " + file.length() +
					" bytes, short of the " + required + " needed for " + count +
					" values of " + precision + " at offset " + valueOffset);
		}

		return new MappedMemory(this, FileMapping.of(file, VALUE_ORDER),
				precision, valueOffset, count);
	}

	/**
	 * Releases the given memory's claim on the file it reads through.
	 *
	 * @param size the number of values originally exposed (ignored)
	 * @param mem  the memory to release
	 */
	@Override
	public void deallocate(int size, Memory mem) {
		((MappedMemory) mem).destroy();
	}

	/**
	 * Reads values from the mapped file, widening to double as needed.
	 *
	 * @param mem     the source memory region
	 * @param sOffset the starting position in the source memory
	 * @param out     the destination double array
	 * @param oOffset the starting position in the output array
	 * @param length  the number of values to read
	 */
	@Override
	public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
		MappedMemory src = (MappedMemory) mem;
		for (int i = 0; i < length; i++) {
			out[oOffset + i] = src.valueAt(sOffset + i);
		}
	}
}
