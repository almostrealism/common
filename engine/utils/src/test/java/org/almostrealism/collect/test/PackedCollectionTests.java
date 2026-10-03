/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.test;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Tests for PackedCollection and its operations like transpose and clear.
 */
public class PackedCollectionTests extends TestSuiteBase {

	/**
	 * Tests that transpose correctly transposes a 10x4 collection.
	 */
	@Test(timeout = 10000)
	public void transpose() {
		PackedCollection data = new PackedCollection(shape(10, 4))
				.randFill();
		PackedCollection transposed = data.transpose();

		// Assert transposed dimensions
		assertEquals(4, transposed.getShape().length(0));
		assertEquals(10, transposed.getShape().length(1));

		// Assert transposed values
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 10; j++) {
				assertEquals(data.valueAt(j, i), transposed.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests that doubleStream over a permuted (irregular) shape yields elements in logical order,
	 * for the whole collection, for a range starting and ending mid-row, and for an empty range,
	 * and that every element agrees with {@link PackedCollection#toDouble(int)}.
	 */
	@Test(timeout = 10000)
	public void doubleStreamPermutedFollowsLogicalOrder() {
		// Memory is [3, 4] row-major; the permuted view is [4, 3], so logical (i, j) is memory (j, i).
		// rowMajor owns the backing allocation; permuted is only a view of it, so closing rowMajor
		// releases the memory even if an assertion fails partway through.
		try (PackedCollection rowMajor = pack(
				0.0, 1.0, 2.0, 3.0,
				10.0, 11.0, 12.0, 13.0,
				20.0, 21.0, 22.0, 23.0).reshape(3, 4)) {
			PackedCollection permuted = rowMajor.reshape(rowMajor.getShape().permute(1, 0));
			assertFalse(permuted.getShape().isRegular());

			double[] expected = {
					0.0, 10.0, 20.0,
					1.0, 11.0, 21.0,
					2.0, 12.0, 22.0,
					3.0, 13.0, 23.0};

			double[] all = permuted.doubleStream().toArray();
			assertEquals(expected.length, all.length);
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], all[i]);
				assertEquals(permuted.toDouble(i), all[i]);
			}

			double[] middle = permuted.doubleStream(4, 5).toArray();
			assertEquals(5, middle.length);
			for (int i = 0; i < middle.length; i++) {
				assertEquals(expected[4 + i], middle[i]);
			}

			double[] last = permuted.doubleStream(11, 1).toArray();
			assertEquals(1, last.length);
			assertEquals(23.0, last[0]);

			assertEquals(0, permuted.doubleStream(3, 0).count());
		}
	}

	/**
	 * Tests that doubleStream over a short logical range of a sparse permutation &mdash; where the
	 * requested elements map to backing indices far apart, so their covering span is many times the
	 * window &mdash; still yields the correct elements in logical order. Such a span exceeds the
	 * limit at which a single bulk transfer is worthwhile, so the stream reads the window element by
	 * element rather than allocating and transferring the whole covering span (which for this layout
	 * would pull the entire backing buffer to return two values). The full stream, whose span equals
	 * its length, still exercises the bulk-read path and must agree with {@link PackedCollection#toDouble(int)}.
	 */
	@Test(timeout = 10000)
	public void doubleStreamSparsePermutationBoundsBulkRead() {
		int cols = 64;
		int total = 2 * cols;

		// Backing [2, cols] row-major holding 0..total-1; permuted view [cols, 2] maps logical (i, j) to memory (j, i).
		try (PackedCollection rowMajor = integers(0, total).evaluate().reshape(2, cols)) {
			PackedCollection permuted = rowMajor.reshape(rowMajor.getShape().permute(1, 0));
			assertFalse(permuted.getShape().isRegular());

			// Logical indices 0 and 1 map to backing 0 and cols: covering span cols + 1 over a window of two.
			double expectedFirst = rowMajor.toDouble(0);
			double expectedSecond = rowMajor.toDouble(cols);
			double[] firstPair = permuted.doubleStream(0, 2).toArray();
			assertTrue("a two-element window must return two values", firstPair.length == total / cols);
			double firstValue = firstPair[0];
			double secondValue = firstPair[1];
			assertEquals(expectedFirst, firstValue);
			assertEquals(expectedSecond, secondValue);

			double[] all = permuted.doubleStream().toArray();
			assertEquals(total, all.length);
			for (int i = 0; i < all.length; i++) {
				assertEquals(permuted.toDouble(i), all[i]);
			}
		}
	}

	/**
	 * Tests that clear zeros out all elements in a collection.
	 */
	@Test(timeout = 10000)
	public void clear() {
		PackedCollection data = pack(1.0, 2.0, 3.0, 4.0);
		data.clear();
		assertEquals(0, data.toArray(0, 4)[1]);
	}

	/**
	 * Tests that load rejects an empty source list explicitly, rather than reaching the shape's
	 * divisibility check and dividing by zero.
	 */
	@Test(timeout = 10000)
	public void loadRejectsEmptySources() {
		try {
			PackedCollection.load(shape(4));
			throw new AssertionError("load with no sources must be rejected");
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	/**
	 * Tests that consecutive calls to load with the same source buffer each consume their own
	 * region of it, rather than every call after the first re-reading the values the first one
	 * read. This is the pattern {@code Llama2Weights} uses to read several tensors in sequence out
	 * of one checkpoint buffer, and it works because the transfer reads the source relatively,
	 * leaving it positioned past the values it consumed. Skipped on a provider addressing
	 * {@link Precision#FP16}, which load refuses outright.
	 */
	@Test(timeout = 10000)
	public void loadAdvancesTheSourcePosition() {
		MemoryProvider<? extends RAM> provider = Hardware.getLocalHardware().getNativeBufferMemoryProvider();
		if (provider.getNumberSize() == Precision.FP16.bytes()) return;

		int size = 4;
		ByteBuffer source = ByteBuffer.allocateDirect(Precision.FP32.bytes() * size * 2)
				.order(ByteOrder.nativeOrder());
		for (int i = 0; i < size * 2; i++) {
			source.putFloat(i * Precision.FP32.bytes(), i + 0.5f);
		}

		PackedCollection first = PackedCollection.load(shape(size), source);
		PackedCollection second = PackedCollection.load(shape(size), source);

		for (int i = 0; i < size; i++) {
			assertEquals(i + 0.5, first.toDouble(i));
			assertEquals(size + i + 0.5, second.toDouble(i));
		}
	}

	/**
	 * Tests that load rejects a provider that addresses values at {@link Precision#FP16}
	 * (bfloat16) before allocating a staging region, rather than allocating one and then failing
	 * inside {@link org.almostrealism.hardware.mem.ByteBufferTransfer}, which does not support that
	 * precision. This is skipped unless the local hardware's native buffer provider is actually
	 * configured for that precision.
	 */
	@Test(timeout = 10000)
	public void loadRejectsAnFp16Destination() {
		MemoryProvider<? extends RAM> provider = Hardware.getLocalHardware().getNativeBufferMemoryProvider();
		if (provider.getNumberSize() != Precision.FP16.bytes()) return;

		int size = 4;
		ByteBuffer source = ByteBuffer.allocateDirect(Precision.FP32.bytes() * size)
				.order(ByteOrder.nativeOrder());

		try {
			PackedCollection.load(shape(size), source);
			throw new AssertionError("load into an FP16 provider must be rejected");
		} catch (UnsupportedOperationException e) {
			// expected
		}
	}
}
