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

import io.almostrealism.concurrent.Submittable;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Validates that a long chain of dependent submissions is ordered by the backend that runs it,
 * at a cost that does not grow with the length of the chain.
 *
 * <p>This is the contract every caller of {@link Submittable#submit(Semaphore)} relies on: a
 * caller hands each submission the completion of the one before it, keeps only the latest
 * completion, and waits once, where its own host code needs the work to be complete. Whether
 * the backend orders the chain on the device (a shared command buffer) or on the host (a
 * dispatch started when its dependency settles) is the backend's business, but either way the
 * caller must be able to issue as many submissions as it likes before it waits. A backend that
 * instead holds a host thread for every submission issued ahead of its dependency breaks that
 * contract, and a caller that works around it by waiting early defeats the abstraction.</p>
 */
public class ChainedSubmissionTest extends TestSuiteBase {
	/** The number of dependent submissions in the chain. */
	private static final int SUBMISSIONS = 2000;

	/**
	 * The most threads the process may gain while the chain is in flight. A backend that holds
	 * a thread per submission waiting for its dependency gains one for nearly every submission;
	 * one that starts each submission when its dependency settles gains only the few that are
	 * running at any moment.
	 */
	private static final int MAX_THREAD_GROWTH = 64;

	/** The number of independent single-element dispatches submitted at once. */
	private static final int INDEPENDENT = 256;

	/**
	 * Submits {@value #SUBMISSIONS} increments of a counter, each chained on the completion of
	 * the one before it, with a single wait at the end. Every increment reads the value the one
	 * before it wrote, so the counter reaching {@value #SUBMISSIONS} proves the chain was
	 * ordered; the thread count staying within {@value #MAX_THREAD_GROWTH} of where it started
	 * proves the ordering did not cost a thread per submission.
	 */
	@Test(timeout = 300000)
	public void longChainIsOrderedWithoutAThreadPerSubmission() {
		PackedCollection counter = new PackedCollection(1);
		Submittable increment = Submittable.of(a(cp(counter), cp(counter).add(1.0)).get());

		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		increment.submit(null).waitFor();
		counter.setMem(0, 0.0);

		int before = threads.getThreadCount();
		threads.resetPeakThreadCount();

		Semaphore pending = null;
		for (int i = 0; i < SUBMISSIONS; i++) {
			pending = increment.submit(pending);
		}

		if (pending != null) pending.waitFor();
		int growth = threads.getPeakThreadCount() - before;

		log("submissions=" + SUBMISSIONS + " threadsBefore=" + before + " peakThreadGrowth=" + growth);
		Assert.assertEquals(SUBMISSIONS, counter.toDouble(0), 0.0);
		Assert.assertTrue("the chain gained " + growth + " threads, more than " + MAX_THREAD_GROWTH +
				": submissions are holding a thread while they wait for their dependency",
				growth <= MAX_THREAD_GROWTH);

		counter.destroy();
	}

	/**
	 * Alternates {@value #SUBMISSIONS} increments of a single-element counter with an in-place
	 * increment of a large collection, each chained on the one before it, with a single wait at
	 * the end. The two operations are sized so that a system with a GPU runs them on different
	 * backends (the small one on the host, the large one on the device), and the large one reads
	 * the collection it writes, so it also copies its argument in and out around the kernel. Every
	 * submission therefore depends on work of the other backend, and both totals reaching
	 * {@value #SUBMISSIONS} proves that each backend ordered its work after the other's, however
	 * far ahead of the work the chain was submitted.
	 */
	@Test(timeout = 300000)
	public void alternatingBackendChainIsOrdered() {
		PackedCollection counter = new PackedCollection(1);
		PackedCollection values = new PackedCollection(1000);
		Submittable increment = Submittable.of(a(cp(counter), cp(counter).add(1.0)).get());
		Submittable incrementValues = Submittable.of(a(cp(values), cp(values).add(1.0)).get());

		Semaphore pending = null;
		for (int i = 0; i < SUBMISSIONS; i++) {
			pending = increment.submit(pending);
			pending = incrementValues.submit(pending);
		}

		if (pending != null) pending.waitFor();

		Assert.assertEquals(SUBMISSIONS, counter.toDouble(0), 0.0);
		Assert.assertEquals(SUBMISSIONS, values.toDouble(0), 0.0);
		Assert.assertEquals(SUBMISSIONS, values.toDouble(999), 0.0);

		counter.destroy();
		values.destroy();
	}

	/**
	 * Submits {@value #INDEPENDENT} independent increments of separate single-element counters,
	 * none ordered after another, so that far more single-worker dispatches are in flight at once
	 * than there are permits for running them on their coordinating threads. Every counter
	 * reaching exactly one proves each dispatch ran once and settled, which it cannot do if a
	 * permit is lost or held past the kernel it was taken for.
	 */
	// TODO(review): only exercises NativeExecution's inlineWorkers path when the native driver is selected (CI native lanes)
	@Test(timeout = 300000)
	public void independentScalarDispatchesAllComplete() {
		PackedCollection counters[] = new PackedCollection[INDEPENDENT];
		Semaphore pending[] = new Semaphore[INDEPENDENT];

		for (int i = 0; i < INDEPENDENT; i++) {
			counters[i] = new PackedCollection(1);
		}

		for (int i = 0; i < INDEPENDENT; i++) {
			pending[i] = Submittable.of(a(cp(counters[i]), cp(counters[i]).add(1.0)).get()).submit(null);
		}

		for (int i = 0; i < INDEPENDENT; i++) {
			if (pending[i] != null) pending[i].waitFor();
		}

		for (int i = 0; i < INDEPENDENT; i++) {
			Assert.assertEquals("counter " + i, 1.0, counters[i].toDouble(0), 0.0);
			counters[i].destroy();
		}
	}
}
