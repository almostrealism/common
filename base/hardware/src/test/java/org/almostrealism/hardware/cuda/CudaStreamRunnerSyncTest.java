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

		/** Wraps a placeholder handle and records into the given event log. */
		private RecordingStream(List<String> events) {
			super(null, 0L);
			this.events = events;
		}

		@Override
		public void synchronize() { events.add("synchronize"); }

		@Override
		public CUEvent recordEvent() { return new RecordingEvent(events); }

		@Override
		public void release() { }
	}

	/** A {@link CUEvent} whose wait is recorded as {@code "synchronize"} instead of reaching JNI. */
	private static final class RecordingEvent extends CUEvent {
		/** The shared event log. */
		private final List<String> events;

		/** Wraps a placeholder handle and records into the given event log. */
		private RecordingEvent(List<String> events) {
			super(null, 0L);
			this.events = events;
		}

		@Override
		public void synchronize() { events.add("synchronize"); }

		@Override
		public void release() { }
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
}
