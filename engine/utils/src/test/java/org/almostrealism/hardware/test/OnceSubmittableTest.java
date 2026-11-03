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

import io.almostrealism.concurrent.OnceSubmittable;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates {@link OnceSubmittable}: its operations are submitted by its first submission only,
 * with that submission's dependency; every later submission issues nothing and is ordered after
 * the group while the group may be outstanding; once the group has completed a later submission
 * yields its own dependency alone; and concurrent submissions issue the group exactly once. The
 * operations and completions are recording stand-ins, so no device is involved.
 */
public class OnceSubmittableTest extends TestSuiteBase {

	/** How many threads submit at once in the concurrent test. */
	private static final int THREADS = 8;

	/** An operation that records every dependency it is submitted with. */
	private static class RecordingOperation implements Submittable {
		/** The completion returned by every submission, or null. */
		private final Semaphore completion;
		/** The dependencies this operation was submitted with, in order. */
		private final List<Semaphore> received = new CopyOnWriteArrayList<>();

		/**
		 * Creates an operation reporting {@code completion} for every submission.
		 *
		 * @param completion the completion to report, or null for a synchronous operation
		 */
		RecordingOperation(Semaphore completion) {
			this.completion = completion;
		}

		@Override
		public Semaphore submit(Semaphore dependsOn) {
			received.add(dependsOn);
			return completion;
		}
	}

	/**
	 * Returns whether {@code semaphore} settles within {@code millis}, waiting on another thread.
	 *
	 * @param semaphore the completion to wait for
	 * @param millis    how long to allow
	 * @return true if the wait returned in time
	 */
	private static boolean settlesWithin(Semaphore semaphore, long millis) throws InterruptedException {
		AtomicBoolean released = new AtomicBoolean();
		Thread waiter = new Thread(() -> {
			semaphore.waitFor();
			released.set(true);
		}, "OnceSubmittableTest waiter");
		waiter.setDaemon(true);
		waiter.start();
		waiter.join(millis);
		return released.get();
	}

	/**
	 * The first submission hands its dependency to every operation of the group and returns their
	 * merged completion; later submissions issue nothing.
	 */
	@Test(timeout = 30000)
	public void firstSubmissionIssuesTheGroupWithItsDependency() {
		LatchSemaphore first = new LatchSemaphore(1);
		LatchSemaphore second = new LatchSemaphore(1);
		RecordingOperation a = new RecordingOperation(first);
		RecordingOperation b = new RecordingOperation(second);
		LatchSemaphore dependsOn = new LatchSemaphore(1);

		OnceSubmittable once = new OnceSubmittable(List.of(a, b));
		Semaphore result = once.submit(dependsOn);
		Assert.assertNotNull(result);
		Assert.assertEquals(List.of(dependsOn), a.received);
		Assert.assertEquals(List.of(dependsOn), b.received);

		once.submit(null);
		once.submit(new LatchSemaphore(1));
		Assert.assertEquals("Later submissions issue nothing", 1, a.received.size());
		Assert.assertEquals("Later submissions issue nothing", 1, b.received.size());

		first.countDown();
		second.countDown();
		dependsOn.countDown();
	}

	/**
	 * While the group is outstanding, a later submission with no dependency returns the group's
	 * completion, and one with a dependency returns a completion that settles only once both the
	 * dependency and the group have.
	 */
	@Test(timeout = 30000)
	public void laterSubmissionIsOrderedAfterOutstandingGroup() throws InterruptedException {
		LatchSemaphore running = new LatchSemaphore(1);
		OnceSubmittable once = new OnceSubmittable(List.of(new RecordingOperation(running)));

		Assert.assertSame(running, once.submit(null));
		Assert.assertSame("Without a dependency the group's completion is returned", running, once.submit(null));

		LatchSemaphore dependsOn = new LatchSemaphore(1);
		Semaphore both = once.submit(dependsOn);
		Assert.assertNotSame(dependsOn, both);

		dependsOn.countDown();
		Assert.assertFalse("A later submission must wait for the outstanding group", settlesWithin(both, 250));

		running.countDown();
		Assert.assertTrue("A later submission settles once the group has", settlesWithin(both, 10000));
	}

	/**
	 * Once the group's completion has fired, a later submission is no longer ordered after it and
	 * returns its own dependency, or null when it has none.
	 */
	@Test(timeout = 30000)
	public void completedGroupNoLongerOrdersLaterSubmissions() throws InterruptedException {
		LatchSemaphore running = new LatchSemaphore(1);
		OnceSubmittable once = new OnceSubmittable(List.of(new RecordingOperation(running)));
		once.submit(null);
		running.countDown();

		// The group is released on a callback thread, so allow it to run
		LatchSemaphore dependsOn = new LatchSemaphore(1);
		long deadline = System.currentTimeMillis() + 10000;
		while (once.submit(dependsOn) != dependsOn && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}

		Assert.assertSame("A completed group adds nothing to a later submission", dependsOn, once.submit(dependsOn));
		Assert.assertNull(once.submit(null));
		dependsOn.countDown();
	}

	/**
	 * A group whose submission publishes no completion, including an empty group, leaves later
	 * submissions with their own dependency alone.
	 */
	@Test(timeout = 30000)
	public void groupWithoutCompletionAddsNothing() {
		RecordingOperation synchronous = new RecordingOperation(null);
		OnceSubmittable once = new OnceSubmittable(List.of(synchronous));
		LatchSemaphore dependsOn = new LatchSemaphore(1);

		Assert.assertNull(once.submit(null));
		Assert.assertSame(dependsOn, once.submit(dependsOn));
		Assert.assertNull(once.submit(null));
		Assert.assertEquals(1, synchronous.received.size());

		OnceSubmittable empty = new OnceSubmittable(Collections.emptyList());
		Assert.assertNull(empty.submit(null));
		Assert.assertSame(dependsOn, empty.submit(dependsOn));
		dependsOn.countDown();
	}

	/**
	 * Submitted {@link Submittable#unordered() unordered}, the group is issued with no dependency
	 * while the completion returned still covers the dependency each submission was given.
	 */
	@Test(timeout = 30000)
	public void unorderedGroupIsIssuedWithoutDependency() throws InterruptedException {
		LatchSemaphore running = new LatchSemaphore(1);
		RecordingOperation operation = new RecordingOperation(running);
		Submittable unordered = new OnceSubmittable(List.of(operation)).unordered();

		LatchSemaphore dependsOn = new LatchSemaphore(1);
		Semaphore first = unordered.submit(dependsOn);
		Assert.assertEquals(Collections.singletonList(null), operation.received);

		running.countDown();
		Assert.assertFalse("The completion must still cover the dependency", settlesWithin(first, 250));

		dependsOn.countDown();
		Assert.assertTrue(settlesWithin(first, 10000));
	}

	/** Submissions made concurrently on many threads issue the group exactly once. */
	@Test(timeout = 60000)
	public void concurrentSubmissionsIssueTheGroupOnce() throws InterruptedException {
		LatchSemaphore running = new LatchSemaphore(1);
		RecordingOperation operation = new RecordingOperation(running);
		OnceSubmittable once = new OnceSubmittable(List.of(operation));
		List<Semaphore> results = new CopyOnWriteArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();

		for (int t = 0; t < THREADS; t++) {
			Thread thread = new Thread(() -> {
				try {
					start.await();
					results.add(once.submit(null));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}, "OnceSubmittableTest submitter " + t);
			threads.add(thread);
			thread.start();
		}

		start.countDown();
		for (Thread thread : threads) thread.join(30000);

		Assert.assertEquals("The group is issued exactly once", 1, operation.received.size());
		Assert.assertEquals(THREADS, results.size());
		for (Semaphore result : results) {
			Assert.assertSame("Every submission is ordered after the outstanding group", running, result);
		}

		running.countDown();
	}
}
