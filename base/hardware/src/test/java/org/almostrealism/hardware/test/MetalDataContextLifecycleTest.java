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

import org.almostrealism.hardware.ctx.AcceleratorDataContext;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link MetalDataContext}'s lazily-initialized accessors must fail fast once the data
 * context has been destroyed, rather than resurrecting Metal resources (the device,
 * {@code mainRam}) for a context that never actually started before
 * {@link MetalDataContext#destroy()} ran.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module, so depending on
 * it would invert the module graph. This needs no engine-layer support, and none of
 * these assertions require an actual Metal device: {@code destroyed} is checked before
 * the deferred {@code start} callback ever runs.</p>
 */
public class MetalDataContextLifecycleTest {
	/** Creates and initializes a {@link MetalDataContext} without triggering its lazy Metal startup. */
	private MetalDataContext newContext() {
		MetalDataContext context = new MetalDataContext("test-metal", 1024L * 1024, 1024);
		context.init();
		return context;
	}

	/** A context whose lazy Metal initialization never ran must not resurrect it via getDevice() after destroy(). */
	@Test(timeout = 30000)
	public void getDeviceFailsFastAfterDestroy() {
		MetalDataContext context = newContext();
		context.destroy();

		try {
			context.getDevice();
			Assert.fail("getDevice() should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/** A context whose lazy Metal initialization never ran must not resurrect mainRam via getMemoryProvider() after destroy(). */
	@Test(timeout = 30000)
	public void getMemoryProviderFailsFastAfterDestroy() {
		MetalDataContext context = newContext();
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
		MetalDataContext context = newContext();
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
	 * Releasing finished work, which a memory provider at its ceiling requests of every data
	 * context, must not start the device or create a compute context for a data context that has
	 * never run anything: there is no work to release, and starting a device under memory pressure
	 * would only take more memory.
	 */
	@Test(timeout = 30000)
	public void releaseFinishedWorkCreatesNothing() throws Exception {
		MetalDataContext context = newContext();

		try {
			context.releaseFinishedWork();

			Assert.assertNull("No shared compute context may be created",
					acceleratorField("sharedContext").get(context));
			Assert.assertNotNull("The device must not be started",
					acceleratorField("start").get(context));
		} finally {
			context.destroy();
		}
	}

	/** Releasing finished work of a destroyed data context does nothing rather than failing. */
	@Test(timeout = 30000)
	public void releaseFinishedWorkAfterDestroyDoesNothing() throws Exception {
		MetalDataContext context = newContext();
		context.destroy();

		context.releaseFinishedWork();
		Assert.assertNull("No shared compute context may be created",
				acceleratorField("sharedContext").get(context));
	}

	/**
	 * Returns an accessible private field of {@link AcceleratorDataContext}.
	 *
	 * @param name the field name
	 * @return the field
	 */
	private static Field acceleratorField(String name) throws NoSuchFieldException {
		Field field = AcceleratorDataContext.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	/**
	 * A {@link MetalDataContext#destroy()} issued while the calling thread still holds the read
	 * side of the lifecycle lock (as it does throughout {@code sharedContext()} and
	 * {@code computeContext(...)}) must fail fast rather than block forever on the write lock,
	 * which a {@link ReentrantReadWriteLock} can never grant to a read-lock holder. The read
	 * lock is taken directly here so the guard can be exercised without a Metal device.
	 */
	@Test(timeout = 30000)
	public void destroyFromWithinScopeFailsFast() throws Exception {
		MetalDataContext context = newContext();

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
}
