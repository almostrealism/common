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

import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
		Assert.assertTrue("Each completion must be waited for before its callback runs",
				events.indexOf("synchronize") < events.indexOf("first complete"));
		runner.destroy();
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
