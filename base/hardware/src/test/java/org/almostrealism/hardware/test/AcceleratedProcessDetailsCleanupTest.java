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

import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryReplacementManager;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Validates that {@link AcceleratedProcessDetails#releaseResources()} always runs both cleanup
 * phases — returning the destination leases and freeing the temporary replacement buffers — even
 * when one of them fails.
 *
 * <p>Destination-lease callbacks are arbitrary {@link Runnable}s and may throw; a failure returning
 * a lease must not leave the temporary replacement buffers unreleased, which would leak native
 * device memory on exactly the failure paths this branch now propagates. The first failure from
 * either phase is rethrown with any later ones attached as suppressed.</p>
 *
 * <p>Each manager is given no target provider, so every argument is replaced and becomes a
 * temporary that {@code releaseResources} must free.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module.</p>
 */
public class AcceleratedProcessDetailsCleanupTest {

	/** Runs each task on the calling thread; {@code releaseResources} does not use the executor. */
	private static final Executor DIRECT = Runnable::run;

	/**
	 * Builds process details whose manager has created exactly one temporary for the given
	 * argument, recording that temporary in {@code created}.
	 */
	private static AcceleratedProcessDetails detailsWithTemporary(MemoryData arg, List<MemoryData> created) {
		return detailsWithTemporary(arg, created, false);
	}

	/**
	 * Builds process details whose manager has created exactly one temporary for the given
	 * argument. When {@code failingDestroy} is set the temporary throws from {@code destroy()}
	 * after freeing its memory, as a failing provider deallocation would.
	 */
	private static AcceleratedProcessDetails detailsWithTemporary(MemoryData arg, List<MemoryData> created,
																  boolean failingDestroy) {
		MemoryReplacementManager manager = new MemoryReplacementManager(null, null, (length, atomic) -> {
			MemoryData tmp = failingDestroy ? new FailingDestroy(length, atomic) : new Bytes(length, atomic);
			created.add(tmp);
			return tmp;
		});

		manager.processArguments(new Object[] { arg });
		Assert.assertEquals(1, created.size());

		return new AcceleratedProcessDetails(new Object[0], 0, manager, DIRECT);
	}

	/**
	 * Regression: a destination lease whose release throws must not stop the temporary
	 * replacement buffers from being freed. The lease failure propagates.
	 */
	@Test(timeout = 30000)
	public void releaseResourcesFreesTemporariesWhenALeaseThrows() {
		List<MemoryData> created = new ArrayList<>();
		Bytes arg = new Bytes(2);

		try {
			AcceleratedProcessDetails details = detailsWithTemporary(arg, created);

			IllegalStateException leaseFailure = new IllegalStateException("lease release failed");
			details.addDestinationLease(() -> { throw leaseFailure; });

			RuntimeException thrown = null;
			try {
				details.releaseResources();
			} catch (RuntimeException e) {
				thrown = e;
			}

			Assert.assertSame("the lease failure must propagate", leaseFailure, thrown);
			Assert.assertTrue("the temporary must still be freed despite the lease failure",
					created.get(0).isDestroyed());
			Assert.assertEquals("the temporary failure is the only concern here", 0, thrown.getSuppressed().length);
		} finally {
			arg.destroy();
		}
	}

	/**
	 * Regression: when both the destination lease release and a temporary destroy fail, the lease
	 * failure (the first phase) is rethrown with the temporary failure attached as suppressed, and
	 * the temporary is still freed.
	 */
	@Test(timeout = 30000)
	public void releaseResourcesAggregatesLeaseAndTemporaryFailures() {
		List<MemoryData> created = new ArrayList<>();
		Bytes arg = new Bytes(2);

		try {
			AcceleratedProcessDetails details = detailsWithTemporary(arg, created, true);

			IllegalStateException leaseFailure = new IllegalStateException("lease release failed");
			details.addDestinationLease(() -> { throw leaseFailure; });

			RuntimeException thrown = null;
			try {
				details.releaseResources();
			} catch (RuntimeException e) {
				thrown = e;
			}

			Assert.assertSame("the lease failure is rethrown first", leaseFailure, thrown);
			Assert.assertEquals(1, thrown.getSuppressed().length);
			Assert.assertEquals(FailingDestroy.MESSAGE, thrown.getSuppressed()[0].getMessage());
			Assert.assertTrue("the temporary must still be freed", created.get(0).isDestroyed());
		} finally {
			arg.destroy();
		}
	}

	/**
	 * Regression: a destination lease whose release throws must not stop the remaining leases from
	 * being returned. The first failure is rethrown with any later one attached as suppressed.
	 */
	@Test(timeout = 30000)
	public void releaseDestinationLeasesRunsEveryLeaseDespiteFailures() {
		AcceleratedProcessDetails details = new AcceleratedProcessDetails(
				new Object[0], 0, new MemoryReplacementManager(null, null, null), DIRECT);

		boolean[] ran = new boolean[3];
		IllegalStateException first = new IllegalStateException("first lease failed");
		IllegalStateException third = new IllegalStateException("third lease failed");

		details.addDestinationLease(() -> { ran[0] = true; throw first; });
		details.addDestinationLease(() -> ran[1] = true);
		details.addDestinationLease(() -> { ran[2] = true; throw third; });

		RuntimeException thrown = null;
		try {
			details.releaseDestinationLeases();
		} catch (RuntimeException e) {
			thrown = e;
		}

		Assert.assertSame("the first lease failure is rethrown", first, thrown);
		Assert.assertEquals(1, thrown.getSuppressed().length);
		Assert.assertSame(third, thrown.getSuppressed()[0]);
		Assert.assertTrue("every lease must run", ran[0] && ran[1] && ran[2]);
		Assert.assertFalse("the leases are cleared after release", details.hasDestinationLeases());
	}

	/**
	 * With no failures, {@code releaseResources} returns normally, runs the lease and frees the
	 * temporary.
	 */
	@Test(timeout = 30000)
	public void releaseResourcesSucceedsWhenNothingFails() {
		List<MemoryData> created = new ArrayList<>();
		Bytes arg = new Bytes(2);

		try {
			AcceleratedProcessDetails details = detailsWithTemporary(arg, created);

			boolean[] leaseRan = new boolean[1];
			details.addDestinationLease(() -> leaseRan[0] = true);

			details.releaseResources();

			Assert.assertTrue("the lease must run", leaseRan[0]);
			Assert.assertTrue("the temporary must be freed", created.get(0).isDestroyed());
			Assert.assertFalse(details.hasDestinationLeases());
		} finally {
			arg.destroy();
		}
	}

	/** A temporary that frees its memory on destroy and then reports a deallocation failure. */
	private static final class FailingDestroy extends Bytes {
		/** The message of the failure every destroy reports. */
		static final String MESSAGE = "deallocation failed";

		/**
		 * Allocates the temporary.
		 *
		 * @param length the number of elements
		 * @param atomic the atomic length
		 */
		FailingDestroy(int length, int atomic) {
			super(length, atomic);
		}

		/** Frees the memory, then reports a failure as a failing provider deallocation would. */
		@Override
		public void destroy() {
			super.destroy();
			throw new IllegalStateException(MESSAGE);
		}
	}
}
