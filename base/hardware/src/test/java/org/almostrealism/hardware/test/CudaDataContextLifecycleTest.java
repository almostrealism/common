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
import org.almostrealism.hardware.ctx.AcceleratorDataContext;
import org.almostrealism.hardware.cuda.CudaDataContext;
import org.almostrealism.hardware.cuda.CudaMemory;
import org.almostrealism.hardware.cuda.CudaMemoryProvider;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link CudaDataContext} starts the device lazily and must fail fast once destroyed,
 * rather than retaining a CUDA context for a data context that has been torn down.
 *
 * <p>The destroyed-state tests need no CUDA device: {@code destroyed} is checked before the
 * deferred start ever runs, so they pass on every runner. {@link #memoryRoundTrip()} uses
 * the device, and is skipped through {@link Assume} when none is present.</p>
 *
 * <p>Like the other tests in this module, this does not extend {@code TestSuiteBase},
 * which lives in the engine layer above this module.</p>
 */
public class CudaDataContextLifecycleTest {
	/** Creates and initializes a {@link CudaDataContext} without triggering its lazy device start. */
	private CudaDataContext newContext() {
		CudaDataContext context = new CudaDataContext("test-cuda", 1024L * 1024, 1024);
		context.init();
		return context;
	}

	/** Asserts that {@code action} fails with an {@link IllegalStateException} naming destruction. */
	private void assertFailsAsDestroyed(Runnable action) {
		try {
			action.run();
			Assert.fail("The call should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/** A context that never started must not start the device after destroy(). */
	@Test(timeout = 30000)
	public void getDeviceFailsFastAfterDestroy() {
		CudaDataContext context = newContext();
		context.destroy();
		assertFailsAsDestroyed(context::getDevice);
	}

	/** A context that never started must not create a memory provider after destroy(). */
	@Test(timeout = 30000)
	public void getMemoryProviderFailsFastAfterDestroy() {
		CudaDataContext context = newContext();
		context.destroy();
		assertFailsAsDestroyed(context::getMemoryProvider);
	}

	/** A compute context requested after destroy() fails fast. */
	@Test(timeout = 30000)
	public void getComputeContextsFailsFastAfterDestroy() {
		CudaDataContext context = newContext();
		context.destroy();
		assertFailsAsDestroyed(context::getComputeContexts);
	}

	/**
	 * destroy() from a thread holding the read side of the lifecycle lock must fail fast
	 * rather than deadlock on the write lock.
	 */
	@Test(timeout = 30000)
	public void destroyFromWithinScopeFailsFast() throws Exception {
		CudaDataContext context = newContext();

		Field lockField = AcceleratorDataContext.class.getDeclaredField("lifecycleLock");
		lockField.setAccessible(true);
		ReentrantReadWriteLock lock = (ReentrantReadWriteLock) lockField.get(context);

		lock.readLock().lock();

		try {
			context.destroy();
			Assert.fail("destroy() should fail fast when called from within a compute-context scope");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the scope reason",
					expected.getMessage().contains("compute-context scope"));
		} finally {
			lock.readLock().unlock();
		}
	}

	/** Values written through the memory provider are read back unchanged, at an offset. */
	@Test(timeout = 60000)
	public void memoryRoundTrip() {
		CudaDataContext context = newContext();

		try {
			CudaMemoryProvider provider;

			try {
				provider = context.getMemoryProvider();
			} catch (LinkageError | HardwareException e) {
				Assume.assumeNoException("CUDA is unavailable", e);
				return;
			}

			CudaMemory mem = provider.allocate(16);
			provider.setMem(mem, 4, new double[] { 1.5, -2.0, 3.25 }, 0, 3);

			double[] out = new double[5];
			provider.getMem(mem, 3, out, 0, 5);
			Assert.assertArrayEquals(new double[] { 0.0, 1.5, -2.0, 3.25, 0.0 }, out, 0.0);

			CudaMemory copy = provider.allocate(16);
			provider.setMem(copy, 0, mem, 4, 3);
			float[] copied = new float[3];
			provider.getMem(copy, 0, copied, 0, 3);
			Assert.assertArrayEquals(new float[] { 1.5f, -2.0f, 3.25f }, copied, 0.0f);

			provider.deallocate(16, copy);
			provider.deallocate(16, mem);
		} finally {
			context.destroy();
		}
	}

	/**
	 * An allocation reads as zeros even when the provider satisfies it by reusing a block an
	 * earlier allocation of the same size wrote to and released.
	 */
	@Test(timeout = 60000)
	public void reusedMemoryIsZeroed() {
		CudaDataContext context = newContext();

		try {
			CudaMemoryProvider provider;

			try {
				provider = context.getMemoryProvider();
			} catch (LinkageError | HardwareException e) {
				Assume.assumeNoException("CUDA is unavailable", e);
				return;
			}

			double[] values = new double[16];
			Arrays.fill(values, 7.0);

			for (int i = 0; i < 4; i++) {
				CudaMemory mem = provider.allocate(16);

				double[] out = new double[16];
				provider.getMem(mem, 0, out, 0, 16);
				Assert.assertArrayEquals("Allocation " + i, new double[16], out, 0.0);

				provider.setMem(mem, 0, values, 0, 16);
				provider.deallocate(16, mem);
			}
		} finally {
			context.destroy();
		}
	}
}
