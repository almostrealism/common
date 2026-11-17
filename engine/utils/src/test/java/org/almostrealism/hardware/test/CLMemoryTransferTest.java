/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.test;

import org.almostrealism.hardware.cl.CLMemory;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.function.Consumer;

/**
 * Tests for the host-array transfers of {@link CLMemoryProvider}: writing a {@code float[]} or
 * {@code double[]} into a buffer and reading a buffer back into either array type, with
 * non-zero offsets on both the buffer side and the array side.
 *
 * <p>Every overload must honor every offset regardless of whether the array type matches the
 * precision of the OpenCL context (a direct transfer) or not (a converting transfer).</p>
 *
 * <p>Each test skips cleanly when no OpenCL backend is available.</p>
 */
public class CLMemoryTransferTest extends TestSuiteBase {

	/** Number of elements in each test buffer. */
	private static final int SIZE = 64;

	/** Offset into the host array used by every transfer. */
	private static final int ARRAY_OFFSET = 5;

	/** Offset into the device buffer used by every transfer. */
	private static final int BUFFER_OFFSET = 7;

	/** Number of elements moved by every transfer. */
	private static final int LENGTH = 20;

	/**
	 * Runs {@code test} against every available {@link CLMemoryProvider}, so a configuration
	 * that exposes both an FP32 and an FP64 context exercises the direct and the converting
	 * transfer paths of each overload rather than only the first provider's precision. Skips
	 * cleanly when no OpenCL backend is available.
	 *
	 * @param test the per-provider check to run
	 */
	private void forEachProvider(Consumer<CLMemoryProvider> test) {
		List<CLMemoryProvider> providers = CLHostReadCacheTest.clProviders();
		if (providers.isEmpty()) {
			log("skipping, OpenCL memory provider unavailable");
			return;
		}

		for (CLMemoryProvider provider : providers) {
			test.accept(provider);
		}
	}

	/**
	 * Writes part of a {@code float[]} starting at a non-zero source offset and verifies the
	 * buffer holds the elements from that offset, at the requested buffer offset.
	 */
	@Test(timeout = 120000)
	public void floatWriteHonorsSourceOffset() {
		forEachProvider(this::floatWriteHonorsSourceOffset);
	}

	/**
	 * Runs {@link #floatWriteHonorsSourceOffset()} against a single {@code provider}.
	 *
	 * @param provider the OpenCL memory provider under test
	 */
	private void floatWriteHonorsSourceOffset(CLMemoryProvider provider) {
		CLMemory mem = provider.allocate(SIZE);

		try {
			provider.setMem(mem, 0, new double[SIZE], 0, SIZE);

			float[] source = new float[SIZE];
			for (int i = 0; i < SIZE; i++) source[i] = 100 + i;
			provider.setMem(mem, BUFFER_OFFSET, source, ARRAY_OFFSET, LENGTH);

			assertWritten(provider, mem);
		} finally {
			provider.deallocate(SIZE, mem);
		}
	}

	/**
	 * Writes part of a {@code double[]} starting at a non-zero source offset and verifies the
	 * buffer holds the elements from that offset, at the requested buffer offset.
	 */
	@Test(timeout = 120000)
	public void doubleWriteHonorsSourceOffset() {
		forEachProvider(this::doubleWriteHonorsSourceOffset);
	}

	/**
	 * Runs {@link #doubleWriteHonorsSourceOffset()} against a single {@code provider}.
	 *
	 * @param provider the OpenCL memory provider under test
	 */
	private void doubleWriteHonorsSourceOffset(CLMemoryProvider provider) {
		CLMemory mem = provider.allocate(SIZE);

		try {
			provider.setMem(mem, 0, new double[SIZE], 0, SIZE);

			double[] source = new double[SIZE];
			for (int i = 0; i < SIZE; i++) source[i] = 100 + i;
			provider.setMem(mem, BUFFER_OFFSET, source, ARRAY_OFFSET, LENGTH);

			assertWritten(provider, mem);
		} finally {
			provider.deallocate(SIZE, mem);
		}
	}

	/**
	 * Reads part of a buffer into a {@code float[]} and a {@code double[]} at a non-zero
	 * destination offset and verifies only the requested range of each array is filled, with
	 * the elements from the requested buffer offset.
	 */
	@Test(timeout = 120000)
	public void readsHonorBothOffsets() {
		forEachProvider(this::readsHonorBothOffsets);
	}

	/**
	 * Runs {@link #readsHonorBothOffsets()} against a single {@code provider}.
	 *
	 * @param provider the OpenCL memory provider under test
	 */
	private void readsHonorBothOffsets(CLMemoryProvider provider) {
		CLMemory mem = provider.allocate(SIZE);

		try {
			double[] contents = new double[SIZE];
			for (int i = 0; i < SIZE; i++) contents[i] = 100 + i;
			provider.setMem(mem, 0, contents, 0, SIZE);

			float[] floats = new float[SIZE];
			provider.getMem(mem, BUFFER_OFFSET, floats, ARRAY_OFFSET, LENGTH);

			double[] doubles = new double[SIZE];
			provider.getMem(mem, BUFFER_OFFSET, doubles, ARRAY_OFFSET, LENGTH);

			for (int i = 0; i < SIZE; i++) {
				boolean inRange = i >= ARRAY_OFFSET && i < ARRAY_OFFSET + LENGTH;
				double expected = inRange ? 100 + BUFFER_OFFSET + (i - ARRAY_OFFSET) : 0.0;
				assertEquals("float index " + i, expected, floats[i]);
				assertEquals("double index " + i, expected, doubles[i]);
			}
		} finally {
			provider.deallocate(SIZE, mem);
		}
	}

	/**
	 * Verifies that the buffer holds {@code 100 + ARRAY_OFFSET + k} at {@code BUFFER_OFFSET + k}
	 * for each of the {@link #LENGTH} written elements, and zero everywhere else.
	 */
	private void assertWritten(CLMemoryProvider provider, CLMemory mem) {
		double[] result = provider.toArray(mem, 0, SIZE);
		for (int i = 0; i < SIZE; i++) {
			boolean inRange = i >= BUFFER_OFFSET && i < BUFFER_OFFSET + LENGTH;
			double expected = inRange ? 100 + ARRAY_OFFSET + (i - BUFFER_OFFSET) : 0.0;
			assertEquals("index " + i, expected, result[i]);
		}
	}
}
