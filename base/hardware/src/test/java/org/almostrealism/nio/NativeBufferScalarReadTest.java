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

package org.almostrealism.nio;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.mem.RAM;
import org.junit.Assert;
import org.junit.Test;

/**
 * The zero-allocation scalar read must return exactly what the array-mediated read returns.
 *
 * <p>{@link NativeBuffer#toDouble(int)} reads a single element straight from its host-addressable
 * {@link java.nio.DoubleBuffer} or {@link java.nio.FloatBuffer} rather than through the
 * one-element array that {@link Memory#toArray(int, int)} allocates; this verifies that the two
 * agree for every element, for both precisions. The default {@link Memory#toDouble(int)} is
 * covered against a minimal memory and provider so that its delegation to {@code toArray} is
 * locked independently of hardware.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that class
 * lives in the engine layer, which sits above this module, so depending on it would invert the
 * module graph. A private {@link NativeMemoryProvider} is allocated directly, which needs no real
 * GPU backend and no native compilation — a private direct buffer is an ordinary NIO direct
 * buffer.</p>
 */
public class NativeBufferScalarReadTest {

	/** A double buffer read one element at a time matches the array-mediated read and the written values. */
	@Test(timeout = 30000)
	public void doubleBufferScalarReadMatchesArrayRead() {
		NativeMemoryProvider provider = new NativeMemoryProvider(Precision.FP64, 1024L * 1024, false, null, true);
		try {
			double[] values = { 0.0, 1.5, -2.25, 3.0e9, Math.PI, -0.0, 1234.5 };
			RAM mem = provider.allocate(values.length);
			provider.setMem(mem, 0, values, 0, values.length);

			for (int i = 0; i < values.length; i++) {
				double viaArray = provider.toArray(mem, i, 1)[0];
				Assert.assertEquals("NativeBuffer.toDouble must match the written value at index " + i,
						values[i], mem.toDouble(i), 0.0);
				Assert.assertEquals("NativeBuffer.toDouble must match the array-mediated read at index " + i,
						viaArray, mem.toDouble(i), 0.0);
			}
		} finally {
			provider.destroy();
		}
	}

	/** A float buffer read one element at a time matches the array-mediated read, including the narrowing to float. */
	@Test(timeout = 30000)
	public void floatBufferScalarReadMatchesArrayRead() {
		NativeMemoryProvider provider = new NativeMemoryProvider(Precision.FP32, 1024L * 1024, false, null, true);
		try {
			// All exactly representable as float, so the FP32 round-trip is lossless and the
			// scalar read can be compared for exact equality.
			double[] values = { 0.0, 1.5, -2.25, 42.0, 256.0, -0.5 };
			RAM mem = provider.allocate(values.length);
			provider.setMem(mem, 0, values, 0, values.length);

			for (int i = 0; i < values.length; i++) {
				double viaArray = provider.toArray(mem, i, 1)[0];
				Assert.assertEquals("NativeBuffer.toDouble must match the written value at index " + i,
						values[i], mem.toDouble(i), 0.0);
				Assert.assertEquals("NativeBuffer.toDouble must match the array-mediated read at index " + i,
						viaArray, mem.toDouble(i), 0.0);
			}
		} finally {
			provider.destroy();
		}
	}

	/** A copy between two same-precision buffers moves exactly the requested range through {@link NativeBuffer#copyFrom}. */
	@Test(timeout = 30000)
	public void sameProviderCopyMovesRequestedRange() {
		NativeMemoryProvider provider = new NativeMemoryProvider(Precision.FP64, 1024L * 1024, false, null, true);
		try {
			double[] values = { 1.0, 2.0, 3.0, 4.0, 5.0 };
			RAM source = provider.allocate(values.length);
			RAM target = provider.allocate(values.length);
			provider.setMem(source, 0, values, 0, values.length);
			provider.setMem(target, 0, new double[values.length], 0, values.length);

			provider.setMem(target, 1, source, 2, 3);

			double[] expected = { 0.0, 3.0, 4.0, 5.0, 0.0 };
			Assert.assertArrayEquals(expected, provider.toArray(target, 0, values.length), 0.0);
		} finally {
			provider.destroy();
		}
	}

	/** The default {@link Memory#toDouble(int)} reads the requested element through {@code toArray}. */
	@Test(timeout = 30000)
	public void defaultScalarReadDelegatesToArrayRead() {
		double[] backing = { 10.0, 20.0, 30.0, 40.0 };
		ArrayMemoryProvider provider = new ArrayMemoryProvider(backing);
		ArrayMemory mem = new ArrayMemory(provider);

		for (int i = 0; i < backing.length; i++) {
			Assert.assertEquals("default toDouble must read the element at index " + i,
					backing[i], mem.toDouble(i), 0.0);
		}
	}

	/** Minimal memory that does not override {@code toDouble}, so the interface default is exercised. */
	private static final class ArrayMemory implements Memory {
		/** The provider that reads this memory's elements. */
		private final ArrayMemoryProvider provider;

		/**
		 * Creates a handle read by the given provider.
		 *
		 * @param provider the provider that owns this memory's data
		 */
		private ArrayMemory(ArrayMemoryProvider provider) {
			this.provider = provider;
		}

		@Override
		public MemoryProvider getProvider() {
			return provider;
		}
	}

	/** Minimal provider that serves {@link ArrayMemory} reads from a Java array. */
	private static final class ArrayMemoryProvider implements MemoryProvider<ArrayMemory> {
		/** The backing store this provider reads from. */
		private final double[] data;

		/**
		 * Creates a provider backed by the given array.
		 *
		 * @param data the elements this provider reads
		 */
		private ArrayMemoryProvider(double[] data) {
			this.data = data;
		}

		@Override
		public String getName() {
			return "array-memory-provider";
		}

		@Override
		public int getNumberSize() {
			return 8;
		}

		@Override
		public void getMem(ArrayMemory mem, int sOffset, double[] out, int oOffset, int length) {
			System.arraycopy(data, sOffset, out, oOffset, length);
		}

		@Override
		public void deallocate(int size, ArrayMemory mem) {
			// Nothing to release: the backing array is owned by the test.
		}
	}
}
