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
import org.almostrealism.io.SystemUtils;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

/**
 * Verifies that the staging provider returned by
 * {@link Hardware#getNativeBufferMemoryProvider()} honors
 * {@code AR_HARDWARE_NATIVE_DIRECT_BUFFERS}: direct NIO buffers when direct
 * buffers are enabled (the default), JNI malloc (calloc mode) when they are
 * disabled. Environments that disable direct buffers do so because staging
 * allocations must not count against the JVM's direct-memory limit, so a
 * provider that ignores the setting stalls every allocation once that limit
 * is reached.
 */
public class NativeBufferStagingModeTest extends TestSuiteBase {

	/** Number of elements in the staged buffer. */
	private static final int SIZE = 64;

	/** The value at each position of the staged buffer. */
	private static double valueAt(int index) { return 1.5 * index - 3.0; }

	/**
	 * The staging provider's allocation mode matches the configured
	 * direct-buffers setting, and the standard staging sequence works in
	 * that mode end to end: values written through the ByteBuffer view are
	 * visible to host reads from a collection rooted over the allocation.
	 */
	@Test(timeout = 60000)
	public void stagingModeMatchesConfiguration() {
		boolean directBuffers =
				SystemUtils.isEnabled("AR_HARDWARE_NATIVE_DIRECT_BUFFERS").orElse(true);

		// When the cross-backend shared-memory bridge is active, the provider is
		// always the direct bridge; the direct-buffers setting governs only the
		// lazily created staging provider
		boolean direct = Hardware.getLocalHardware().isNativeSharedMemory() || directBuffers;

		MemoryProvider<? extends RAM> provider =
				Hardware.getLocalHardware().getNativeBufferMemoryProvider();
		Assert.assertEquals(direct ? "NIO" : "JNI", provider.getName());

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

		PackedCollection staged =
				new PackedCollection(shape(SIZE), 0, Bytes.of(mem, SIZE), 0);
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), staged.toDouble(i), 1e-6);
		}
	}
}
