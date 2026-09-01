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

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.mem.FileMapping;

import java.nio.ByteBuffer;

/**
 * Read-only memory backed by collection data still in the file it was written
 * to.
 *
 * <p>Nothing is parsed and nothing is held: the values are read from a mapping
 * of the file, which is to say from the operating system's page cache, which
 * reclaims them under pressure without asking. Data no kernel wants never
 * reaches a device, and data nothing reads never reaches the Java heap at
 * all.</p>
 *
 * <p>The file remains an ordinary protobuf asset. Reading it this way is an
 * additional way to read it, not a different format — anything able to read
 * protobuf still reads the same file.</p>
 *
 * @see CollectionDataReference
 * @see CollectionDataMemoryProvider
 */
public class MappedCollectionDataMemory extends CollectionDataMemory {
	/** Where the values are, and what they are. */
	private final CollectionDataReference reference;

	/** The mapping the values are read through; {@code null} once destroyed. */
	private FileMapping mapping;

	/**
	 * Creates memory over the values the given reference locates.
	 *
	 * @param provider  the managing provider
	 * @param reference where the values are
	 * @param mapping   the mapping to read them through
	 */
	protected MappedCollectionDataMemory(CollectionDataMemoryProvider provider,
										 CollectionDataReference reference,
										 FileMapping mapping) {
		super(provider);
		this.reference = reference;
		this.mapping = mapping;
	}

	/** Returns where the values are, and what they are. */
	public CollectionDataReference getReference() { return reference; }

	@Override
	public int getLength() { return reference.getCount(); }

	@Override
	protected double valueAt(int index) {
		FileMapping current = mapping;

		if (current == null) {
			throw new IllegalStateException("Memory has been destroyed");
		}

		if (index < 0 || index >= reference.getCount()) {
			throw new IndexOutOfBoundsException("Index " + index +
					" outside 0.." + (reference.getCount() - 1));
		}

		ByteBuffer buffer = current.buffer();
		int at = (int) (reference.getValueOffset()
				+ (long) index * reference.getPrecision().bytes());

		return reference.getPrecision() == Precision.FP64 ?
				buffer.getDouble(at) : buffer.getFloat(at);
	}

	/**
	 * Releases this memory's claim on the file. The mapping goes when nothing
	 * is reading it any more.
	 */
	@Override
	protected void destroy() {
		FileMapping released = mapping;
		mapping = null;
		if (released != null) released.release();
	}
}
