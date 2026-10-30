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

import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies that destroying a {@link MetalComputeContext} destroys and drains its
 * {@link MetalCommandRunner} before the context's command queue is released, as destroying a
 * CUDA context does for its stream runner: work still open in a command buffer is committed and
 * completed, its completion callbacks run, and the runner refuses further work instead of leaving
 * its thread alive on a released queue.
 */
public class MetalComputeContextTeardownTest extends TestSuiteBase {

	/** A Metal compute context of its own, so destroying it leaves the shared one untouched. */
	private static final class OwnContext extends MetalComputeContext {
		/**
		 * Creates and initializes a context on the device of {@code shared}.
		 *
		 * @param shared the shared Metal context, whose data context and device are reused
		 */
		private OwnContext(MetalComputeContext shared) {
			super((MetalDataContext) shared.getDataContext());
			init(shared.getMtlDevice());
		}
	}

	/** Destroying the context completes an un-waited dispatch and closes its runner. */
	@Test(timeout = 60000)
	public void destroyDrainsCommandRunner() {
		MetalComputeContext shared = SemaphoreChainBatchingTest.metalContext();
		if (shared == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		OwnContext context = new OwnContext(shared);
		MetalCommandRunner runner = context.getCommandRunner();
		AtomicBoolean completed = new AtomicBoolean();

		runner.submit(null, buffer -> { }, null, () -> completed.set(true));
		assertFalse("The dispatch is still open in its command buffer", completed.get());

		context.destroy();

		assertTrue("Destroying the context must complete the open dispatch", completed.get());
		assertTrue("Destroying the context must destroy its runner",
				runner.isClosed());

		try {
			runner.submit(null, buffer -> { }, null, null);
			Assert.fail("The runner of a destroyed context must refuse work");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("destroyed"));
		}
	}
}
