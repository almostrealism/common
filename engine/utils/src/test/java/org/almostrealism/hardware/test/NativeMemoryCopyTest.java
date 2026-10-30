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

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.nio.NativeArrayCopy;
import org.almostrealism.nio.NativeRead;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Tests for copies between Java {@code double[]} arrays and JNI-allocated (calloc) native memory,
 * which {@link NativeMemoryProvider} performs through {@link NativeRead} and
 * {@link org.almostrealism.nio.NativeWrite} when it is not in direct-buffer mode, as on the
 * native CPU backend with {@code AR_HARDWARE_NATIVE_DIRECT_BUFFERS=disabled}.
 *
 * <p>The copies are made directly between the array and native memory. The provider uses the
 * precision of the running hardware, because the native read and write operations are bound
 * once per JVM. At FP32 the values are converted a fixed number at a time, so the length used
 * here spans several of those chunks and ends part of the way through one. Offsets on both
 * sides, an empty copy, and an array too short for the requested range are covered.</p>
 */
public class NativeMemoryCopyTest extends TestSuiteBase {
	/** A length spanning two full conversion chunks and part of a third. */
	private static final int LENGTH = 2 * NativeArrayCopy.CONVERSION_CHUNK + 37;

	/**
	 * A whole block written from an array reads back unchanged, whole and from an offset into
	 * an offset of a larger array, and an empty read leaves the array untouched.
	 */
	@Test(timeout = 60000)
	public void roundTrip() {
		NativeMemoryProvider provider = calloc();
		RAM mem = provider.allocate(LENGTH);

		try {
			double[] values = sequence(LENGTH, 0.5);
			provider.setMem(mem, 0, values, 0, LENGTH);
			Assert.assertArrayEquals(values, provider.toArray(mem, 0, LENGTH), 0.0);

			double[] out = filled(LENGTH + 10, -1.0);
			provider.getMem(mem, 5, out, 3, LENGTH - 10);
			for (int i = 0; i < out.length; i++) {
				double expected = i >= 3 && i < 3 + LENGTH - 10 ? values[i - 3 + 5] : -1.0;
				assertEquals("index " + i, expected, out[i]);
			}

			double[] untouched = filled(4, -1.0);
			provider.getMem(mem, 0, untouched, 0, 0);
			Assert.assertArrayEquals(filled(4, -1.0), untouched, 0.0);
		} finally {
			provider.deallocate(LENGTH, mem);
		}
	}

	/** A range of an array written into the middle of a block, and an empty range, change only the range written. */
	@Test(timeout = 60000)
	public void partialWrite() {
		NativeMemoryProvider provider = calloc();
		RAM mem = provider.allocate(LENGTH);

		try {
			provider.setMem(mem, 0, filled(LENGTH, -1.0), 0, LENGTH);

			int offset = 100;
			int sourceOffset = 7;
			int length = LENGTH - 200;
			double[] source = sequence(LENGTH, 0.25);
			provider.setMem(mem, offset, source, sourceOffset, length);
			provider.setMem(mem, 0, source, 0, 0);

			double[] result = provider.toArray(mem, 0, LENGTH);
			for (int i = 0; i < LENGTH; i++) {
				double expected = i >= offset && i < offset + length ? source[i - offset + sourceOffset] : -1.0;
				assertEquals("index " + i, expected, result[i]);
			}
		} finally {
			provider.deallocate(LENGTH, mem);
		}
	}

	/** A write from, or a read into, an array too short for the requested range is rejected rather than running past its end. */
	@Test(timeout = 60000)
	public void arrayTooShortIsRejected() {
		NativeMemoryProvider provider = calloc();
		RAM mem = provider.allocate(LENGTH);

		try {
			double[] shortArray = new double[LENGTH - 1];

			try {
				provider.setMem(mem, 0, shortArray, 0, LENGTH);
				Assert.fail("A write reading past the end of the source array was not rejected");
			} catch (IllegalArgumentException expected) {
				// expected
			}

			try {
				provider.getMem(mem, 0, shortArray, 0, LENGTH);
				Assert.fail("A read writing past the end of the destination array was not rejected");
			} catch (IllegalArgumentException expected) {
				// expected
			}
		} finally {
			provider.deallocate(LENGTH, mem);
		}
	}

	/**
	 * Returns a provider that allocates with JNI calloc rather than direct buffers, at the
	 * precision of the running hardware.
	 *
	 * @return the provider
	 */
	private static NativeMemoryProvider calloc() {
		return new NativeMemoryProvider(Hardware.getLocalHardware().getPrecision(),
				1024 * 1024, false, null, false);
	}

	/**
	 * Returns {@code i + fraction} for each index; every value is exactly representable in FP32.
	 *
	 * @param length   the number of values
	 * @param fraction the fractional part of each value
	 * @return the values
	 */
	private static double[] sequence(int length, double fraction) {
		double[] values = new double[length];
		for (int i = 0; i < length; i++) values[i] = i + fraction;
		return values;
	}

	/**
	 * Returns an array holding {@code value} in every element.
	 *
	 * @param length the number of values
	 * @param value  the value
	 * @return the array
	 */
	private static double[] filled(int length, double value) {
		double[] values = new double[length];
		Arrays.fill(values, value);
		return values;
	}
}
