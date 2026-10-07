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

import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.cuda.CUStream;
import org.almostrealism.hardware.cuda.CudaStreamRunner;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The completion callback that {@link org.almostrealism.hardware.cuda.CudaOperator} hands to
 * {@link CudaStreamRunner#submit} releases the {@code KernelMemoryGuard} reservation taken over
 * the operation's arguments. It must therefore run on every exit from {@code submit}, including
 * the failure paths, or the input buffers stay pinned for the rest of the process.
 *
 * <p>Both failures a submission can hit before the stream drains — the dependency wait and the
 * command itself — are exercised here. Neither path touches the (null) stream, so these tests
 * need no CUDA device and run on every runner, like the other lifecycle tests in this module.</p>
 */
public class CudaStreamRunnerTest {
	/** A semaphore whose {@link Semaphore#waitFor()} always fails. */
	private static Semaphore failingDependency() {
		return () -> { throw new IllegalStateException("upstream failed"); };
	}

	/**
	 * When the dependency wait fails, the command does not run, the completion callback still
	 * runs and the failure propagates, so the reservation is released rather than leaked. The
	 * dependency is waited for off the submitting thread, so the failure is reported by the
	 * submission's semaphore.
	 */
	@Test(timeout = 30000)
	public void dependencyFailureRunsCompletion() {
		CudaStreamRunner runner = new CudaStreamRunner(null);
		AtomicBoolean completed = new AtomicBoolean(false);

		Semaphore completion = runner.submit(null,
				stream -> Assert.fail("The command must not run after a failed dependency"),
				failingDependency(), () -> completed.set(true));

		try {
			completion.waitFor();
			Assert.fail("The dependency failure should propagate");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("upstream failed", expected.getMessage());
		}

		Assert.assertTrue("The completion callback must run on the dependency-failure path", completed.get());
	}

	/**
	 * When the command itself fails, the completion callback still runs and the failure
	 * propagates, so the reservation is released rather than leaked.
	 */
	@Test(timeout = 30000)
	public void commandFailureRunsCompletion() {
		CudaStreamRunner runner = new CudaStreamRunner(null);
		AtomicBoolean completed = new AtomicBoolean(false);

		Consumer<CUStream> command = stream -> { throw new IllegalStateException("launch failed"); };

		try {
			runner.submit(null, command, null, () -> completed.set(true));
			Assert.fail("The command failure should propagate");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("launch failed", expected.getMessage());
		}

		Assert.assertTrue("The completion callback must run on the command-failure path", completed.get());
	}
}
