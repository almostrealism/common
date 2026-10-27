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

import io.almostrealism.concurrent.Submittable;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates how an operation is submitted after the work it depends on:
 * {@link Submittable#of(Runnable)}, through which an operation that cannot be submitted takes part
 * by running on the host, and {@link Submittable#chainOnDevice(Semaphore)}, which chains an
 * operation only on a completion that the device orders it after. The operations and completions
 * are recording stand-ins, so every wait and every dependency handed on is observed directly, with
 * no device involved.
 */
public class SubmittableTest extends TestSuiteBase {

	/** A completion that records how many times it was waited for. */
	private static class CountedCompletion implements Semaphore {
		/** Whether the device orders dependents of this completion after it. */
		private final boolean orderedOnDevice;
		/** The number of calls to {@link #waitFor()}. */
		private int waits;

		/**
		 * Creates a completion that the device does or does not order dependents after.
		 *
		 * @param orderedOnDevice whether the device orders dependents of this completion after it
		 */
		CountedCompletion(boolean orderedOnDevice) {
			this.orderedOnDevice = orderedOnDevice;
		}

		@Override
		public void waitFor() { waits++; }

		@Override
		public boolean isOrderedOnDevice() { return orderedOnDevice; }
	}

	/** An operation that records the dependencies it is submitted with. */
	private static class RecordingOperation implements Runnable, Submittable {
		/** This operation's completion. */
		private final CountedCompletion completion = new CountedCompletion(true);
		/** The completions this operation was submitted with, in order. */
		private final List<Semaphore> received = new ArrayList<>();

		@Override
		public void run() { submit(null); }

		@Override
		public Semaphore submit(Semaphore dependsOn) {
			received.add(dependsOn);
			return completion;
		}
	}

	/** An operation that can be submitted is its own submission. */
	@Test(timeout = 30000)
	public void submittableOperationIsItsOwnSubmission() {
		RecordingOperation operation = new RecordingOperation();
		Assert.assertSame(operation, Submittable.of(operation));
	}

	/**
	 * An operation that cannot be submitted runs on the host only once the work it depends on has
	 * been waited for, even work the device would order it after, and leaves nothing in flight.
	 * Without a dependency it runs at once.
	 */
	@Test(timeout = 30000)
	public void hostOperationRunsAfterItsDependency() {
		CountedCompletion dependsOn = new CountedCompletion(true);
		List<Integer> waitsSeenByRun = new ArrayList<>();
		Submittable host = Submittable.of(() -> waitsSeenByRun.add(dependsOn.waits));

		Assert.assertNull(host.submit(dependsOn));
		Assert.assertNull(host.chainOnDevice(dependsOn));
		Assert.assertNull(host.submit(null));
		Assert.assertEquals(List.of(1, 2, 2), waitsSeenByRun);
		Assert.assertEquals(2, dependsOn.waits);
	}

	/** A completion that the device orders dependents after is handed on without a host wait. */
	@Test(timeout = 30000)
	public void chainOnDeviceHandsOnADeviceOrderedCompletion() {
		RecordingOperation operation = new RecordingOperation();
		CountedCompletion dependsOn = new CountedCompletion(true);

		Assert.assertSame(operation.completion, operation.chainOnDevice(dependsOn));
		Assert.assertEquals(List.of(dependsOn), operation.received);
		Assert.assertEquals(0, dependsOn.waits);
	}

	/**
	 * A completion held on the host is waited for before the operation is submitted, so the
	 * operation begins a new chain instead of holding a host thread until that completion.
	 */
	@Test(timeout = 30000)
	public void chainOnDeviceWaitsForAHostOrderedCompletion() {
		RecordingOperation operation = new RecordingOperation();
		CountedCompletion dependsOn = new CountedCompletion(false);

		Assert.assertSame(operation.completion, operation.chainOnDevice(dependsOn));
		Assert.assertEquals(1, operation.received.size());
		Assert.assertNull(operation.received.get(0));
		Assert.assertEquals(1, dependsOn.waits);
	}
}
