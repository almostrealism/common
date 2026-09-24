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

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Tests for adopting an existing region as memory rather than copying it: a region the process
 * already holds backs a collection directly, and the ones that cannot be adopted are refused
 * rather than misread.
 *
 * @see MemoryProvider#wrap(ByteBuffer, int)
 */
public class NativeBufferWrapTest extends TestSuiteBase {

	/** Number of values in the test regions. */
	private static final int SIZE = 64;

	/** The value at each position. */
	private static double valueAt(int index) { return 0.25 * index - 3.0; }

	/** The provider under test. */
	private MemoryProvider<? extends RAM> provider() {
		return Hardware.getLocalHardware().getNativeBufferMemoryProvider();
	}

	/**
	 * A direct region of this provider's number size, filled with {@link #valueAt}, positioned at
	 * the first value.
	 */
	private ByteBuffer source() {
		MemoryProvider<? extends RAM> provider = provider();
		ByteBuffer buffer = ByteBuffer
				.allocateDirect(provider.getNumberSize() * SIZE)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < SIZE; i++) {
			if (provider.getNumberSize() == 4) {
				buffer.putFloat(i * 4, (float) valueAt(i));
			} else {
				buffer.putDouble(i * 8, valueAt(i));
			}
		}

		return buffer;
	}

	/**
	 * A collection of the given size over the adopted region.
	 *
	 * @param source the region to adopt, positioned at the first value
	 * @param size   the number of values to expose
	 * @return a collection backed by that region
	 */
	private PackedCollection wrapped(ByteBuffer source, int size) {
		return new PackedCollection(new TraversalPolicy(size), 0,
				Bytes.of(provider().wrap(source, size), size), 0);
	}

	/** Writes one value into a region of this provider's number size. */
	private void put(ByteBuffer buffer, int index, double value) {
		if (provider().getNumberSize() == 4) {
			buffer.putFloat(index * 4, (float) value);
		} else {
			buffer.putDouble(index * 8, value);
		}
	}

	/**
	 * A collection over an adopted region reads the values that are in that region.
	 */
	@Test(timeout = 60000)
	public void wrappedRegionReadsAsItsValues() {
		MemoryProvider<? extends RAM> provider = provider();
		ByteBuffer source = source();
		Assert.assertTrue(provider.canWrap(source, SIZE));

		PackedCollection wrapped = wrapped(source, SIZE);

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), wrapped.toDouble(i), 1e-5);
		}
	}

	/**
	 * The adopted region is the caller's own: a write through the source buffer is visible through
	 * the collection, which is what distinguishes adoption from a copy.
	 */
	@Test(timeout = 60000)
	public void wrappedRegionIsNotACopy() {
		ByteBuffer source = source();

		PackedCollection wrapped = wrapped(source, SIZE);
		Assert.assertEquals(valueAt(7), wrapped.toDouble(7), 1e-5);

		put(source, 7, 42.5);
		Assert.assertEquals(42.5, wrapped.toDouble(7), 1e-5);
	}

	/**
	 * A kernel over an adopted region computes from those values, so the region reaches the device
	 * the same way an allocated one does.
	 */
	@Test(timeout = 120000)
	public void wrappedRegionComputesAsKernelArgument() {
		PackedCollection wrapped = wrapped(source(), SIZE);

		PackedCollection doubled = cp(wrapped).multiply(2.0).evaluate();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, 2.0 * valueAt(i), doubled.toDouble(i), 1e-5);
		}
	}

	/**
	 * Regions that cannot be adopted as they stand are refused rather than misread: a heap buffer
	 * has no address to hand a kernel, a read-only region could be offered to one as a destination,
	 * a foreign byte order would be read as raw storage, and a short region has fewer values than
	 * were asked for.
	 */
	@Test(timeout = 60000)
	public void unsuitableRegionsAreRefused() {
		MemoryProvider<? extends RAM> provider = provider();

		Assert.assertFalse("heap buffer", provider.canWrap(
				ByteBuffer.allocate(provider.getNumberSize() * SIZE).order(ByteOrder.nativeOrder()), SIZE));
		Assert.assertFalse("read-only buffer", provider.canWrap(source().asReadOnlyBuffer(), SIZE));
		Assert.assertFalse("short buffer", provider.canWrap(source(), SIZE + 1));
		Assert.assertFalse("null buffer", provider.canWrap(null, SIZE));

		ByteOrder foreign = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ?
				ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
		Assert.assertFalse("foreign order", provider.canWrap(
				ByteBuffer.allocateDirect(provider.getNumberSize() * SIZE).order(foreign), SIZE));

		try {
			provider.wrap(ByteBuffer.allocate(provider.getNumberSize() * SIZE), SIZE);
			throw new AssertionError("a heap buffer must be refused");
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	/**
	 * A region positioned partway through a buffer is adopted from that position, so a value read
	 * out of a larger region does not have to be copied out of it first.
	 */
	@Test(timeout = 60000)
	public void wrapStartsAtThePosition() {
		MemoryProvider<? extends RAM> provider = provider();
		ByteBuffer source = source();
		source.position(provider.getNumberSize() * 4);

		PackedCollection wrapped = wrapped(source, SIZE - 4);

		for (int i = 0; i < SIZE - 4; i++) {
			Assert.assertEquals("element " + i, valueAt(i + 4), wrapped.toDouble(i), 1e-5);
		}
	}
}
