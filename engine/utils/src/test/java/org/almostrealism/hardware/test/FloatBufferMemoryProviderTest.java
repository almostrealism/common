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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.FloatBufferMemory;
import org.almostrealism.hardware.mem.FloatBufferMemoryProvider;
import org.almostrealism.hardware.mem.ProvidedBytes;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.FloatBuffer;

/**
 * Tests for the NIO buffer memory provider: collections rooted over a
 * {@link FloatBufferMemory} read from the buffer on the host, compute
 * correctly as kernel arguments, and reject writes while buffer-backed.
 */
public class FloatBufferMemoryProviderTest extends TestSuiteBase {

	/** Number of elements in the test buffers. */
	private static final int SIZE = 96;

	/** The value at each position of the test buffer. */
	private static double valueAt(int index) { return 0.5 * index + 2.0; }

	/**
	 * Builds a collection rooted over buffer-backed memory whose values
	 * follow {@link #valueAt}.
	 */
	private PackedCollection bufferBacked() {
		FloatBuffer buffer = FloatBuffer.allocate(SIZE);
		for (int i = 0; i < SIZE; i++) {
			buffer.put(i, (float) valueAt(i));
		}

		FloatBufferMemory mem = (FloatBufferMemory)
				FloatBufferMemoryProvider.getInstance().allocate(buffer);
		return new PackedCollection(shape(SIZE), 0,
				new ProvidedBytes(mem, mem.getLength()), 0);
	}

	/**
	 * Host reads from a buffer-backed collection must match the buffer contents.
	 */
	@Test(timeout = 60000)
	public void bufferHostReadsMatchSource() {
		PackedCollection backed = bufferBacked();

		Assert.assertEquals("NIO",
				backed.getRootDelegate().getMem().getProvider().getName());

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), backed.toDouble(i), 1e-6);
		}
	}

	/**
	 * A kernel consuming a buffer-backed collection computes correctly through
	 * the per-dispatch aggregation path while the collection stays
	 * buffer-backed, so host-mostly results never permanently occupy device
	 * memory.
	 */
	@Test(timeout = 120000)
	public void bufferBackedComputesAsKernelArgument() {
		PackedCollection backed = bufferBacked();

		PackedCollection doubled = cp(backed).multiply(2.0).evaluate();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					2.0 * valueAt(i), doubled.toDouble(i), 1e-6);
		}

		Assert.assertEquals("small arguments aggregate per dispatch rather than migrating",
				"NIO", backed.getRootDelegate().getMem().getProvider().getName());
	}

	/**
	 * Buffer-backed memory is a read-only source: writes are rejected rather
	 * than silently lost, since migration is one-way.
	 */
	@Test(timeout = 60000)
	public void bufferBackedRejectsWrites() {
		PackedCollection backed = bufferBacked();

		try {
			backed.setMem(0, 1.0);
			Assert.fail("Write into buffer-backed memory should be rejected");
		} catch (UnsupportedOperationException e) {
			// Expected: the provider is a read-only source
		}
	}
}
