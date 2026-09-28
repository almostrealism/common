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

package org.almostrealism.hardware.cuda;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;

/**
 * Host-side validation that {@link CUContext} and {@link CUDeviceBuffer} perform before crossing
 * into JNI, where sizes and offsets are reinterpreted as {@code size_t} and used for raw pointer
 * arithmetic. Each case checked here throws before any {@link CU} native method is reached, so no
 * CUDA device is required and the {@code CU} library is never loaded: these tests run on every
 * host, like the other hardware lifecycle tests in this module.
 *
 * <p>The construction of a {@link CUContext} or {@link CUDeviceBuffer} touches no native state
 * (the wrapper only stores its handle), so a wrapper over a placeholder handle is enough to drive
 * the guards.</p>
 */
public class CudaMemorySafetyTest {
	/** Fails unless {@code action} throws an instance of {@code expected}. */
	private static void assertRejects(Class<? extends Throwable> expected, Runnable action) {
		try {
			action.run();
			Assert.fail("Expected " + expected.getSimpleName() + " to be thrown");
		} catch (Throwable actual) {
			Assert.assertTrue("Expected " + expected.getSimpleName() + " but got " +
					actual.getClass().getSimpleName() + ": " + actual.getMessage(),
					expected.isInstance(actual));
		}
	}

	/**
	 * A negative allocation size would be reinterpreted as a near-maximum {@code size_t} by the
	 * driver, so it is rejected before reaching {@code cuMemAlloc}/{@code cuMemAllocManaged}.
	 */
	@Test(timeout = 30000)
	public void allocateRejectsNegativeSize() {
		CUContext context = new CUContext(null, 0L);
		assertRejects(IllegalArgumentException.class, () -> context.allocate(-1));
		assertRejects(IllegalArgumentException.class, () -> context.allocateManaged(-8));
		assertRejects(IllegalArgumentException.class, () -> context.allocate(Long.MIN_VALUE));
	}

	/**
	 * A range whose {@code offset + bytes} overflows to a negative value (here {@code bytes} is
	 * {@link Long#MAX_VALUE}) must still be rejected: the overflow-safe check compares
	 * {@code offset > capacity - bytes} rather than {@code offset + bytes > capacity}. Both the
	 * host-buffer offset and the allocation offset are non-zero so that, without the fix, both
	 * checks would overflow and the transfer would reach JNI rather than being rejected here.
	 */
	@Test(timeout = 30000)
	public void hostTransferRejectsOverflowingRange() {
		CUDeviceBuffer buffer = new CUDeviceBuffer(null, 0L, 1024L, true);
		ByteBuffer host = ByteBuffer.allocateDirect(16);

		assertRejects(IndexOutOfBoundsException.class, () -> buffer.write(host, 8, 8, Long.MAX_VALUE));
		assertRejects(IndexOutOfBoundsException.class, () -> buffer.read(host, 8, 8, Long.MAX_VALUE));
	}

	/**
	 * A host range that simply runs past the direct buffer's capacity, or begins before it, is
	 * rejected before the JNI copy reads the buffer's raw address.
	 */
	@Test(timeout = 30000)
	public void hostTransferRejectsRangePastCapacity() {
		CUDeviceBuffer buffer = new CUDeviceBuffer(null, 0L, 1024L, true);
		ByteBuffer host = ByteBuffer.allocateDirect(16);

		assertRejects(IndexOutOfBoundsException.class, () -> buffer.write(host, 0, 0, 17));
		assertRejects(IndexOutOfBoundsException.class, () -> buffer.write(host, 12, 0, 8));
		assertRejects(IndexOutOfBoundsException.class, () -> buffer.write(host, -1, 0, 4));
		assertRejects(IndexOutOfBoundsException.class, () -> buffer.read(host, 0, 0, 17));
	}

	/**
	 * The same overflow-safe bound guards the allocation range, exercised here through the
	 * device-to-device copy, whose source and destination ranges are both validated.
	 */
	@Test(timeout = 30000)
	public void deviceCopyRejectsOverflowingRange() {
		CUDeviceBuffer destination = new CUDeviceBuffer(null, 0L, 1024L, false);
		CUDeviceBuffer source = new CUDeviceBuffer(null, 0L, 1024L, false);

		assertRejects(IndexOutOfBoundsException.class, () -> destination.copyFrom(source, 8, 8, Long.MAX_VALUE));
		assertRejects(IndexOutOfBoundsException.class, () -> destination.copyFrom(source, 0, 0, 2048));
		assertRejects(IndexOutOfBoundsException.class, () -> destination.copyFrom(source, -1, 0, 4));
	}
}
