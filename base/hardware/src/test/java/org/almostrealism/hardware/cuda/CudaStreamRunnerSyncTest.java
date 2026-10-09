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

package org.almostrealism.hardware.cuda;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The completion callback {@link org.almostrealism.hardware.cuda.CudaOperator} hands to
 * {@link CudaStreamRunner#submit} releases the {@code KernelMemoryGuard} reservation over the
 * operation's argument buffers. A launch can report an error after work referencing those buffers
 * has already been submitted to the stream, so the stream must be drained before the buffers are
 * released — otherwise a failed launch becomes a use-after-free. These tests record the order in
 * which {@link CUStream#synchronize()} and the completion callback run.
 *
 * <p>The recording stream and its events override every operation the runner performs on them so
 * they touch no native state, so these tests need no CUDA device and run on every host, like the
 * other hardware tests here. Waiting for a recorded event is logged as {@code "synchronize"}, the
 * same as draining the stream, since both mean the GPU has finished with the buffers.</p>
 */
public class CudaStreamRunnerSyncTest {
	/** A {@link CUStream} that records the calls the runner makes instead of reaching JNI. */
	private static final class RecordingStream extends CUStream {
		/** The shared event log the stream appends {@code "synchronize"} to. */
		private final List<String> events;
		/** Counted down when the first recorded event starts waiting, or {@code null}. */
		private final CountDownLatch entered;
		/** Releases the first recorded event's wait, or {@code null} if none block. */
		private final CountDownLatch gate;
		/** How many events have been recorded, to block only the first. */
		private int recorded;

		/** Wraps a placeholder handle and records into the given event log. */
		private RecordingStream(List<String> events) {
			this(events, null, null);
		}

		/**
		 * Wraps a placeholder handle and records into the given event log, blocking the first
		 * event it records until {@code gate} is released.
		 */
		private RecordingStream(List<String> events, CountDownLatch entered, CountDownLatch gate) {
			super(null, 0L);
			this.events = events;
			this.entered = entered;
			this.gate = gate;
		}

		@Override
		public void synchronize() { events.add("synchronize"); }

		@Override
		public CUEvent recordEvent() {
			if (gate != null && recorded++ == 0) return new RecordingEvent(events, entered, gate);
			return new RecordingEvent(events);
		}

		@Override
		public void release() { }
	}

	/** A {@link CUEvent} whose wait is recorded as {@code "synchronize"} instead of reaching JNI. */
	private static final class RecordingEvent extends CUEvent {
		/** The shared event log. */
		private final List<String> events;
		/** Counted down when the wait begins, or {@code null} if the wait does not block. */
		private final CountDownLatch entered;
		/** Releases the wait, or {@code null} if the wait does not block. */
		private final CountDownLatch gate;

		/** Wraps a placeholder handle and records into the given event log. */
		private RecordingEvent(List<String> events) {
			this(events, null, null);
		}

		/** Wraps a placeholder handle and blocks its wait on {@code gate} once {@code entered}. */
		private RecordingEvent(List<String> events, CountDownLatch entered, CountDownLatch gate) {
			super(null, 0L);
			this.events = events;
			this.entered = entered;
			this.gate = gate;
		}

		@Override
		public void synchronize() {
			if (gate != null) {
				entered.countDown();
				awaitUninterruptibly(gate);
			}

			events.add("synchronize");
		}

		@Override
		public void release() { }
	}

	/**
	 * A {@link CUStream} whose recorded completion event both fails to synchronize and then fails
	 * again when released, so a test can assert that the synchronization failure reaches the caller
	 * with the release failure attached as suppressed rather than replacing it. Draining the stream
	 * and releasing it are no-ops, as in {@link RecordingStream}.
	 */
	private static final class FailingCompletionStream extends CUStream {
		/** Wraps a placeholder handle; the stream touches no native state. */
		private FailingCompletionStream() {
			super(null, 0L);
		}

		@Override
		public void synchronize() { }

		@Override
		public CUEvent recordEvent() { return new FailingCompletionEvent(); }

		@Override
		public void release() { }
	}

	/**
	 * A {@link CUEvent} whose wait throws {@link IllegalStateException} and whose release then
	 * throws {@link IllegalArgumentException}, modeling a completion whose synchronization fails
	 * and whose cleanup also fails.
	 */
	private static final class FailingCompletionEvent extends CUEvent {
		/** Wraps a placeholder handle; the event touches no native state. */
		private FailingCompletionEvent() {
			super(null, 0L);
		}

		@Override
		public void synchronize() { throw new IllegalStateException("sync failed"); }

		@Override
		public void release() { throw new IllegalArgumentException("release failed"); }
	}

	/**
	 * Awaits the given latch, ignoring interrupts so the wait is never abandoned, and restores the
	 * thread's interrupt status afterward if any interrupt arrived. A recorded event's wait models
	 * the GPU finishing with the buffers, which no interrupt may cut short.
	 *
	 * @param latch the latch to await to completion
	 */
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}

		if (interrupted) Thread.currentThread().interrupt();
	}

	/**
	 * On the command-failure path the stream is drained before the completion callback runs, and
	 * the original command failure propagates unchanged.
	 */
	@Test(timeout = 30000)
	public void commandFailureDrainsStreamBeforeCompletion() {
		List<String> events = new ArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));

		try {
			runner.submit(null, stream -> { throw new IllegalStateException("launch failed"); },
					null, () -> events.add("complete"));
			Assert.fail("The command failure should propagate");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("launch failed", expected.getMessage());
		}

		Assert.assertEquals(List.of("synchronize", "complete"), events);
	}

	/**
	 * When the command fails and the completion callback then fails too, the caller still receives
	 * the original command failure, with the callback's failure attached as suppressed rather than
	 * replacing it, exactly as a refused submission preserves its refusal.
	 */
	@Test(timeout = 30000)
	public void commandFailureKeepsCallbackFailureSuppressed() {
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(new ArrayList<>()));

		try {
			runner.submit(null, stream -> { throw new IllegalStateException("launch failed"); },
					null, () -> { throw new IllegalArgumentException("release failed"); });
			Assert.fail("The command failure should propagate");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("launch failed", expected.getMessage());
			Assert.assertEquals(1, expected.getSuppressed().length);
			Assert.assertTrue(expected.getSuppressed()[0] instanceof IllegalArgumentException);
			Assert.assertEquals("release failed", expected.getSuppressed()[0].getMessage());
		}

		runner.destroy();
	}

	/**
	 * When waiting for a submission's completion event fails and releasing that event then fails
	 * too, the submission's semaphore reports the wait failure, carrying the release failure as
	 * suppressed, so the operation that actually failed is not hidden by cleanup.
	 */
	@Test(timeout = 30000)
	public void completionSyncFailureSuppressesReleaseFailure() {
		CudaStreamRunner runner = new CudaStreamRunner(new FailingCompletionStream());
		Semaphore completion = runner.submit(null, s -> { }, null, null);

		try {
			completion.waitFor();
			Assert.fail("The event synchronization failure must propagate");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("sync failed", expected.getMessage());
			Assert.assertEquals(1, expected.getSuppressed().length);
			Assert.assertTrue(expected.getSuppressed()[0] instanceof IllegalArgumentException);
			Assert.assertEquals("release failed", expected.getSuppressed()[0].getMessage());
		}

		runner.destroy();
	}

	/**
	 * When waiting for a submission's completion event fails, releasing it fails, and the completion
	 * callback then fails too, the semaphore still reports the wait failure first and carries both
	 * the release failure and the callback failure as suppressed, so a callback failure is not
	 * discarded just because an earlier failure was already recorded.
	 */
	@Test(timeout = 30000)
	public void completionCallbackFailureSuppressedAfterEventFailure() {
		CudaStreamRunner runner = new CudaStreamRunner(new FailingCompletionStream());
		Semaphore completion = runner.submit(null, s -> { }, null,
				() -> { throw new IllegalStateException("callback failed"); });

		try {
			completion.waitFor();
			Assert.fail("The event synchronization failure must propagate");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("sync failed", expected.getMessage());
			List<String> suppressed = List.of(expected.getSuppressed()).stream()
					.map(Throwable::getMessage).collect(Collectors.toList());
			Assert.assertEquals(List.of("release failed", "callback failed"), suppressed);
		}

		runner.destroy();
	}

	/**
	 * On the success path the command runs, its completion is waited for once, and the completion
	 * callback runs last, in that order, all before the submission's semaphore settles.
	 */
	@Test(timeout = 30000)
	public void successfulSubmitDrainsThenCompletes() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));

		Semaphore completion = runner.submit(null, stream -> events.add("command"),
				null, () -> events.add("complete"));
		Assert.assertNotNull(completion);
		completion.waitFor();

		Assert.assertEquals(List.of("command", "synchronize", "complete"), events);
		runner.destroy();
	}

	/**
	 * A submission whose foreign dependency is pending returns without launching, and holds every
	 * later submission behind it, including one that depends only on the held submission and one
	 * with no dependency at all. Once the dependency completes, all three launch in submission
	 * order and settle in that order.
	 *
	 * @throws InterruptedException if interrupted while waiting for the held submissions
	 */
	@Test(timeout = 30000)
	public void foreignDependencyHoldsLaterSubmissionsInOrder() throws InterruptedException {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		LatchSemaphore pending = new LatchSemaphore(1);

		Semaphore first = runner.submit(null, stream -> events.add("first"),
				pending, () -> events.add("first complete"));
		Semaphore second = runner.submit(null, stream -> events.add("second"),
				first, () -> events.add("second complete"));
		Semaphore third = runner.submit(null, stream -> events.add("third"),
				null, () -> events.add("third complete"));

		Thread.sleep(200);
		Assert.assertEquals("Nothing may launch while the foreign dependency is pending",
				List.of(), events);

		pending.countDown();
		third.waitFor();

		Assert.assertTrue(((CudaSemaphore) first).isSettled());
		Assert.assertTrue(((CudaSemaphore) second).isSettled());
		Assert.assertEquals(List.of("first", "second", "third"),
				events.stream().filter(e -> !e.contains(" ") && !e.equals("synchronize")).collect(Collectors.toList()));
		Assert.assertEquals(List.of("first complete", "second complete", "third complete"),
				events.stream().filter(e -> e.endsWith(" complete")).collect(Collectors.toList()));
		assertEachCallbackFollowsItsSynchronization(events, 3);
		runner.destroy();
	}

	/**
	 * Asserts that exactly {@code submissions} completion events were waited for, and that the
	 * n-th completion callback (an event ending in {@code " complete"}) is preceded by at least n
	 * waits, so every callback runs only after its own submission's completion was observed.
	 *
	 * @param events      the recorded event log
	 * @param submissions the number of submissions that completed
	 */
	private static void assertEachCallbackFollowsItsSynchronization(List<String> events, int submissions) {
		Assert.assertEquals("Each submission's completion must be waited for exactly once", submissions,
				events.stream().filter("synchronize"::equals).count());

		int waits = 0;
		int callbacks = 0;

		for (String event : events) {
			if (event.equals("synchronize")) {
				waits++;
			} else if (event.endsWith(" complete")) {
				callbacks++;
				Assert.assertTrue("Callback " + callbacks + " ran after only " + waits + " waits: " + events,
						waits >= callbacks);
			}
		}

		Assert.assertEquals(submissions, callbacks);
	}

	/**
	 * Destroying the runner while a submission is still held behind an unsatisfied foreign
	 * dependency abandons the submission: its command never launches, its completion callback
	 * still runs so its reservation is released, and its semaphore reports that the runner was
	 * destroyed.
	 */
	@Test(timeout = 30000)
	public void destroyAbandonsHeldSubmissionReportingDestroyed() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		LatchSemaphore pending = new LatchSemaphore(1);

		Semaphore held = runner.submit(null, stream -> events.add("held"),
				pending, () -> events.add("held complete"));

		runner.destroy();

		Assert.assertTrue("A held submission must settle when the runner is destroyed",
				((CudaSemaphore) held).isSettled());
		Assert.assertFalse("The abandoned command must never launch", events.contains("held"));
		Assert.assertTrue("The abandoned submission's callback must still run",
				events.contains("held complete"));

		try {
			held.waitFor();
			Assert.fail("Destroying the runner must fail the held submission");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The failure must explain the runner was destroyed",
					expected.getMessage().contains("destroyed"));
		}
	}

	/**
	 * A completion callback runs on the runner's completion thread, so waiting there for the
	 * submission's own semaphore would wait for the thread itself. That self-wait is rejected
	 * with an {@link IllegalStateException} rather than deadlocking.
	 *
	 * @throws InterruptedException if interrupted while waiting for the callback to run
	 */
	@Test(timeout = 30000)
	public void completionCallbackCannotWaitForItsOwnSubmission() throws InterruptedException {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));

		CountDownLatch ready = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Semaphore> handle = new AtomicReference<>();
		AtomicReference<Throwable> rejected = new AtomicReference<>();

		handle.set(runner.submit(null, stream -> { }, null, () -> {
			try {
				ready.await();
				handle.get().waitFor();
			} catch (IllegalStateException e) {
				rejected.set(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				done.countDown();
			}
		}));

		ready.countDown();
		done.await();

		Assert.assertNotNull("Waiting from the completion thread must be rejected", rejected.get());
		Assert.assertTrue("The rejection must be an IllegalStateException",
				rejected.get() instanceof IllegalStateException);
		runner.destroy();
	}

	/**
	 * The completion thread settles submissions in order, so by the time a later submission's
	 * callback runs every earlier submission has settled. Waiting there for an earlier submission
	 * cannot deadlock, so it must return normally rather than be rejected like a self-wait, and it
	 * must still report the earlier submission's failure when it failed.
	 *
	 * @throws InterruptedException if interrupted while waiting for the callback to run
	 */
	@Test(timeout = 30000)
	public void completionCallbackMayWaitForSettledEarlierSubmission() throws InterruptedException {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		LatchSemaphore pending = new LatchSemaphore(1);

		Semaphore earlier = runner.submit(null, stream -> events.add("earlier"),
				null, () -> events.add("earlier complete"));
		CudaSemaphore failed = (CudaSemaphore) runner.submit(null, stream -> events.add("failed"),
				pending, () -> events.add("failed complete"));

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> earlierWait = new AtomicReference<>();
		AtomicReference<Throwable> failedWait = new AtomicReference<>();

		runner.submit(null, stream -> { }, null, () -> {
			try {
				earlier.waitFor();
			} catch (RuntimeException e) {
				earlierWait.set(e);
			}

			try {
				failed.waitFor();
			} catch (RuntimeException e) {
				failedWait.set(e);
			} finally {
				done.countDown();
			}
		});

		pending.fail(new IllegalArgumentException("dependency failed"));
		pending.countDown();
		done.await();

		Assert.assertNull("Waiting for a settled earlier submission must not be rejected", earlierWait.get());
		Assert.assertTrue("The failed submission's failure must be reported, not a self-wait rejection",
				failedWait.get() instanceof IllegalArgumentException);
		Assert.assertEquals("dependency failed", failedWait.get().getMessage());
		Assert.assertFalse("A failed dependency must not launch its command", events.contains("failed"));
		runner.destroy();
	}

	/**
	 * When a held submission's foreign dependency fails and its completion callback then fails too,
	 * the submission's semaphore reports the dependency failure first and carries the callback failure
	 * as suppressed. The dependency failure is recorded before the callback runs, so this guards
	 * against the callback failure being silently dropped because a failure was already recorded.
	 */
	@Test(timeout = 30000)
	public void dependencyFailureKeepsCallbackFailureSuppressed() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		LatchSemaphore pending = new LatchSemaphore(1);

		Semaphore held = runner.submit(null, stream -> events.add("held"), pending,
				() -> { throw new IllegalArgumentException("release failed"); });

		pending.fail(new IllegalStateException("dependency failed"));
		pending.countDown();

		try {
			held.waitFor();
			Assert.fail("The dependency failure must propagate");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("dependency failed", expected.getMessage());
			Assert.assertEquals(1, expected.getSuppressed().length);
			Assert.assertTrue(expected.getSuppressed()[0] instanceof IllegalArgumentException);
			Assert.assertEquals("release failed", expected.getSuppressed()[0].getMessage());
		}

		Assert.assertFalse("A failed dependency must not launch its command", events.contains("held"));
		runner.destroy();
	}

	/**
	 * Interrupting the completion thread while a completion is still queued must not leave that
	 * submission's semaphore un-settled: its callback still runs and {@link Semaphore#waitFor()}
	 * returns rather than hanging forever. The first submission holds the completion thread inside
	 * {@code settle()} so the second's completion is waiting in the queue when the interrupt lands;
	 * once the thread returns to {@code take()} it sees the interrupt and settles what remains.
	 *
	 * @throws Exception if interrupted while waiting, or the completion thread cannot be reached
	 */
	@Test(timeout = 30000)
	public void interruptSettlesPendingCompletionsSoWaitersDoNotHang() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events, entered, gate));

		runner.submit(null, stream -> events.add("first"), null, () -> events.add("first complete"));
		Semaphore second = runner.submit(null, stream -> events.add("second"),
				null, () -> events.add("second complete"));

		entered.await();

		completionThread(runner).interrupt();
		gate.countDown();

		second.waitFor();

		Assert.assertTrue("The queued submission must settle despite the interrupt",
				((CudaSemaphore) second).isSettled());
		Assert.assertTrue("The queued submission's callback must still run",
				events.contains("second complete"));
	}

	/**
	 * A submission refused because the runner has already been destroyed must still run its
	 * completion callback, which is how callers release the memory reservation they acquired before
	 * submitting; otherwise losing the race with {@code destroy()} pins that reservation forever. The
	 * command itself must never run, and the refusal must reach the caller.
	 */
	@Test(timeout = 30000)
	public void submitAfterDestroyRunsCompletionCallback() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		runner.destroy();
		events.clear();

		try {
			runner.submit(null, stream -> events.add("command"), null, () -> events.add("complete"));
			Assert.fail("A destroyed runner must refuse the submission");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The refusal must explain the runner was destroyed",
					expected.getMessage().contains("destroyed"));
			Assert.assertEquals(0, expected.getSuppressed().length);
		}

		Assert.assertEquals("Only the refused submission's callback may run",
				List.of("complete"), events);
	}

	/**
	 * When the completion callback of a refused submission itself fails, the caller still receives
	 * the refusal, with the callback's failure attached as suppressed rather than replacing it.
	 */
	@Test(timeout = 30000)
	public void submitAfterDestroyKeepsCallbackFailureSuppressed() {
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(new ArrayList<>()));
		runner.destroy();

		try {
			runner.submit(null, stream -> { }, null, () -> {
				throw new IllegalArgumentException("release failed");
			});
			Assert.fail("A destroyed runner must refuse the submission");
		} catch (IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("destroyed"));
			Assert.assertEquals(1, expected.getSuppressed().length);
			Assert.assertTrue(expected.getSuppressed()[0] instanceof IllegalArgumentException);
			Assert.assertEquals("release failed", expected.getSuppressed()[0].getMessage());
		}
	}

	/**
	 * A submission without a completion callback is still refused cleanly once the runner has been
	 * destroyed.
	 */
	@Test(timeout = 30000)
	public void submitAfterDestroyWithoutCallbackIsRefused() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		runner.destroy();
		events.clear();

		try {
			runner.submit(null, stream -> events.add("command"), null, null);
			Assert.fail("A destroyed runner must refuse the submission");
		} catch (IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("destroyed"));
		}

		Assert.assertEquals(List.of(), events);
	}

	/**
	 * A re-attributed view returned by {@link CudaSemaphore#withRequester} is a distinct handle
	 * that keeps the original runner and carries the new requester metadata, while the original
	 * keeps its own, and it shares the submission's settlement state, so once the submission has
	 * completed the view reports it settled too.
	 */
	@Test(timeout = 30000)
	public void withRequesterKeepsRunnerAndSharesSettlement() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));

		OperationMetadata original = new OperationMetadata("original", "original");
		OperationMetadata reattributed = new OperationMetadata("reattributed", "reattributed");

		CudaSemaphore completion = (CudaSemaphore) runner.submit(original,
				stream -> events.add("command"), null, () -> events.add("complete"));
		CudaSemaphore view = (CudaSemaphore) completion.withRequester(reattributed);

		Assert.assertNotSame("withRequester must return a distinct handle", completion, view);
		Assert.assertSame("The view must keep the original runner", runner, view.getRunner());
		Assert.assertSame("The view must carry the new requester", reattributed, view.getRequester());
		Assert.assertSame("The original must keep its own requester", original, completion.getRequester());

		completion.waitFor();

		Assert.assertTrue("The view must share the original's settlement state", view.isSettled());
		view.waitFor();
		runner.destroy();
	}

	/**
	 * A re-attributed view shares the original submission's failure: when the submission it
	 * describes fails, waiting on the view rethrows that failure rather than returning as though
	 * the work had succeeded. This is the reason the view reuses the original's failure reference.
	 */
	@Test(timeout = 30000)
	public void withRequesterViewSharesFailure() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		LatchSemaphore pending = new LatchSemaphore(1);

		CudaSemaphore held = (CudaSemaphore) runner.submit(null, stream -> events.add("held"),
				pending, () -> events.add("held complete"));
		CudaSemaphore view = (CudaSemaphore) held.withRequester(
				new OperationMetadata("reattributed", "reattributed"));

		runner.destroy();

		Assert.assertTrue("The view must share the original's settlement state", view.isSettled());

		try {
			view.waitFor();
			Assert.fail("The view must rethrow the shared failure");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The failure must explain the runner was destroyed",
					expected.getMessage().contains("destroyed"));
		}
	}

	/**
	 * If recording a new event fails and releasing that event then fails too, the recording failure
	 * is the one thrown, carrying the release failure as suppressed, so the operation that actually
	 * failed is not hidden. A successful release adds nothing.
	 */
	@Test(timeout = 30000)
	public void recordEventFailureSuppressesReleaseFailure() {
		IllegalStateException failure = recordEventFailure(true);
		Assert.assertEquals("record failed", failure.getMessage());
		Assert.assertEquals(1, failure.getSuppressed().length);
		Assert.assertEquals("release failed", failure.getSuppressed()[0].getMessage());

		IllegalStateException clean = recordEventFailure(false);
		Assert.assertEquals("record failed", clean.getMessage());
		Assert.assertEquals(0, clean.getSuppressed().length);
	}

	/**
	 * Calls {@link CUStream#recordEvent()} on a stream whose recording always fails, in a context
	 * whose new events optionally fail to release, and returns the failure it throws.
	 *
	 * @param releaseFails whether releasing the unrecorded event fails
	 * @return the failure thrown by {@link CUStream#recordEvent()}
	 */
	private static IllegalStateException recordEventFailure(boolean releaseFails) {
		CUContext context = new CUContext(null, 0L) {
			@Override
			public CUEvent newEvent() {
				return new CUEvent(this, 0L) {
					@Override
					public void release() {
						if (releaseFails) throw new IllegalStateException("release failed");
					}
				};
			}
		};

		CUStream stream = new CUStream(context, 0L) {
			@Override
			public void record(CUEvent event) {
				throw new IllegalStateException("record failed");
			}
		};

		try {
			stream.recordEvent();
		} catch (IllegalStateException e) {
			return e;
		}

		throw new AssertionError("recordEvent must propagate the recording failure");
	}

	/**
	 * The {@code onHeld} hook of a submission runs only when the runner holds the submission
	 * instead of launching it: not for a submission launched immediately, but for one held behind a
	 * foreign dependency, and for one held only because an earlier submission is held. It runs before
	 * {@code submit} returns.
	 */
	@Test(timeout = 30000)
	public void onHeldRunsOnlyWhenSubmissionIsHeld() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		LatchSemaphore pending = new LatchSemaphore(1);

		runner.submit(null, stream -> events.add("immediate"), null,
				() -> events.add("immediate held"), null);
		Assert.assertFalse("A submission launched immediately must not run onHeld",
				events.contains("immediate held"));

		runner.submit(null, stream -> events.add("foreign"), pending,
				() -> events.add("foreign held"), null);
		Assert.assertTrue("A submission held behind a foreign dependency must run onHeld before submit returns",
				events.contains("foreign held"));

		Semaphore behind = runner.submit(null, stream -> events.add("behind"), null,
				() -> events.add("behind held"), null);
		Assert.assertTrue("A submission held behind an earlier held one must run onHeld",
				events.contains("behind held"));
		Assert.assertFalse("A held submission must not launch before its turn", events.contains("behind"));

		pending.countDown();
		behind.waitFor();
		Assert.assertTrue(events.indexOf("foreign") < events.indexOf("behind"));
		runner.destroy();
	}

	/**
	 * When the {@code onHeld} hook fails, the submission is rejected: its command never launches,
	 * its completion callback runs so whatever it releases is not leaked, and the hook's failure is
	 * thrown from {@code submit}.
	 */
	@Test(timeout = 30000)
	public void onHeldFailureRejectsSubmission() {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events));
		LatchSemaphore pending = new LatchSemaphore(1);

		try {
			runner.submit(null, stream -> events.add("command"), pending,
					() -> { throw new IllegalArgumentException("lease failed"); },
					() -> events.add("complete"));
			Assert.fail("A failing onHeld must reject the submission");
		} catch (IllegalArgumentException expected) {
			Assert.assertEquals("lease failed", expected.getMessage());
		}

		pending.countDown();
		runner.destroy();

		Assert.assertTrue("The rejected submission's callback must run", events.contains("complete"));
		Assert.assertFalse("The rejected submission must never launch", events.contains("command"));
	}

	/**
	 * Once its completion thread has been interrupted, nothing would observe a new submission's
	 * completion, so the runner refuses further work, running the refused submission's callback,
	 * exactly as a destroyed runner does. Destroying it afterwards still releases the stream.
	 *
	 * @throws Exception if interrupted while waiting, or the completion thread cannot be reached
	 */
	@Test(timeout = 30000)
	public void interruptedCompletionThreadRefusesLaterSubmissions() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		CudaStreamRunner runner = new CudaStreamRunner(new TeardownStream(events, null, null));

		Thread completions = completionThread(runner);
		completions.interrupt();
		completions.join();

		try {
			runner.submit(null, stream -> events.add("command"), null, () -> events.add("complete"));
			Assert.fail("A runner without a completion thread must refuse work");
		} catch (IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("destroyed"));
		}

		Assert.assertEquals(List.of("complete"), events);

		runner.destroy();
		Assert.assertEquals("Destroying the runner must still release its stream",
				List.of("complete", "released"), events);
	}

	/**
	 * A {@code destroy()} that arrives while another is still waiting for the stream must not return
	 * until that teardown has released the stream, and the stream is released only once.
	 *
	 * @throws InterruptedException if interrupted while waiting for the destroying threads
	 */
	@Test(timeout = 30000)
	public void concurrentDestroyWaitsForTeardown() throws InterruptedException {
		List<String> events = new CopyOnWriteArrayList<>();
		CountDownLatch draining = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		CudaStreamRunner runner = new CudaStreamRunner(new TeardownStream(events, draining, gate));

		Thread first = new Thread(runner::destroy, "first destroy");
		first.start();
		draining.await();

		Thread second = new Thread(runner::destroy, "second destroy");
		second.start();
		second.join(500);
		Assert.assertTrue("A concurrent destroy must wait for the teardown in progress", second.isAlive());

		gate.countDown();
		first.join();
		second.join();

		Assert.assertEquals("The stream must be released exactly once",
				List.of("released"), events);
	}

	/**
	 * Destroying the runner while a submission is held behind a foreign dependency that never
	 * completes cancels the wait for that dependency, and the wait runs on a daemon thread, so a
	 * dependency that is never satisfied can neither keep a thread blocked nor keep the JVM alive.
	 *
	 * @throws InterruptedException if interrupted while waiting for the wait to be cancelled
	 */
	@Test(timeout = 30000)
	public void destroyCancelsDependencyWaitOnDaemonThread() throws InterruptedException {
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(new CopyOnWriteArrayList<>()));
		CountDownLatch waiting = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		AtomicReference<Thread> waiter = new AtomicReference<>();

		Semaphore neverCompletes = () -> {
			waiter.set(Thread.currentThread());
			waiting.countDown();

			try {
				new CountDownLatch(1).await();
			} catch (InterruptedException e) {
				cancelled.countDown();
			}
		};

		runner.submit(null, stream -> { }, neverCompletes, null);
		waiting.await();
		Assert.assertTrue("The dependency must be waited for on a daemon thread", waiter.get().isDaemon());

		runner.destroy();
		Assert.assertTrue("Destroying the runner must cancel the dependency wait",
				cancelled.await(10, TimeUnit.SECONDS));
	}

	/**
	 * Two completions of the same runner merge into the one submitted later, in either order, and
	 * so does a merge of several through {@link Semaphore#all(List)}. Completions of another runner,
	 * or of another provider, do not merge.
	 */
	@Test(timeout = 30000)
	public void completionsOfOneRunnerMergeIntoTheLater() {
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(new CopyOnWriteArrayList<>()));
		CudaStreamRunner other = new CudaStreamRunner(new RecordingStream(new CopyOnWriteArrayList<>()));

		Semaphore first = runner.submit(null, stream -> { }, null, null);
		Semaphore second = runner.submit(null, stream -> { }, null, null);
		Semaphore elsewhere = other.submit(null, stream -> { }, null, null);

		Assert.assertSame(second, first.merge(second));
		Assert.assertSame(second, second.merge(first));
		Assert.assertSame(second, Semaphore.all(List.of(first, second)));
		Assert.assertNull("Completions of different runners must not merge", first.merge(elsewhere));
		Assert.assertNull("A completion of another provider must not merge",
				first.merge(new LatchSemaphore(1)));

		runner.destroy();
		other.destroy();
	}

	/**
	 * A submission whose immediate launch fails settles in its turn: its callback does not run
	 * before that of an earlier submission still on the device, and the failure is thrown only once
	 * its callback has run. This order is what lets a later completion stand for an earlier one
	 * (see {@link CudaSemaphore#merge}).
	 *
	 * @throws InterruptedException if interrupted while waiting for the submitting thread
	 */
	@Test(timeout = 30000)
	public void immediateLaunchFailureSettlesAfterEarlierSubmissions() throws InterruptedException {
		List<String> events = new CopyOnWriteArrayList<>();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		CudaStreamRunner runner = new CudaStreamRunner(new RecordingStream(events, entered, gate));

		runner.submit(null, stream -> { }, null, () -> events.add("first complete"));
		entered.await();

		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread submitting = new Thread(() -> {
			try {
				runner.submit(null, stream -> { throw new IllegalStateException("launch failed"); },
						null, () -> events.add("second complete"));
			} catch (IllegalStateException e) {
				thrown.set(e);
			}
		});
		submitting.start();
		submitting.join(500);

		Assert.assertTrue("The failure must wait for the earlier submission to settle", submitting.isAlive());
		Assert.assertFalse(events.contains("second complete"));

		gate.countDown();
		submitting.join();

		Assert.assertNotNull("The launch failure must reach the submitting thread", thrown.get());
		Assert.assertEquals("launch failed", thrown.get().getMessage());
		Assert.assertTrue(events.indexOf("first complete") < events.indexOf("second complete"));
		runner.destroy();
	}

	/**
	 * A {@link CUStream} for teardown tests: draining it optionally blocks on a gate, and releasing
	 * it is recorded as {@code "released"}. Completion events are recorded as in
	 * {@link RecordingStream} but not logged.
	 */
	private static final class TeardownStream extends CUStream {
		/** The event log {@code "released"} is appended to. */
		private final List<String> events;
		/** Counted down when a drain begins, or {@code null}. */
		private final CountDownLatch draining;
		/** Releases a drain, or {@code null} if drains do not block. */
		private final CountDownLatch gate;

		/** Wraps a placeholder handle, optionally blocking drains on {@code gate}. */
		private TeardownStream(List<String> events, CountDownLatch draining, CountDownLatch gate) {
			super(null, 0L);
			this.events = events;
			this.draining = draining;
			this.gate = gate;
		}

		@Override
		public void synchronize() {
			if (gate != null) {
				draining.countDown();
				awaitUninterruptibly(gate);
			}
		}

		@Override
		public CUEvent recordEvent() { return new RecordingEvent(new ArrayList<>()); }

		@Override
		public void release() { events.add("released"); }
	}

	/**
	 * Returns the completion thread of the given runner, so a test can interrupt it directly.
	 *
	 * @param runner the runner whose completion thread to return
	 * @return the runner's completion thread
	 * @throws ReflectiveOperationException if the field cannot be read
	 */
	private static Thread completionThread(CudaStreamRunner runner) throws ReflectiveOperationException {
		Field field = CudaStreamRunner.class.getDeclaredField("completionThread");
		field.setAccessible(true);
		return (Thread) field.get(runner);
	}
}
