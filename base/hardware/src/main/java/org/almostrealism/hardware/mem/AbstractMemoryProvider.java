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

/**
 * Base class for the {@link MemoryProvider}s of the hardware module, holding the
 * implementation they share for copying from a source memory region they cannot
 * transfer from directly.
 *
 * <p>The chunked copy is internal to the providers' implementations of
 * {@link #setMem(Memory, int, Memory, int, int)}; it is not part of the general
 * {@link MemoryProvider} contract, and so is not exposed publicly.</p>
 *
 * @param <T> the type of memory this provider manages
 * @see HardwareMemoryProvider
 */
public abstract class AbstractMemoryProvider<T extends Memory> implements MemoryProvider<T> {
	/**
	 * The largest number of elements {@link #setMemInChunks(Memory, int, Memory, int, int)}
	 * reads from its source, and writes to its destination, at one time.
	 *
	 * <p>At this size the temporary {@code double[]} is 2 MB, and the per-chunk conversion
	 * a provider performs for FP32 storage adds 1 MB of heap and 1 MB of direct memory,
	 * independent of the length of the copy. Copying 16M elements from heap memory into
	 * Metal (FP32) and native (FP64) memory, this size was as fast as or faster than both
	 * a single transfer of the whole range and chunks four times larger, while chunks of
	 * 64K elements or fewer were slower into Metal memory.</p>
	 */
	protected static final int HOST_COPY_CHUNK_SIZE = 1 << 18;

	/**
	 * Copies data from a source memory region that this provider cannot transfer from
	 * directly, reading it with {@link Memory#get(int, double[], int, int)} in chunks of
	 * at most {@link #HOST_COPY_CHUNK_SIZE} elements.
	 *
	 * @param mem the destination memory region
	 * @param offset the starting position in the destination
	 * @param source the source memory region
	 * @param srcOffset the starting position in the source
	 * @param length the number of elements to copy
	 * @see #setMemInChunks(Memory, int, Memory, int, int, int)
	 */
	protected void setMemInChunks(T mem, int offset, Memory source, int srcOffset, int length) {
		setMemInChunks(mem, offset, source, srcOffset, length, HOST_COPY_CHUNK_SIZE);
	}

	/**
	 * Copies data from a source memory region that this provider cannot transfer from
	 * directly, reading it with {@link Memory#get(int, double[], int, int)} in chunks of
	 * at most {@code chunkSize} elements and writing each chunk with
	 * {@link #setMem(Memory, int, double[], int, int)}.
	 *
	 * <p>A single {@code double[]} of at most {@code chunkSize} elements is reused for
	 * every chunk, so the temporary memory this copy needs, and whatever the destination
	 * provider allocates to convert one write, is bounded by the chunk size rather than
	 * growing with {@code length}. A copy no longer than one chunk is one read and one
	 * write of the whole range.</p>
	 *
	 * <p>A copy within one memory region whose destination starts inside the source range
	 * is made from the last chunk to the first, so every chunk is read before any write
	 * reaches it and the result is the same as copying a snapshot of the whole source.
	 * Source and destination are one memory region when they are {@link Object#equals equal},
	 * so two distinct handles to the same allocation are recognized as one region.</p>
	 *
	 * <p>No monitor is held across the read of a chunk: a provider that serializes its
	 * own writes takes its monitor inside {@link #setMem(Memory, int, double[], int, int)}
	 * for each chunk, so two providers copying into each other cannot deadlock here.</p>
	 *
	 * @param mem the destination memory region
	 * @param offset the starting position in the destination
	 * @param source the source memory region
	 * @param srcOffset the starting position in the source
	 * @param length the number of elements to copy
	 * @param chunkSize the largest number of elements to read and write at once
	 * @throws IllegalArgumentException if {@code length} is negative or
	 *                                  {@code chunkSize} is not positive
	 */
	protected void setMemInChunks(T mem, int offset, Memory source, int srcOffset, int length, int chunkSize) {
		if (length < 0)
			throw new IllegalArgumentException("Negative copy length " + length);
		if (chunkSize <= 0)
			throw new IllegalArgumentException("Chunk size must be positive, not " + chunkSize);

		double[] chunk = new double[Math.min(length, chunkSize)];

		int n;
		if (source.equals(mem) && offset > srcOffset && offset - srcOffset < length) {
			for (int end = length; end > 0; end -= n) {
				n = Math.min(chunk.length, end);
				source.get(srcOffset + end - n, chunk, 0, n);
				setMem(mem, offset + end - n, chunk, 0, n);
			}
		} else {
			for (int pos = 0; pos < length; pos += n) {
				n = Math.min(chunk.length, length - pos);
				source.get(srcOffset + pos, chunk, 0, n);
				setMem(mem, offset + pos, chunk, 0, n);
			}
		}
	}
}
