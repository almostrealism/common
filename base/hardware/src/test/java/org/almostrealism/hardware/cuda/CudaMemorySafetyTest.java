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

import io.almostrealism.code.Precision;
import org.almostrealism.io.PrintWriter;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Collections;

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

	/**
	 * The asynchronous stream copy validates both ranges exactly as the synchronous
	 * {@link CUDeviceBuffer#copyFrom} does, so it cannot be used to bypass those checks.
	 */
	@Test(timeout = 30000)
	public void streamCopyRejectsOutOfRange() {
		CUStream stream = new CUStream(null, 0L);
		CUDeviceBuffer source = new CUDeviceBuffer(null, 0L, 1024L, false);
		CUDeviceBuffer destination = new CUDeviceBuffer(null, 0L, 512L, false);

		assertRejects(IndexOutOfBoundsException.class, () -> stream.copy(source, 8, destination, 8, Long.MAX_VALUE));
		assertRejects(IndexOutOfBoundsException.class, () -> stream.copy(source, 0, destination, 0, 1024));
		assertRejects(IndexOutOfBoundsException.class, () -> stream.copy(source, 1000, destination, 0, 32));
		assertRejects(IndexOutOfBoundsException.class, () -> stream.copy(source, -1, destination, 0, 4));
		assertRejects(IndexOutOfBoundsException.class, () -> stream.copy(source, 0, destination, -4, 4));
		assertRejects(IndexOutOfBoundsException.class, () -> stream.copy(source, 0, destination, 0, -1));
	}

	/**
	 * A kernel argument whose element range does not lie within its buffer is rejected before
	 * the launch crosses JNI, including an offset whose byte position overflows {@code int}.
	 */
	@Test(timeout = 30000)
	public void launchRejectsArgumentOutsideBuffer() {
		CUFunction function = new CUFunction(new CUModule(null, 0L), 0L);
		CUStream stream = new CUStream(null, 0L);
		CUDeviceBuffer[] buffers = { new CUDeviceBuffer(null, 0L, 16L * Float.BYTES, false) };

		assertRejects(IndexOutOfBoundsException.class, () -> function.launch(stream, 1, 64, buffers,
				new int[] { 1 }, new int[] { 16 }, Float.BYTES, 16, 0));
		assertRejects(IndexOutOfBoundsException.class, () -> function.launch(stream, 1, 64, buffers,
				new int[] { -1 }, new int[] { 4 }, Float.BYTES, 4, 0));
		assertRejects(IndexOutOfBoundsException.class, () -> function.launch(stream, 1, 64, buffers,
				new int[] { 0 }, new int[] { -4 }, Float.BYTES, 4, 0));
		assertRejects(IndexOutOfBoundsException.class, () -> function.launch(stream, 1, 64, buffers,
				new int[] { Integer.MAX_VALUE }, new int[] { 1 }, Float.BYTES, 1, 0));
		assertRejects(IndexOutOfBoundsException.class, () -> function.launch(stream, 1, 64, buffers,
				new int[] { 0 }, new int[] { 9 }, Double.BYTES, 9, 0));
	}

	/**
	 * An invalid launch geometry, element size or argument array shape is rejected before the
	 * launch crosses JNI.
	 */
	@Test(timeout = 30000)
	public void launchRejectsInvalidGeometry() {
		CUFunction function = new CUFunction(new CUModule(null, 0L), 0L);
		CUStream stream = new CUStream(null, 0L);
		CUDeviceBuffer[] buffers = { new CUDeviceBuffer(null, 0L, 64L, false) };
		int[] offsets = { 0 };
		int[] sizes = { 4 };

		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 0, 64, buffers, offsets, sizes, 4, 4, 0));
		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, -1, buffers, offsets, sizes, 4, 4, 0));
		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, 64, buffers, offsets, sizes, 4, -1, 0));
		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, 64, buffers, offsets, sizes, 4, 4, -1));
		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, 64, buffers, offsets, sizes, 0, 4, 0));
		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, 64, buffers, new int[0], sizes, 4, 4, 0));
		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, 64, buffers, offsets, new int[2], 4, 4, 0));
	}

	/**
	 * The grid covers the work with the fewest blocks, and does not overflow to a negative grid
	 * (which would pass the device maximum-grid check) for a work size near {@link Long#MAX_VALUE}.
	 */
	@Test(timeout = 30000)
	public void gridSizeIsOverflowSafeCeiling() {
		Assert.assertEquals(0L, CUFunction.gridSize(0, 256));
		Assert.assertEquals(1L, CUFunction.gridSize(1, 256));
		Assert.assertEquals(1L, CUFunction.gridSize(256, 256));
		Assert.assertEquals(2L, CUFunction.gridSize(257, 256));
		Assert.assertEquals(4L, CUFunction.gridSize(1000, 256));
		Assert.assertEquals(Long.MAX_VALUE, CUFunction.gridSize(Long.MAX_VALUE, 1));
		Assert.assertEquals(Long.MAX_VALUE / 256 + 1, CUFunction.gridSize(Long.MAX_VALUE, 256));
		Assert.assertEquals(Long.MAX_VALUE / 1024 + 1, CUFunction.gridSize(Long.MAX_VALUE - 1, 1024));

		assertRejects(IllegalArgumentException.class, () -> CUFunction.gridSize(-1, 256));
		assertRejects(IllegalArgumentException.class, () -> CUFunction.gridSize(10, 0));
		assertRejects(IllegalArgumentException.class, () -> CUFunction.gridSize(10, -8));
	}

	/**
	 * A work range whose {@code globalOffset + globalCount} overflows {@code long} is rejected
	 * before the launch crosses JNI. The generated kernel bounds the thread with that sum, so an
	 * overflowing pair would wrap the bound negative and let every thread index out of range.
	 */
	@Test(timeout = 30000)
	public void launchRejectsWorkRangeOverflow() {
		CUFunction function = new CUFunction(new CUModule(null, 0L), 0L);
		CUStream stream = new CUStream(null, 0L);
		CUDeviceBuffer[] buffers = { new CUDeviceBuffer(null, 0L, 64L, false) };
		int[] offsets = { 0 };
		int[] sizes = { 4 };

		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, 64, buffers,
				offsets, sizes, 4, Long.MAX_VALUE, 1));
		assertRejects(IllegalArgumentException.class, () -> function.launch(stream, 1, 64, buffers,
				offsets, sizes, 4, 2, Long.MAX_VALUE - 1));
	}

	/**
	 * The bounds guard {@link CudaPrintWriter} emits must be overflow-safe: it compares
	 * {@code global_id - global_offset} against {@code global_count} rather than forming
	 * {@code global_offset + global_count}, which wraps negative for an offset near
	 * {@link Long#MAX_VALUE} and would let every launched thread pass the guard.
	 */
	@Test(timeout = 30000)
	public void generatedKernelGuardIsOverflowSafe() {
		StringBuilder generated = new StringBuilder();
		CudaPrintWriter writer = new CudaPrintWriter(
				PrintWriter.of(generated::append), "kernel", Precision.FP32);

		writer.renderArgumentReads(Collections.emptyList());

		String source = generated.toString();
		Assert.assertTrue("Expected a subtraction-based bound: " + source,
				source.contains("global_id - global_offset >= global_count"));
		Assert.assertTrue("Expected the lower-bound guard: " + source,
				source.contains("global_id < global_offset"));
		Assert.assertFalse("Guard must not form the overflowing sum: " + source,
				source.contains("global_offset + global_count"));
	}

	/**
	 * {@link CUObject} exposes its released state through {@link CUObject#isReleased()} and refuses
	 * to hand out the native handle once {@link CUObject#release()} has run. The flag is
	 * {@code volatile} so a release on one thread is visible to the others sharing the wrapper.
	 */
	@Test(timeout = 30000)
	public void releasedHandleIsRefused() {
		ReleasableStub object = new ReleasableStub(42L);

		Assert.assertFalse(object.isReleased());
		Assert.assertEquals(42L, object.getNativePointer());

		object.release();

		Assert.assertTrue(object.isReleased());
		assertRejects(IllegalStateException.class, object::getNativePointer);
	}

	/** A minimal {@link CUObject} whose {@link #release()} touches no native state. */
	private static final class ReleasableStub extends CUObject {
		/** Wraps the given placeholder handle in a context-less object. */
		private ReleasableStub(long nativePointer) {
			super(null, nativePointer);
		}
	}
}
