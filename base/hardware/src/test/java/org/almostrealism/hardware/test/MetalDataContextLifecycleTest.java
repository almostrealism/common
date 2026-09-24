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

import org.almostrealism.hardware.metal.MetalDataContext;
import org.junit.Assert;
import org.junit.Test;

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
}
