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
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
	 * The number of elements a single worker of the long-running dispatch in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} processes. Sized per worker so
	 * the dispatch takes a comparable time however many cores it is split across (its size, and so
	 * its memory, scales with the worker count), while {@link #LONG_DEPTH} supplies the duration
	 * independently of the element count.
	 */
	private static final int LONG_WORK_PER_WORKER = 512 * 1024;

	/**
	 * The number of multiplications applied to every element of the long-running dispatch in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()}. The duration comes from this
	 * depth rather than from the element count, so the dispatch occupies the worker pool long enough
	 * to observe a wedge without allocating a large amount of memory.
	 */
	private static final int LONG_DEPTH = 500;

	/**
	 * How long to wait, in milliseconds, after issuing the long-running dispatch in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} before issuing the independent
	 * one, so that the long one is already occupying the worker pool &mdash; much shorter than the
	 * long dispatch runs, so it is still running when the independent one is issued.
	 */
	private static final long LONG_HEAD_START_MS = 30;

	/**
	 * The number of elements of the independent operation in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()}. Well above the work
	 * subdivision minimum ({@link io.almostrealism.kernel.KernelPreferences#getWorkSubdivisionMinimum()})
	 * so the operation is split across more than one worker and therefore runs on the shared worker
	 * pool (an operation at or below that size runs inline on its coordinating thread instead, never
	 * reaching it), yet small enough that it finishes at once once it has a worker thread.
	 */
	private static final int INDEPENDENT_WORK = 256 * 1024;

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
	 *
	 * <p>A computation with a fixed count of one is assigned to a CPU context whenever one is
	 * configured (see {@code DefaultComputer.getContext}), so these dispatches reach the native
	 * single-worker path under the default driver set, on a GPU host as well; only a GPU-only
	 * driver selection leaves that path unexercised.</p>
	 */
	@Test(timeout = 300000)
	public void independentScalarDispatchesAllComplete() {
		PackedCollection[] counters = new PackedCollection[INDEPENDENT];
		Semaphore[] pending = new Semaphore[INDEPENDENT];

		for (int i = 0; i < INDEPENDENT; i++) {
			counters[i] = new PackedCollection(1);
		}

		try {
			for (int i = 0; i < INDEPENDENT; i++) {
				pending[i] = Submittable.of(a(cp(counters[i]), cp(counters[i]).add(1.0)).get()).submit(null);
			}

			for (int i = 0; i < INDEPENDENT; i++) {
				if (pending[i] != null) pending[i].waitFor();
			}

			for (int i = 0; i < INDEPENDENT; i++) {
				Assert.assertEquals("counter " + i, 1.0, counters[i].toDouble(0), 0.0);
			}
		} finally {
			for (PackedCollection counter : counters) {
				counter.destroy();
			}
		}
	}

	/**
	 * Issues one long-running dispatch and then an independent, unrelated short one, and checks
	 * that the short one finishes first rather than waiting for the long one.
	 *
	 * <p>A dispatch is split across as many workers as the backend runs in parallel, which is also
	 * the resting size of the pool those workers run on, so a single long-running dispatch occupies
	 * every resting worker thread for its whole duration. A native kernel cannot be preempted once
	 * it has started. If the pool merely queued further work behind its resting threads, the short
	 * dispatch &mdash; which has no data dependency on the long one, and which the asynchronous
	 * model lets a caller issue while the long one is still running &mdash; could not complete until
	 * the long one did, however long that took. A pool that grows a thread instead gives the short
	 * dispatch its own worker, so it completes at once, while the long one is still running.</p>
	 *
	 * <p>The long dispatch is issued first and given a brief head start so that it is occupying the
	 * pool before the independent dispatch is issued behind it; the one that completes first is then
	 * recorded. The independent dispatch does a few hundred elements of work and the long one
	 * applies hundreds of multiplications to every element, so the independent one is by far the
	 * quicker of the two and completes first on any machine &mdash; unless it was queued behind the
	 * long one, in which case the long one completes first. The check is therefore of their order,
	 * not of any absolute duration.</p>
	 */
	@Test(timeout = 300000)
	public void independentDispatchIsNotWedgedBehindALongRunningOne() throws InterruptedException {
		int longSize = KernelPreferences.getCpuParallelism() * LONG_WORK_PER_WORKER;
		PackedCollection values = new PackedCollection(longSize);
		PackedCollection independent = new PackedCollection(INDEPENDENT_WORK);

		CollectionProducer product = cp(values);
		for (int i = 0; i < LONG_DEPTH; i++) {
			product = product.multiply(cp(values));
		}

		Submittable longDispatch = Submittable.of(a(cp(values), product).get());
		Submittable independentDispatch = Submittable.of(a(cp(independent), cp(independent).add(1.0)).get());

		try {
			// Warm up both kernels (native compilation) and time the long one.
			long warmStart = System.nanoTime();
			longDispatch.submit(null).waitFor();
			long longMillis = (System.nanoTime() - warmStart) / 1_000_000;
			Semaphore warm = independentDispatch.submit(null);
			if (warm != null) warm.waitFor();

			AtomicReference<String> firstCompleted = new AtomicReference<>();
			CountDownLatch bothCompleted = new CountDownLatch(2);

			Semaphore longPending = longDispatch.submit(null);
			Thread.sleep(LONG_HEAD_START_MS);
			Semaphore independentPending = independentDispatch.submit(null);

			record("long", firstCompleted, bothCompleted, longPending);
			record("independent", firstCompleted, bothCompleted, independentPending);

			Assert.assertTrue("both dispatches should have completed",
					bothCompleted.await(120, TimeUnit.SECONDS));

			log("longMillis=" + longMillis + " firstCompleted=" + firstCompleted.get());
			Assert.assertEquals("the independent dispatch was serialized behind the long-running " +
					"one on the worker pool instead of completing while it ran",
					"independent", firstCompleted.get());
		} finally {
			values.destroy();
			independent.destroy();
		}
	}

	/**
	 * Records {@code name} as the first to complete (if nothing has been recorded yet) once
	 * {@code pending} settles, and counts {@code bothCompleted} down. A {@code null} completion
	 * stands for work that has already finished and is recorded at once.
	 *
	 * @param name          the label for this dispatch
	 * @param firstCompleted holds the label of whichever dispatch settles first
	 * @param bothCompleted  counted down once this dispatch has settled
	 * @param pending        the completion of this dispatch, or {@code null} if it has finished
	 */
	private static void record(String name, AtomicReference<String> firstCompleted,
							   CountDownLatch bothCompleted, Semaphore pending) {
		Runnable mark = () -> {
			firstCompleted.compareAndSet(null, name);
			bothCompleted.countDown();
		};

		if (pending == null) {
			mark.run();
		} else {
			pending.whenComplete(mark);
		}
	}
}
