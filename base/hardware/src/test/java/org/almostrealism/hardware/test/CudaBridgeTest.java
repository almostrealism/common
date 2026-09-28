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

import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.cuda.CUContext;
import org.almostrealism.hardware.cuda.CUDevice;
import org.almostrealism.hardware.cuda.CUDeviceBuffer;
import org.almostrealism.hardware.cuda.CUFunction;
import org.almostrealism.hardware.cuda.CUModule;
import org.almostrealism.hardware.cuda.CUStream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.function.Function;

/**
 * Verifies the CUDA JNI bridge ({@code CU} and its typed wrappers) against a real device
 * using hand-written kernels, independently of code generation.
 *
 * <p>The kernels follow the parameter convention of generated CUDA kernels: every argument
 * pointer, then every argument offset, then every argument size, then {@code global_count}
 * and {@code global_offset}, with each thread computing its own {@code global_id} and
 * returning when it falls outside the requested range. {@link #parameterPacking()} pins
 * that convention down, since {@code CUFunction.launch} and the generated signature must
 * agree on it exactly.</p>
 *
 * <p>Like the other tests in this module, this does not extend {@code TestSuiteBase},
 * which lives in the engine layer above this module. Every test is skipped when no CUDA
 * driver or device is available.</p>
 */
public class CudaBridgeTest {
	/** Kernel prologue computing {@code global_id} and returning outside the requested range. */
	private static final String GLOBAL_ID =
			"long global_id = (long) blockIdx.x * blockDim.x + threadIdx.x + global_offset;\n" +
			"if (global_id >= global_offset + global_count) return;\n";

	/** Kernel computing {@code y = 2x + y} over two offset arguments. */
	private static final String SAXPY =
			"extern \"C\" __global__ void saxpy(float* x, float* y, int xOffset, int yOffset, " +
			"int xSize, int ySize, long global_count, long global_offset) {\n" +
			GLOBAL_ID +
			"y[yOffset + global_id] = 2.0f * x[xOffset + global_id] + y[yOffset + global_id];\n" +
			"}\n";

	/** Kernel recording every scalar parameter it receives into its first argument. */
	private static final String PACKING =
			"extern \"C\" __global__ void packing(float* a, float* b, float* c, " +
			"int aOffset, int bOffset, int cOffset, int aSize, int bSize, int cSize, " +
			"long global_count, long global_offset) {\n" +
			GLOBAL_ID +
			"a[aOffset + 0] = aOffset; a[aOffset + 1] = bOffset; a[aOffset + 2] = cOffset;\n" +
			"a[aOffset + 3] = aSize; a[aOffset + 4] = bSize; a[aOffset + 5] = cSize;\n" +
			"a[aOffset + 6] = global_count; a[aOffset + 7] = global_offset;\n" +
			"b[bOffset] = 1.0f;\n" +
			"c[cOffset] = 2.0f;\n" +
			"}\n";

	/** Kernel writing 1 to every element within the requested range. */
	private static final String MARK =
			"extern \"C\" __global__ void mark(float* y, int yOffset, int ySize, " +
			"long global_count, long global_offset) {\n" +
			GLOBAL_ID +
			"y[yOffset + global_id] = 1.0f;\n" +
			"}\n";

	/** The device under test. */
	private CUDevice device;

	/** The device's retained primary context. */
	private CUContext context;

	/** The stream every kernel in a test is launched on. */
	private CUStream stream;

	/** Skips the test unless the CUDA bridge loads and a device is present. */
	@Before
	public void setUp() {
		try {
			Assume.assumeTrue("No CUDA device", CUDevice.getDeviceCount() > 0);
		} catch (LinkageError | HardwareException e) {
			Assume.assumeNoException("CUDA is unavailable", e);
		}

		device = CUDevice.get(0);
		context = device.retainPrimaryContext();
		stream = context.newStream();
	}

	/** Releases the stream and context retained by {@link #setUp()}. */
	@After
	public void tearDown() {
		if (stream != null) stream.release();
		if (context != null) context.release();
	}

	/** The device reports a name, a compute capability and launch limits. */
	@Test(timeout = 60000)
	public void deviceAttributes() {
		Assert.assertFalse(device.getName().isEmpty());
		Assert.assertTrue(device.getComputeCapabilityMajor() > 0);
		Assert.assertTrue(device.getMaxThreadsPerBlock() >= 256);
		Assert.assertTrue(device.getMaxGridDimX() > 65535);
		Assert.assertTrue(device.getTotalMemory() > 0);
	}

	/** A kernel compiled with NVRTC computes the expected values in device memory. */
	@Test(timeout = 60000)
	public void saxpyDeviceMemory() {
		assertSaxpy(context::allocate);
	}

	/** A kernel compiled with NVRTC computes the expected values in managed memory. */
	@Test(timeout = 60000)
	public void saxpyManagedMemory() {
		assertSaxpy(context::allocateManaged);
	}

	/**
	 * The kernel receives pointers, offsets, sizes, {@code global_count} and
	 * {@code global_offset} exactly as {@code CUFunction.launch} was given them.
	 */
	@Test(timeout = 60000)
	public void parameterPacking() {
		CUModule module = context.loadModule(device.compile(PACKING, "packing.cu"));
		CUDeviceBuffer a = context.allocate(16 * Float.BYTES);
		CUDeviceBuffer b = context.allocate(16 * Float.BYTES);
		CUDeviceBuffer c = context.allocate(16 * Float.BYTES);

		try {
			CUFunction packing = module.getFunction("packing");
			packing.launch(stream, 1, 64, new CUDeviceBuffer[] { a, b, c },
					new int[] { 1, 2, 3 }, new int[] { 8, 5, 6 }, 1, 0);
			stream.synchronize();

			Assert.assertArrayEquals(new float[] { 1, 2, 3, 8, 5, 6, 1, 0 }, read(a, 1, 8), 0.0f);
			Assert.assertEquals(1.0f, read(b, 2, 1)[0], 0.0f);
			Assert.assertEquals(2.0f, read(c, 3, 1)[0], 0.0f);
		} finally {
			a.release();
			b.release();
			c.release();
			module.release();
		}
	}

	/**
	 * Only the work items in {@code [global_offset, global_offset + global_count)} run,
	 * even though the block is larger than the requested range.
	 */
	@Test(timeout = 60000)
	public void boundsGuard() {
		CUModule module = context.loadModule(device.compile(MARK, "mark.cu"));
		CUDeviceBuffer y = context.allocate(64 * Float.BYTES);

		try {
			module.getFunction("mark").launch(stream, 1, 64, new CUDeviceBuffer[] { y },
					new int[] { 0 }, new int[] { 64 }, 3, 2);
			stream.synchronize();

			Assert.assertArrayEquals(new float[] { 0, 0, 1, 1, 1, 0, 0, 0 }, read(y, 0, 8), 0.0f);
		} finally {
			y.release();
			module.release();
		}
	}

	/** A compile error is reported as a {@link HardwareException} carrying the NVRTC log. */
	@Test(timeout = 60000)
	public void compileErrorIncludesLog() {
		try {
			device.compile("extern \"C\" __global__ void broken(float* x) { undeclared = 1; }", "broken.cu");
			Assert.fail("Compilation of invalid source should fail");
		} catch (HardwareException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("undeclared"));
		}
	}

	/** Runs {@code y = 2x + y} over offset sub-ranges of two allocations from {@code allocator}. */
	private void assertSaxpy(Function<Long, CUDeviceBuffer> allocator) {
		int n = 1000;
		CUModule module = context.loadModule(device.compile(SAXPY, "saxpy.cu"));
		CUDeviceBuffer x = allocator.apply((long) (n + 4) * Float.BYTES);
		CUDeviceBuffer y = allocator.apply((long) (n + 7) * Float.BYTES);

		try {
			float[] xs = new float[n + 4];
			float[] ys = new float[n + 7];
			for (int i = 0; i < n; i++) {
				xs[i + 4] = i;
				ys[i + 7] = 3 * i;
			}

			x.setContents(xs, 0, 0, xs.length);
			y.setContents(ys, 0, 0, ys.length);

			CUFunction saxpy = module.getFunction("saxpy");
			int block = Math.min(256, saxpy.getMaxThreadsPerBlock());
			saxpy.launch(stream, (n + block - 1) / block, block, new CUDeviceBuffer[] { x, y },
					new int[] { 4, 7 }, new int[] { n, n }, n, 0);
			stream.synchronize();

			float[] result = read(y, 0, n + 7);
			for (int i = 0; i < 7; i++) {
				Assert.assertEquals("Element before the offset was modified", 0.0f, result[i], 0.0f);
			}

			for (int i = 0; i < n; i++) {
				Assert.assertEquals(5.0f * i, result[i + 7], 0.0f);
			}
		} finally {
			x.release();
			y.release();
			module.release();
		}
	}

	/** Reads {@code count} floats from {@code buffer}, starting at element {@code offset}. */
	private static float[] read(CUDeviceBuffer buffer, int offset, int count) {
		float[] values = new float[count];
		buffer.getContents(values, 0, offset, count);
		return values;
	}
}
