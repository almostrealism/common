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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.CLMemory;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the whole-buffer host read cache of {@link CLMemory}, which serves per-element
 * read loops from one snapshot instead of one blocking OpenCL transfer per element.
 *
 * <p>The cache must be invalidated by writes to its own buffer and by nothing else. A write to
 * an unrelated buffer (a kernel dispatched against other memory, possibly by another thread)
 * that discarded the snapshot would make a long read loop recapture the whole buffer over and
 * over, which is slower than not caching at all.</p>
 *
 * <p>Each test skips cleanly when no OpenCL backend is available.</p>
 *
 * @see CLMemory#snapshotForRead(int)
 */
public class CLHostReadCacheTest extends TestSuiteBase {

	/** Number of elements in each test buffer. */
	private static final int SIZE = 4096;

	/**
	 * Captures a snapshot of one buffer, invalidates a different buffer as a kernel dispatch
	 * against it would, and verifies the first buffer's snapshot is still served.
	 */
	@Test(timeout = 120000)
	public void unrelatedWriteKeepsSnapshot() {
		CLMemoryProvider provider = clProvider();
		if (provider == null) {
			log("skipping, OpenCL memory provider unavailable");
			return;
		}

		CLMemory read = provider.allocate(SIZE);
		CLMemory other = provider.allocate(SIZE);

		try {
			provider.setMem(read, 0, sequence(SIZE), 0, SIZE);

			Assert.assertNull(read.snapshotForRead(1));
			double[] snapshot = read.snapshotForRead(1);
			assertNotNull(snapshot);

			other.invalidateHostCache();

			Assert.assertSame(snapshot, read.snapshotForRead(1));
			assertEquals(SIZE - 1.0, provider.toArray(read, SIZE - 1, 1)[0]);
		} finally {
			provider.deallocate(SIZE, other);
			provider.deallocate(SIZE, read);
		}
	}

	/**
	 * Captures a snapshot of a buffer, writes new contents into it on the host, and verifies
	 * that subsequent element reads observe the new contents rather than the stale snapshot.
	 */
	@Test(timeout = 120000)
	public void ownWriteDiscardsSnapshot() {
		CLMemoryProvider provider = clProvider();
		if (provider == null) {
			log("skipping, OpenCL memory provider unavailable");
			return;
		}

		CLMemory mem = provider.allocate(SIZE);

		try {
			provider.setMem(mem, 0, sequence(SIZE), 0, SIZE);
			provider.toArray(mem, 10, 1);
			assertEquals(11.0, provider.toArray(mem, 11, 1)[0]);
			assertNotNull(mem.snapshotForRead(1));

			double[] replacement = new double[SIZE];
			for (int i = 0; i < SIZE; i++) replacement[i] = -i;
			provider.setMem(mem, 0, replacement, 0, SIZE);

			for (int i = 0; i < 16; i++) {
				assertEquals("index " + i, -i, provider.toArray(mem, i, 1)[0]);
			}
		} finally {
			provider.deallocate(SIZE, mem);
		}
	}

	/**
	 * Captures a snapshot of a buffer and invalidates the buffer itself, as a kernel dispatch
	 * that takes it as an argument does, and verifies the snapshot is no longer served.
	 */
	@Test(timeout = 120000)
	public void dispatchInvalidationDiscardsSnapshot() {
		CLMemoryProvider provider = clProvider();
		if (provider == null) {
			log("skipping, OpenCL memory provider unavailable");
			return;
		}

		CLMemory mem = provider.allocate(SIZE);

		try {
			provider.setMem(mem, 0, sequence(SIZE), 0, SIZE);
			mem.snapshotForRead(1);
			assertNotNull(mem.snapshotForRead(1));

			mem.invalidateHostCache();

			Assert.assertNull(mem.snapshotForRead(1));
		} finally {
			provider.deallocate(SIZE, mem);
		}
	}

	/** Returns {@code n} values counting up from 0. */
	private static double[] sequence(int n) {
		double[] values = new double[n];
		for (int i = 0; i < n; i++) values[i] = i;
		return values;
	}

	/**
	 * Returns the OpenCL memory provider of the active hardware configuration, or null when
	 * no OpenCL backend is available.
	 */
	private static CLMemoryProvider clProvider() {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, ComputeRequirement.CL).stream()
					.flatMap(c -> c.getDataContext().getMemoryProviders().stream())
					.filter(CLMemoryProvider.class::isInstance)
					.map(CLMemoryProvider.class::cast)
					.findFirst().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
