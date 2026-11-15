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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.jni.NativeExecution;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
	 * The number of elements the long-running dispatch in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} carries per worker it is sized
	 * for. Its total element count is this times {@link #sizedWorkerCount()}, so on a host whose
	 * parallelism is at or below {@link #MAX_SIZED_WORKERS} every worker processes this many elements
	 * and the dispatch takes a time that does not depend on the core count; on a higher-core host the
	 * total is held at {@link #MAX_SIZED_WORKERS} workers' worth and divided among the (more numerous)
	 * resting workers. {@link #LONG_DEPTH} supplies the per-element duration independently of this
	 * count. Large enough that a worker runs long enough to be observed occupying the pool and for the
	 * independent dispatch to finish well before it.
	 */
	private static final int LONG_WORK_PER_WORKER = 2 * 1024 * 1024;

	/**
	 * The most workers the long-running dispatch in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} is sized for, which bounds its
	 * allocation at {@code MAX_SIZED_WORKERS * }{@link #LONG_WORK_PER_WORKER} doubles (about 128 MiB)
	 * on any host rather than letting it grow with {@link KernelPreferences#getCpuParallelism()}
	 * without bound &mdash; on a 96-core host the uncapped size would be roughly 768 MiB, enough to
	 * exhaust a test lane's memory or exceed the timeout. The dispatch still splits across every
	 * resting worker: its total element count stays far above the core count, so it occupies the
	 * whole pool regardless of this cap, which only stops the allocation from scaling with core
	 * count.
	 */
	private static final int MAX_SIZED_WORKERS = 8;

	/**
	 * The number of multiplications applied to every element of the long-running dispatch in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()}. The duration comes from this
	 * depth rather than from the element count, so the dispatch occupies the worker pool long enough
	 * to observe a wedge without allocating a large amount of memory.
	 */
	private static final int LONG_DEPTH = 500;

	/**
	 * The longest, in milliseconds, that
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} waits to observe the
	 * long-running dispatch occupying the worker pool. The dispatches are pinned to the native
	 * backend (see {@link #nativeDispatch}), so the workers normally appear within a few
	 * milliseconds and the wait ends at once; the bound is generous so a loaded runner's slow
	 * coordinator or thread start-up is still observed. If the occupancy is not observed within it
	 * the test fails, because issuing the independent dispatch before the pool is occupied would no
	 * longer establish the condition the test is about.
	 */
	private static final long WORKER_OCCUPANCY_TIMEOUT_MS = 30000;

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
	 * <p>The long dispatch is issued first, and the independent one is issued only once the long one
	 * is observed occupying every resting worker thread (the test fails if that is never observed);
	 * the time each takes to complete, measured from when the long one was issued, is then
	 * recorded. The independent dispatch does a single addition per element and the long one
	 * applies hundreds of multiplications to every element of a larger collection, so the
	 * independent one is by far the quicker of the two.</p>
	 *
	 * <p>Checking only which completes first would not detect the wedge: a queuing pool starts the
	 * independent dispatch's workers as soon as the <em>first</em> of the long dispatch's workers
	 * finishes, and they can then finish before the <em>last</em> of them does, because the long
	 * dispatch's workers do not all finish at once. The test therefore also requires the independent
	 * dispatch to complete before the long one is half done. Every one of the long dispatch's
	 * workers processes the same amount of work, so none of them finishes that early, and a queued
	 * independent dispatch cannot meet the bound; one with its own threads meets it by a wide
	 * margin. Both checks compare the two dispatches with each other, not with any absolute
	 * duration, so they hold on any machine.</p>
	 */
	@Test(timeout = 300000)
	public void independentDispatchIsNotWedgedBehindALongRunningOne() throws InterruptedException {
		int longSize = sizedWorkerCount() * LONG_WORK_PER_WORKER;
		PackedCollection values = new PackedCollection(longSize);
		PackedCollection independent = new PackedCollection(INDEPENDENT_WORK);

		CollectionProducer product = cp(values);
		for (int i = 0; i < LONG_DEPTH; i++) {
			product = product.multiply(cp(values));
		}

		Submittable longDispatch = Submittable.of(nativeDispatch(a(cp(values), product)));
		Submittable independentDispatch =
				Submittable.of(nativeDispatch(a(cp(independent), cp(independent).add(1.0))));

		boolean bothSettled = false;

		try {
			// Warm up both kernels (native compilation) and time the long one.
			long warmStart = System.nanoTime();
			longDispatch.submit(null).waitFor();
			long longMillis = (System.nanoTime() - warmStart) / 1_000_000;
			Semaphore warm = independentDispatch.submit(null);
			if (warm != null) warm.waitFor();

			AtomicReference<String> firstCompleted = new AtomicReference<>();
			AtomicLong longCompletedAt = new AtomicLong();
			AtomicLong independentCompletedAt = new AtomicLong();
			CountDownLatch bothCompleted = new CountDownLatch(2);

			long issuedAt = System.nanoTime();
			Semaphore longPending = longDispatch.submit(null);
			Assert.assertTrue("the long-running dispatch was not observed occupying the worker pool " +
							"before the independent dispatch was issued",
					awaitWorkerPoolOccupied(KernelPreferences.getCpuParallelism()));
			Semaphore independentPending = independentDispatch.submit(null);

			record("long", firstCompleted, longCompletedAt, bothCompleted, longPending);
			record("independent", firstCompleted, independentCompletedAt, bothCompleted, independentPending);

			bothSettled = bothCompleted.await(120, TimeUnit.SECONDS);

			// Surface a dispatch failure as its own cause rather than as the latch timing out:
			// whenSettled counts the latch down on the failure path too, so a failed dispatch does
			// not block the await, and waitFor rethrows the actual failure at once for settled work.
			if (bothSettled) {
				if (longPending != null) longPending.waitFor();
				if (independentPending != null) independentPending.waitFor();
			}

			long longElapsed = (longCompletedAt.get() - issuedAt) / 1_000_000;
			long independentElapsed = (independentCompletedAt.get() - issuedAt) / 1_000_000;
			log("longMillis=" + longMillis + " firstCompleted=" + firstCompleted.get() +
					" longElapsed=" + longElapsed + " independentElapsed=" + independentElapsed);
			Assert.assertTrue("both dispatches should have completed", bothSettled);
			Assert.assertEquals("the independent dispatch was serialized behind the long-running " +
					"one on the worker pool instead of completing while it ran",
					"independent", firstCompleted.get());
			Assert.assertTrue("the independent dispatch completed after " + independentElapsed +
					"ms, not before the long-running one (" + longElapsed + "ms) was half done, so " +
					"it waited for a worker of the long-running one to finish",
					2 * independentElapsed < longElapsed);
		} finally {
			// A timed-out dispatch may still be reading this memory, so free it only once both settle.
			if (bothSettled) {
				values.destroy();
				independent.destroy();
			}
		}
	}

	/**
	 * The number of workers the long-running dispatch in
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} is sized for: the host's
	 * {@link KernelPreferences#getCpuParallelism() parallelism}, capped at {@link #MAX_SIZED_WORKERS}
	 * so the allocation does not scale without bound on a high-core host. The dispatch still splits
	 * across every resting worker because its total element count stays far above the core count; the
	 * cap bounds only how much memory the test allocates.
	 *
	 * @return the number of workers the long-running dispatch is sized for
	 */
	private static int sizedWorkerCount() {
		return Math.min(KernelPreferences.getCpuParallelism(), MAX_SIZED_WORKERS);
	}

	/**
	 * Compiles {@code assignment} pinned to the {@link ComputeRequirement#JNI native CPU backend}, so
	 * {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} exercises the
	 * {@link NativeExecution} worker pool it validates on every host. Without the pin an operation
	 * whose element count is above the work subdivision minimum selects an accelerator wherever one is
	 * configured (a Metal or OpenCL host), where the two dispatches share a command queue and neither
	 * reaches the worker pool &mdash; so their completion order would not reflect the pool's behaviour
	 * and could record the long dispatch first, failing the test even though the pool is uninvolved.
	 * {@link ComputeRequirement#CPU} is not used because it resolves to OpenCL on x86;
	 * {@code JNI} names the native backend directly and is present on every CI lane.
	 *
	 * <p>The requirement is both recorded on the assignment (so it reaches the compiled scope and the
	 * operation's signature, and is re-applied whenever the operation is dispatched) and pushed onto
	 * the computer's requirements while it compiles, because the backend a computation compiles for
	 * is chosen from the requirements active on the compiling thread
	 * ({@link org.almostrealism.hardware.DefaultComputer#getContext}); recording it on the assignment
	 * alone leaves the compilation free to select an accelerator.</p>
	 *
	 * @param assignment the assignment to compile
	 * @return the compiled runnable, pinned to the native CPU backend
	 */
	private static Runnable nativeDispatch(Assignment<PackedCollection> assignment) {
		List<ComputeRequirement> requirements = List.of(ComputeRequirement.JNI);
		assignment.setComputeRequirements(requirements);

		Hardware.getLocalHardware().getComputer().pushRequirements(requirements);

		try {
			return assignment.get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}

	/**
	 * Waits until {@code expected} worker threads of the native pool are running a kernel, so the
	 * independent dispatch in {@link #independentDispatchIsNotWedgedBehindALongRunningOne()} is
	 * issued only once the long-running one already occupies the pool &mdash; an explicit
	 * observation of worker start (via {@link NativeExecution#getActiveWorkerCount()}) rather than a
	 * fixed delay a loaded runner could outlast. Because the dispatches are pinned to the native
	 * backend (see {@link #nativeDispatch}) the workers do appear; this returns {@code true} as soon
	 * as that many are seen, or {@code false} once {@value #WORKER_OCCUPANCY_TIMEOUT_MS} milliseconds
	 * pass without seeing them, so the caller can fail rather than run the check unestablished.
	 *
	 * @param expected the number of running worker threads that marks the pool as occupied
	 * @return whether the pool was observed occupied within the bound
	 */
	private static boolean awaitWorkerPoolOccupied(int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WORKER_OCCUPANCY_TIMEOUT_MS);

		while (System.nanoTime() < deadline) {
			if (NativeExecution.getActiveWorkerCount() >= expected) return true;
			Thread.sleep(1);
		}

		return NativeExecution.getActiveWorkerCount() >= expected;
	}

	/**
	 * Once {@code pending} settles, records the {@link System#nanoTime() time} it did in
	 * {@code completedAt}, records {@code name} as the first to complete (if nothing has been
	 * recorded yet), and counts {@code bothCompleted} down. A {@code null} completion stands for
	 * work that has already finished and is recorded at once.
	 *
	 * <p>The callback runs on the failure path as well as the ordinary one (via
	 * {@link Semaphore#whenSettled(Runnable)}), so a dispatch that <em>fails</em> still counts the
	 * latch down rather than leaving the caller's {@code await} to time out and report the failure
	 * as a mere non-completion; the failure itself is surfaced by the caller, which calls
	 * {@link Semaphore#waitFor()} on the settled completions to rethrow it as its actual cause.</p>
	 *
	 * @param name           the label for this dispatch
	 * @param firstCompleted holds the label of whichever dispatch settles first
	 * @param completedAt    receives the time at which this dispatch settled
	 * @param bothCompleted  counted down once this dispatch has settled
	 * @param pending        the completion of this dispatch, or {@code null} if it has finished
	 */
	private static void record(String name, AtomicReference<String> firstCompleted, AtomicLong completedAt,
							   CountDownLatch bothCompleted, Semaphore pending) {
		Runnable mark = () -> {
			completedAt.set(System.nanoTime());
			firstCompleted.compareAndSet(null, name);
			bothCompleted.countDown();
		};

		if (pending == null) {
			mark.run();
		} else {
			pending.whenSettled(mark);
		}
	}
}
