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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * {@link NativeDataContext#getComputeContexts()} must fail fast once the data context
 * has been destroyed, rather than lazily rebuilding a {@code NativeComputeContext}
 * against a compiler and memory provider that destroy() already released.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module, so depending on
 * it would invert the module graph. This needs no engine-layer support.</p>
 */
public class NativeDataContextLifecycleTest {
	/** A compute context is available before destroy(); a fresh request after destroy() fails fast instead of rebuilding. */
	@Test(timeout = 30000)
	public void getComputeContextsFailsFastAfterDestroy() {
		NativeDataContext context = new NativeDataContext("test-native", Precision.FP64, 1024L * 1024);
		context.init();

		List<ComputeContext<MemoryData>> before = context.getComputeContexts();
		Assert.assertFalse("A compute context should be available before destroy()", before.isEmpty());

		context.destroy();

		try {
			context.getComputeContexts();
			Assert.fail("getComputeContexts() should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/** A context whose default memory provider was never lazily created must not resurrect it via getMemoryProvider() after destroy(). */
	@Test(timeout = 30000)
	public void getMemoryProviderFailsFastAfterDestroy() {
		NativeDataContext context = new NativeDataContext("test-native", Precision.FP64, 1024L * 1024);
		context.init();
		context.destroy();

		try {
			context.getMemoryProvider();
			Assert.fail("getMemoryProvider() should fail fast once the data context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}
}
