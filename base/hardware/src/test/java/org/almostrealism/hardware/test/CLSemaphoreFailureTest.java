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

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.hardware.cl.CLSemaphore;
import org.jocl.CL;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates that an OpenCL command that did not execute fails every wait for it, rather than
 * settling as success. The device is replaced through {@link CLSemaphore}'s processing seam, so
 * these tests need no OpenCL device.
 *
 * <p>Plain JUnit: {@code TestSuiteBase} lives in the engine layer, above this module.</p>
 */
public class CLSemaphoreFailureTest {
	/** A semaphore whose event processing fails the way a failed command's does. */
	private static final class FailedCommand extends CLSemaphore {
		/** How many times the event was processed. */
		private final AtomicInteger processed;

		/**
		 * @param processed   counts processing attempts
		 * @param onProcessed runs once processing has finished
		 */
		private FailedCommand(AtomicInteger processed, Runnable onProcessed) {
			super(new OperationMetadata("failed", "a command that did not execute"),
					null, null, null, onProcessed);
			this.processed = processed;
		}

		@Override
		protected void process() {
			processed.incrementAndGet();
			CLComputeContext.requireExecuted(CL.CL_OUT_OF_RESOURCES);
		}
	}

	/** A negative execution status is a failure; {@code CL_COMPLETE} is not. */
	@Test(timeout = 10000)
	public void negativeExecutionStatusIsAFailure() {
		CLComputeContext.requireExecuted(CL.CL_COMPLETE);

		try {
			CLComputeContext.requireExecuted(CL.CL_EXEC_STATUS_ERROR_FOR_EVENTS_IN_WAIT_LIST);
			Assert.fail("A command failed through its wait list did not execute");
		} catch (HardwareException expected) {
			Assert.assertTrue(expected.getMessage().contains("did not execute"));
		}
	}

	/**
	 * The first wait reports the failure, the callback still runs once, and every later wait (on
	 * the semaphore or on a re-attributed copy of it) reports the same failure without processing
	 * the released event again.
	 */
	@Test(timeout = 10000)
	public void failureIsReportedToEveryWaiter() {
		AtomicInteger processed = new AtomicInteger();
		AtomicInteger callbacks = new AtomicInteger();
		CLSemaphore semaphore = new FailedCommand(processed, callbacks::incrementAndGet);
		Semaphore copy = semaphore.withRequester(new OperationMetadata("copy", "re-attributed"));

		for (Semaphore waiter : new Semaphore[] { semaphore, semaphore, copy }) {
			try {
				waiter.waitFor();
				Assert.fail("A command that did not execute must fail every wait");
			} catch (HardwareException expected) {
				Assert.assertTrue("The failure must name the command status: " + expected.getMessage(),
						expected.getMessage().contains("execution status " + CL.CL_OUT_OF_RESOURCES));
			}
		}

		Assert.assertEquals("The event must be processed exactly once", 1, processed.get());
		Assert.assertEquals("The processed callback must run exactly once, on the failure path too",
				1, callbacks.get());
	}

	/**
	 * Once a failed command has been processed its event is released, so a dependent can no longer
	 * be failed by OpenCL through its wait list; ordering after it must report the failure instead
	 * of enqueueing the dependent with no constraint.
	 */
	@Test(timeout = 10000)
	public void dependentOfProcessedFailureIsNotEnqueued() {
		CLSemaphore failed = new FailedCommand(new AtomicInteger(), null);

		try {
			failed.waitFor();
			Assert.fail("The command failure must be reported");
		} catch (HardwareException expected) {
			Assert.assertNotNull(expected.getCause());
		}

		AtomicInteger enqueued = new AtomicInteger();

		try {
			failed.whileValid(event -> enqueued.incrementAndGet());
			Assert.fail("Ordering after a failed command must report the failure");
		} catch (HardwareException expected) {
			Assert.assertTrue(expected.getMessage().contains("did not execute"));
		}

		Assert.assertEquals("The dependent must not be enqueued", 0, enqueued.get());
	}

	/** A failure of the processed callback itself is reported, and stays reported. */
	@Test(timeout = 10000)
	public void callbackFailureIsSuppressedOntoCommandFailure() {
		CLSemaphore semaphore = new FailedCommand(new AtomicInteger(),
				() -> { throw new IllegalStateException("release failed"); });

		try {
			semaphore.waitFor();
			Assert.fail("The command failure must be reported");
		} catch (HardwareException expected) {
			Throwable cause = expected.getCause();
			Assert.assertNotNull(cause);
			Assert.assertEquals(1, cause.getSuppressed().length);
			Assert.assertEquals("release failed", cause.getSuppressed()[0].getMessage());
		}
	}
}
