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

import java.nio.ByteBuffer;

/**
 * Read-only memory backed by values still in the file they were written to.
 *
 * <p>Nothing is parsed and nothing is held: the values are read from a mapping of the file, which
 * is to say from the operating system's page cache, which reclaims them under pressure without
 * asking. Values no kernel wants never reach a device, and values nothing reads never reach the
 * Java heap at all.</p>
 *
 * <p>This is what a file of values <em>is</em>, expressed as memory, and it is the route to prefer
 * whenever the values are in a file — including a file this process wrote moments earlier. Reading
 * such a file into a buffer and then copying that buffer into an allocation pays for the values
 * twice and pins them in memory the page cache could otherwise reclaim.</p>
 *
 * @see FileMapping
 * @see MappedMemoryProvider
 */
public class MappedMemory implements Memory {
	/** The provider that manages this memory. */
	private final MappedMemoryProvider provider;

	/** Width the values are stored at in the file. */
	private final Precision precision;

	/** Byte position of the first value within the file. */
	private final long valueOffset;

	/** Number of values available. */
	private final int count;

	/** The mapping the values are read through; {@code null} once destroyed. */
	private FileMapping mapping;

	/**
	 * Creates memory over values in a mapped file.
	 *
	 * @param provider    the managing provider
	 * @param mapping     the mapping to read them through, already retained for this instance
	 * @param precision   the width the values are stored at
	 * @param valueOffset the byte position of the first value
	 * @param count       the number of values
	 */
	protected MappedMemory(MappedMemoryProvider provider, FileMapping mapping,
						   Precision precision, long valueOffset, int count) {
		this.provider = provider;
		this.mapping = mapping;
		this.precision = precision;
		this.valueOffset = valueOffset;
		this.count = count;
	}

	@Override
	public MemoryProvider getProvider() { return provider; }

	/**
	 * Returns the number of values available.
	 *
	 * @return the value count
	 */
	public int getLength() { return count; }

	/**
	 * Returns the width the values are stored at, which is not necessarily the width they are
	 * served at — see {@link MappedMemoryProvider#getNumberSize()}.
	 *
	 * @return the stored precision
	 */
	public Precision getPrecision() { return precision; }

	/**
	 * Reads the value at the given position, widening to {@code double} whichever precision the
	 * file stores.
	 *
	 * @param index the value position
	 * @return the value at that position
	 * @throws IllegalStateException if this memory has been destroyed
	 * @throws IndexOutOfBoundsException if the position is outside the mapped values
	 */
	protected double valueAt(int index) {
		FileMapping current = mapping;

		if (current == null) {
			throw new IllegalStateException("Memory has been destroyed");
		}

		if (index < 0 || index >= count) {
			throw new IndexOutOfBoundsException("Index " + index + " outside 0.." + (count - 1));
		}

		ByteBuffer buffer = current.buffer();
		int at = (int) (valueOffset + (long) index * precision.bytes());

		return precision == Precision.FP64 ? buffer.getDouble(at) : buffer.getFloat(at);
	}

	/**
	 * Releases this memory's claim on the file. The mapping goes when nothing is reading it any
	 * more, and the file itself is untouched either way.
	 */
	protected void destroy() {
		FileMapping released = mapping;
		mapping = null;
		if (released != null) released.release();
	}
}
