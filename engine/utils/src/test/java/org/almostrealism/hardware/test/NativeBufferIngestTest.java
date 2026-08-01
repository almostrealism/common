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

package org.almostrealism.hardware.test;

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.DirectMemory;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

/**
 * Tests for the standard ByteBuffer ingest sequence: a native buffer staging
 * allocation is populated through a view of its ByteBuffer, wrapped as root
 * memory data, and served to the framework — with the data moving to a compute
 * device only when a kernel first requires it. No host arrays are involved at
 * any point.
 */
public class NativeBufferIngestTest extends TestSuiteBase {

	/** Number of elements in the test buffers. */
	private static final int SIZE = 96;

	/** The value at each position of the staged buffer. */
	private static double valueAt(int index) { return 0.5 * index + 2.0; }

	/**
	 * Stages values through a ByteBuffer view of a native buffer allocation
	 * and returns a collection rooted over the staging memory.
	 */
	private PackedCollection staged() {
		MemoryProvider<? extends RAM> provider =
				Hardware.getLocalHardware().getNativeBufferMemoryProvider();
		RAM mem = provider.allocate(SIZE);

		ByteBuffer staging = ((DirectMemory) mem).asByteBuffer();
		if (provider.getNumberSize() == 4) {
			FloatBuffer view = staging.asFloatBuffer();
			for (int i = 0; i < SIZE; i++) {
				view.put(i, (float) valueAt(i));
			}
		} else {
			DoubleBuffer view = staging.asDoubleBuffer();
			for (int i = 0; i < SIZE; i++) {
				view.put(i, valueAt(i));
			}
		}

		return new PackedCollection(shape(SIZE), 0, Bytes.of(mem, SIZE), 0);
	}

	/**
	 * Host reads from a staged collection must match what was written through
	 * the buffer view.
	 */
	@Test(timeout = 60000)
	public void stagedHostReadsMatchSource() {
		PackedCollection staged = staged();

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), staged.toDouble(i), 1e-6);
		}
	}

	/**
	 * A kernel consuming a staged collection computes correctly, with the
	 * framework moving the data off the staging buffer when the target device
	 * is discovered.
	 */
	@Test(timeout = 120000)
	public void stagedComputesAsKernelArgument() {
		PackedCollection staged = staged();

		PackedCollection doubled = cp(staged).multiply(2.0).evaluate();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					2.0 * valueAt(i), doubled.toDouble(i), 1e-6);
		}

		// The staged collection remains readable and consistent afterward
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i + " after kernel use",
					valueAt(i), staged.toDouble(i), 1e-6);
		}
	}
}
