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
