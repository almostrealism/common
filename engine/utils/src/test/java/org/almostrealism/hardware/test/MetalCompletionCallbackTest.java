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

import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalSemaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates that a failing completion callback on a Metal command buffer does not stop the
 * buffer's other callbacks from running.
 *
 * <p>{@link MetalSemaphore#whenSettled(Runnable)} registers its callback directly in the
 * command buffer's completion-callback list, and the resource-release callbacks registered that
 * way deliberately rethrow cleanup failures. When the buffer was drained, one throwing callback
 * used to skip every later callback for that buffer — leaking whatever they would have released —
 * and skip releasing the command buffer itself.</p>
 */
public class MetalCompletionCallbackTest extends TestSuiteBase {

	/**
	 * Regression: a callback that throws must not skip a later callback on the same buffer. The
	 * failure still reaches the host wait that drained the buffer, and the runner keeps working.
	 */
	@Test(timeout = 60000)
	public void failingCallbackDoesNotSkipLaterCallbacks() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("Skipping failingCallbackDoesNotSkipLaterCallbacks - no Metal device");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();
		AtomicBoolean laterRan = new AtomicBoolean();
		IllegalStateException cleanupFailure = new IllegalStateException("cleanup failure");

		MetalSemaphore dispatch = runner.submit(null, buffer -> { }, null, () -> { throw cleanupFailure; });
		dispatch.whenSettled(() -> laterRan.set(true));

		try {
			dispatch.waitFor();
			Assert.fail("The callback failure must reach the wait that drained the buffer");
		} catch (RuntimeException e) {
			Throwable cause = e;
			while (cause != null && cause != cleanupFailure) cause = cause.getCause();
			Assert.assertSame("The original failure must be preserved, got " + e, cleanupFailure, cause);
		}

		Assert.assertTrue("A later callback on the same buffer must still run", laterRan.get());

		AtomicBoolean nextRan = new AtomicBoolean();
		MetalSemaphore next = runner.submit(null, buffer -> { }, null, () -> nextRan.set(true));
		next.waitFor();
		Assert.assertTrue("The runner must keep draining buffers after a callback failure", nextRan.get());
	}
}
