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
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests that copying from a source memory a provider cannot transfer from directly is done
 * in chunks of at most {@link MemoryProvider#HOST_COPY_CHUNK_SIZE} elements, so the temporary
 * memory a copy needs does not grow with its length, and that every element still lands in
 * the right place with the value the whole-range copy produced.
 *
 * <p>The device tests use the kernel memory provider of whichever backend is selected by
 * {@code AR_HARDWARE_DRIVER}, so running this class under {@code mtl} and under
 * {@code native} covers the Metal and native providers.</p>
 */
public class ChunkedHostCopyTest extends TestSuiteBase {

	/** Offset into the source at which the copies start. */
	private static final int SOURCE_OFFSET = 7;

	/** Offset into the destination at which the copies start. */
	private static final int DESTINATION_OFFSET = 5;

	/** A length spanning two full chunks and a partial third. */
	private static final int LONG_LENGTH = 2 * MemoryProvider.HOST_COPY_CHUNK_SIZE + 12345;

	/** A length shorter than one chunk. */
	private static final int SHORT_LENGTH = 1000;

	/** The value every destination element outside the copied range is left holding. */
	private static final double UNTOUCHED = -1.0;

	/**
	 * A copy into device memory longer than one chunk is read from its source in chunks,
	 * none longer than the chunk size, and every element arrives at its offset unchanged
	 * apart from the conversion the provider's precision requires.
	 */
	@Test(timeout = 120000)
	public void deviceCopyLongerThanChunkIsReadInChunks() {
		CountingSource source = verifyCopy(kernelProvider(), LONG_LENGTH);
		Assert.assertEquals(3, source.reads.size());
		Assert.assertEquals(MemoryProvider.HOST_COPY_CHUNK_SIZE, source.maxRead());
	}

	/**
	 * A copy into device memory shorter than one chunk is still a single read of the whole
	 * range, as it was before copies were chunked.
	 */
	@Test(timeout = 60000)
	public void deviceCopyShorterThanChunkIsOneRead() {
		CountingSource source = verifyCopy(kernelProvider(), SHORT_LENGTH);
		Assert.assertEquals(List.of(SHORT_LENGTH), source.reads);
	}

	/**
	 * The JVM heap provider shares the same chunked copy.
	 */
	@Test(timeout = 60000)
	public void jvmCopyLongerThanChunkIsReadInChunks() {
		CountingSource source = verifyCopy(new JVMMemoryProvider(), LONG_LENGTH);
		Assert.assertEquals(3, source.reads.size());
	}

	/**
	 * An explicit chunk size that does not divide the length leaves a short final chunk,
	 * and the copy is still complete and in place.
	 */
	@Test(timeout = 60000)
	public void explicitChunkSizeWithRemainder() {
		MemoryProvider<Memory> provider = kernelProvider();
		int length = 10537;
		int chunk = 1000;

		CountingSource source = new CountingSource(SOURCE_OFFSET + length);
		int size = DESTINATION_OFFSET + length + 1;
		Memory dest = provider.allocate(size);

		try {
			prefill(provider, dest, size);
			provider.setMemInChunks(dest, DESTINATION_OFFSET, source, SOURCE_OFFSET, length, chunk);
			assertCopied(provider, dest, size, length);
		} finally {
			provider.deallocate(size, dest);
		}

		Assert.assertEquals(11, source.reads.size());
		Assert.assertEquals(chunk, source.maxRead());
		Assert.assertEquals(537, (int) source.reads.get(source.reads.size() - 1));
	}

	/**
	 * A copy of {@link Integer#MAX_VALUE} elements, whose final chunk is partial, stops after
	 * that chunk: advancing the position past it must not overflow into negative offsets and
	 * keep the copy going. Source and destination discard the values, so no memory of that
	 * length is allocated.
	 */
	@Test(timeout = 60000)
	public void maximumLengthCopyEndsAfterFinalPartialChunk() {
		int length = Integer.MAX_VALUE;
		int chunk = MemoryProvider.HOST_COPY_CHUNK_SIZE;

		DiscardingMemory source = new DiscardingMemory();
		DiscardingMemory dest = new DiscardingMemory();
		dest.setMemInChunks(dest, 0, source, 0, length);

		int chunks = length / chunk + 1;
		int last = length % chunk;
		Assert.assertNotEquals(0, last);

		Assert.assertEquals(chunks, source.calls);
		Assert.assertEquals(length, source.total);
		Assert.assertEquals((long) (chunks - 1) * chunk, source.lastOffset);
		Assert.assertEquals(last, source.lastLength);

		Assert.assertEquals(chunks, dest.calls);
		Assert.assertEquals(length, dest.total);
		Assert.assertEquals((long) (chunks - 1) * chunk, dest.lastOffset);
		Assert.assertEquals(last, dest.lastLength);
	}

	/**
	 * Copies {@code length} elements from a counting source into memory of the given
	 * provider, at non-zero offsets on both sides, and checks the reads and the result.
	 *
	 * @param provider the destination provider
	 * @param length   the number of elements to copy
	 * @return the source, for further checks on its reads
	 */
	private CountingSource verifyCopy(MemoryProvider<Memory> provider, int length) {
		log("provider=" + provider.getName() + " numberSize=" + provider.getNumberSize()
				+ " length=" + length);

		CountingSource source = new CountingSource(SOURCE_OFFSET + length);
		int size = DESTINATION_OFFSET + length + 1;
		Memory dest = provider.allocate(size);

		try {
			prefill(provider, dest, size);
			provider.setMem(dest, DESTINATION_OFFSET, source, SOURCE_OFFSET, length);
			assertCopied(provider, dest, size, length);
		} finally {
			provider.deallocate(size, dest);
		}

		Assert.assertTrue("a read of " + source.maxRead() + " elements exceeds the chunk size",
				source.maxRead() <= MemoryProvider.HOST_COPY_CHUNK_SIZE);
		Assert.assertEquals(length, source.reads.stream().mapToInt(Integer::intValue).sum());
		return source;
	}

	/** Fills the whole destination with {@link #UNTOUCHED}. */
	private static void prefill(MemoryProvider<Memory> provider, Memory dest, int size) {
		double[] fill = new double[size];
		Arrays.fill(fill, UNTOUCHED);
		provider.setMem(dest, 0, fill, 0, size);
	}

	/**
	 * Checks that the destination holds, in the copied range, exactly the values a single
	 * whole-range write of the source values through the same provider stores, and is
	 * untouched on either side of it. Deriving the expected values from the provider itself
	 * covers whatever conversion its precision (FP64, FP32 or FP16) performs.
	 */
	private static void assertCopied(MemoryProvider<Memory> provider, Memory dest, int size, int length) {
		double[] result = provider.toArray(dest, 0, size);
		double[] expected = wholeRangeCopy(provider, length);

		Assert.assertEquals(UNTOUCHED, result[DESTINATION_OFFSET - 1], 0.0);
		Assert.assertEquals(UNTOUCHED, result[DESTINATION_OFFSET + length], 0.0);

		for (int i = 0; i < length; i++) {
			if (result[DESTINATION_OFFSET + i] != expected[i]) {
				Assert.fail("element " + i + " expected " + expected[i]
						+ " but was " + result[DESTINATION_OFFSET + i]);
			}
		}
	}

	/**
	 * Writes the source values of the copied range to fresh memory of the given provider in
	 * one call and reads them back, giving the values an unchunked copy stores.
	 */
	private static double[] wholeRangeCopy(MemoryProvider<Memory> provider, int length) {
		double[] values = new double[length];
		for (int i = 0; i < length; i++) {
			values[i] = CountingSource.valueAt(SOURCE_OFFSET + i);
		}

		Memory reference = provider.allocate(length);
		try {
			provider.setMem(reference, 0, values, 0, length);
			return provider.toArray(reference, 0, length);
		} finally {
			provider.deallocate(length, reference);
		}
	}

	/** Returns the kernel memory provider of the selected backend. */
	private static MemoryProvider<Memory> kernelProvider() {
		return (MemoryProvider<Memory>) Hardware.getLocalHardware()
				.getDataContext().getKernelMemoryProvider();
	}

	/**
	 * Host memory whose values are a function of their index and whose provider records
	 * the length of every read made from it.
	 */
	private static class CountingSource implements Memory, MemoryProvider<Memory> {
		/** The number of elements this source holds. */
		private final int size;

		/** The length of each read, in order. */
		private final List<Integer> reads;

		/**
		 * Creates a source of the given number of elements.
		 *
		 * @param size the number of elements
		 */
		CountingSource(int size) {
			this.size = size;
			this.reads = new ArrayList<>();
		}

		/**
		 * Returns the value at the given index: distinct for every index in range, and not
		 * exactly representable at FP32, so a misplaced or reconverted element is noticed.
		 *
		 * @param index the element index
		 * @return the value
		 */
		static double valueAt(int index) {
			return 0.5 + index * 0.001;
		}

		/** Returns the longest read made so far. */
		int maxRead() {
			return reads.stream().mapToInt(Integer::intValue).max().orElse(0);
		}

		@Override
		public MemoryProvider getProvider() { return this; }

		@Override
		public String getName() { return "COUNTING"; }

		@Override
		public int getNumberSize() { return Double.BYTES; }

		@Override
		public void deallocate(int size, Memory mem) { }

		@Override
		public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
			if (sOffset < 0 || sOffset + length > size)
				throw new IndexOutOfBoundsException(sOffset + " + " + length + " > " + size);

			reads.add(length);
			for (int i = 0; i < length; i++) {
				out[oOffset + i] = valueAt(sOffset + i);
			}
		}
	}

	/**
	 * Memory of unbounded length that neither stores nor produces values, recording only
	 * the position and length of each read from it or write to it. Any read or write with
	 * a negative offset or a non-positive length fails.
	 */
	private static class DiscardingMemory implements Memory, MemoryProvider<Memory> {
		/** The number of reads or writes made. */
		private int calls;

		/** The total number of elements read or written. */
		private long total;

		/** The offset of the most recent read or write. */
		private long lastOffset;

		/** The length of the most recent read or write. */
		private int lastLength;

		/**
		 * Records one read or write, rejecting an invalid range.
		 *
		 * @param offset the starting position
		 * @param length the number of elements
		 */
		private void record(int offset, int length) {
			if (offset < 0 || length <= 0)
				throw new IndexOutOfBoundsException("offset " + offset + ", length " + length);

			calls++;
			total += length;
			lastOffset = offset;
			lastLength = length;
		}

		@Override
		public MemoryProvider getProvider() { return this; }

		@Override
		public String getName() { return "DISCARDING"; }

		@Override
		public int getNumberSize() { return Double.BYTES; }

		@Override
		public void deallocate(int size, Memory mem) { }

		@Override
		public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
			record(sOffset, length);
		}

		@Override
		public void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
			record(offset, length);
		}
	}
}
