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

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link CLDataContext}'s lazily-initialized accessors must fail fast once the data
 * context has been destroyed, rather than resurrecting OpenCL resources (an OpenCL
 * context, {@code mainRam}) for a context that never actually started before
 * {@link CLDataContext#destroy()} ran.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module, so depending on
 * it would invert the module graph. This needs no engine-layer support, and none of
 * these assertions require an actual OpenCL device: {@code destroyed} is checked before
 * the deferred {@code start} callback ever runs.</p>
 */
public class CLDataContextLifecycleTest {
	/** Creates and initializes a {@link CLDataContext} without triggering its lazy OpenCL startup. */
	private CLDataContext newContext() {
		CLDataContext context = new CLDataContext("test-cl", Precision.FP64,
				1024L * 1024, 1024, CLMemoryProvider.Location.DEVICE);
		context.init();
		return context;
	}

	/** A context whose lazy OpenCL initialization never ran must not resurrect it via getPrecision() after destroy(). */
	@Test(timeout = 30000)
	public void getPrecisionFailsFastAfterDestroy() {
		CLDataContext context = newContext();
		context.destroy();

		try {
			context.getPrecision();
			Assert.fail("getPrecision() should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/** A context whose lazy OpenCL initialization never ran must not resurrect it via getClContext() after destroy(). */
	@Test(timeout = 30000)
	public void getClContextFailsFastAfterDestroy() {
		CLDataContext context = newContext();
		context.destroy();

		try {
			context.getClContext();
			Assert.fail("getClContext() should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/** A context whose lazy OpenCL initialization never ran must not resurrect mainRam via getMemoryProvider() after destroy(). */
	@Test(timeout = 30000)
	public void getMemoryProviderFailsFastAfterDestroy() {
		CLDataContext context = newContext();
		context.destroy();

		try {
			context.getMemoryProvider();
			Assert.fail("getMemoryProvider() should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/** A fresh compute context request after destroy() fails fast instead of racing destroy()'s teardown. */
	@Test(timeout = 30000)
	public void getComputeContextsFailsFastAfterDestroy() {
		CLDataContext context = newContext();
		context.destroy();

		try {
			context.getComputeContexts();
			Assert.fail("getComputeContexts() should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/**
	 * A {@link CLDataContext#destroy()} issued while the calling thread still holds the read
	 * side of the lifecycle lock (as it does throughout {@code getComputeContexts()} and
	 * {@code computeContext(...)}) must fail fast rather than block forever on the write lock,
	 * which a {@link ReentrantReadWriteLock} can never grant to a read-lock holder. The read
	 * lock is taken directly here so the guard can be exercised without an OpenCL device.
	 */
	@Test(timeout = 30000)
	public void destroyFromWithinScopeFailsFast() throws Exception {
		CLDataContext context = newContext();

		Field lockField = CLDataContext.class.getDeclaredField("lifecycleLock");
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
}
