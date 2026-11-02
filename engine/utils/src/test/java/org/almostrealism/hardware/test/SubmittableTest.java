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
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates how an operation is submitted after the work it depends on: an operation that can be
 * submitted receives its dependency unchanged and decides itself how to order after it,
 * {@link Submittable#of(Runnable)} lets an operation that cannot be submitted take part by running
 * on the host, and {@link Submittable#unordered()} issues an operation at once while its completion
 * still covers the dependency. The operations and completions are recording stand-ins, so every
 * wait and every dependency handed on is observed directly, with no device involved.
 */
public class SubmittableTest extends TestSuiteBase {

	/** A completion that records how many times it was waited for. */
	private static class CountedCompletion implements Semaphore {
		/** The number of calls to {@link #waitFor()}. */
		private int waits;

		@Override
		public void waitFor() { waits++; }
	}

	/** An operation that records the dependencies it is submitted with. */
	private static class RecordingOperation implements Runnable, Submittable {
		/** This operation's completion. */
		private final CountedCompletion completion = new CountedCompletion();
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
	 * been waited for, and leaves nothing in flight. Without a dependency it runs at once.
	 */
	@Test(timeout = 30000)
	public void hostOperationRunsAfterItsDependency() {
		CountedCompletion dependsOn = new CountedCompletion();
		List<Integer> waitsSeenByRun = new ArrayList<>();
		Submittable host = Submittable.of(() -> waitsSeenByRun.add(dependsOn.waits));

		Assert.assertNull(host.submit(dependsOn));
		Assert.assertNull(host.submit(null));
		Assert.assertEquals(List.of(1, 1), waitsSeenByRun);
		Assert.assertEquals(1, dependsOn.waits);
	}

	/**
	 * Submitting an operation that can be submitted hands it the dependency exactly as given and
	 * returns the operation's own completion: neither the caller nor the submission waits for the
	 * dependency, because ordering after it is the operation's job.
	 */
	@Test(timeout = 30000)
	public void submissionHandsTheDependencyToTheOperation() {
		RecordingOperation operation = new RecordingOperation();
		CountedCompletion dependsOn = new CountedCompletion();

		Assert.assertSame(operation.completion, Submittable.of(operation).submit(dependsOn));
		Assert.assertEquals(List.of(dependsOn), operation.received);
		Assert.assertEquals(0, dependsOn.waits);
	}

	/**
	 * An unordered operation is always submitted with no dependency, and the completion it returns
	 * covers the dependency it was given as well as the operation: an operation finished on
	 * submission yields the dependency itself, and one still running yields a completion that
	 * settles only once both it and the dependency have.
	 */
	@Test(timeout = 30000)
	public void unorderedSubmissionCoversItsDependency() throws InterruptedException {
		LatchSemaphore dependsOn = new LatchSemaphore(1);
		LatchSemaphore running = new LatchSemaphore(1);
		List<Semaphore> received = new ArrayList<>();
		Submittable finished = d -> {
			received.add(d);
			return null;
		};
		Submittable queued = d -> {
			received.add(d);
			return running;
		};

		Assert.assertSame(dependsOn, finished.unordered().submit(dependsOn));
		Assert.assertNull(finished.unordered().submit(null));
		Assert.assertSame(running, queued.unordered().submit(null));

		Semaphore both = queued.unordered().submit(dependsOn);
		Assert.assertEquals(Arrays.asList(null, null, null, null), received);

		AtomicBoolean released = new AtomicBoolean();
		Thread waiter = new Thread(() -> {
			both.waitFor();
			released.set(true);
		}, "SubmittableTest unordered waiter");
		waiter.setDaemon(true);
		waiter.start();

		running.countDown();
		waiter.join(250);
		Assert.assertFalse("The completion must wait for the dependency", released.get());

		dependsOn.countDown();
		waiter.join(10000);
		Assert.assertTrue("The completion settles once the operation and the dependency have", released.get());
	}

	/**
	 * Settling on behalf of a propagating failure waits for the guarded work, attaches a
	 * different failure of that work as suppressed, does not attach the propagating failure to
	 * itself when the work rethrows that very instance, and leaves the failure untouched when the
	 * work completes normally.
	 */
	@Test(timeout = 30000)
	public void waitForSettledSuppressesOnlyOtherFailures() {
		RuntimeException propagating = new RuntimeException("propagating");

		CountedCompletion completes = new CountedCompletion();
		completes.waitForSettled(propagating);
		Assert.assertEquals(1, completes.waits);
		Assert.assertEquals(0, propagating.getSuppressed().length);

		IllegalStateException other = new IllegalStateException("other");
		Semaphore failsDifferently = () -> { throw other; };
		failsDifferently.waitForSettled(propagating);
		Assert.assertArrayEquals(new Throwable[] { other }, propagating.getSuppressed());

		Semaphore rethrows = () -> { throw propagating; };
		rethrows.waitForSettled(propagating);
		Assert.assertArrayEquals(new Throwable[] { other }, propagating.getSuppressed());

		AssertionError error = new AssertionError("error");
		Semaphore failsWithError = () -> { throw error; };
		failsWithError.waitForSettled(propagating);
		Assert.assertArrayEquals(new Throwable[] { other, error }, propagating.getSuppressed());
	}
}
