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

import io.almostrealism.concurrent.OperationSemaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Validates {@link Submittable#submit(List, Semaphore)}, the grouped submission whose returned
 * completion a caller treats as the whole group's. Because group members submitted with the same
 * dependency may complete asynchronously &mdash; and so out of submission order &mdash; the merged
 * completion must wait for every member, not merely the one submitted last.
 */
public class SubmittableGroupTest extends TestSuiteBase {

	/**
	 * The completion returned for a group waits for every member, including one that was
	 * submitted earlier but completes later than the last-submitted member. Completing only the
	 * last-submitted member must not release a waiter on the group completion.
	 */
	@Test(timeout = 30000)
	public void mergedCompletionWaitsForEveryMember() throws InterruptedException {
		LatchSemaphore first = new LatchSemaphore(1);
		LatchSemaphore last = new LatchSemaphore(1);

		Semaphore group = Submittable.submit(
				List.of(dependsOn -> first, dependsOn -> last), null);

		AtomicBoolean released = new AtomicBoolean(false);
		Thread waiter = new Thread(() -> {
			group.waitFor();
			released.set(true);
		}, "SubmittableGroupTest waiter");
		waiter.setDaemon(true);
		waiter.start();

		// Completing only the last-submitted member must not be enough: the first member, which
		// completes later here, is still pending.
		last.countDown();
		waiter.join(250);
		assertFalse(released.get());

		first.countDown();
		waiter.join(10000);
		assertTrue(released.get());
	}

	/**
	 * A single-member group returns that member's completion directly, so the common case keeps
	 * the provider's own completion handle rather than wrapping it.
	 */
	@Test(timeout = 30000)
	public void singleMemberReturnsItsCompletionDirectly() {
		LatchSemaphore only = new LatchSemaphore(1);

		Semaphore group = Submittable.submit(List.of(dependsOn -> only), null);

		assertTrue(group == only);
	}

	/**
	 * An empty group has nothing to wait for and reports completion with {@code null}.
	 */
	@Test(timeout = 30000)
	public void emptyGroupCompletesImmediately() {
		assertNull(Submittable.submit(List.of(), null));
	}

	/**
	 * A group whose members all publish no completion handle (fully synchronous work) reports
	 * completion with {@code null}, since there is nothing left to wait for.
	 */
	@Test(timeout = 30000)
	public void fullySynchronousGroupCompletesImmediately() {
		Semaphore group = Submittable.submit(
				List.of(dependsOn -> null, dependsOn -> null), null);

		assertNull(group);
	}

	/**
	 * A member whose {@link Semaphore#waitFor()} throws must not pin the merged completion: the
	 * group must still settle (not hang) and must rethrow the member's failure to its waiter,
	 * rather than swallowing it the way a plain {@code onComplete} callback would. This is now
	 * reachable because a deferred fallback copy can fail.
	 */
	@Test(timeout = 30000)
	public void mergedCompletionSettlesAndRethrowsMemberFailure() {
		Semaphore failing = () -> { throw new RuntimeException("member failed"); };
		LatchSemaphore ok = new LatchSemaphore(1);
		ok.countDown();

		Semaphore group = Submittable.submit(
				List.of(dependsOn -> failing, dependsOn -> ok), null);

		try {
			group.waitFor();
			Assert.fail("A failing member must propagate through the merged completion");
		} catch (RuntimeException e) {
			assertEquals("member failed", e.getMessage());
		}
	}

	/**
	 * When a member's submission throws after an earlier member has started asynchronously, the
	 * failure must not propagate while that earlier member is still running: the caller releases
	 * the group's resources as soon as it sees the failure, and the earlier member, whose
	 * completion never reached the caller, may still be using them. The group submission must
	 * therefore settle the started member before rethrowing, and must not invoke any member after
	 * the one that threw.
	 */
	@Test(timeout = 30000)
	public void partialSubmissionFailureSettlesStartedMembersFirst() throws InterruptedException {
		LatchSemaphore started = new LatchSemaphore(1);
		AtomicBoolean laterSubmitted = new AtomicBoolean(false);
		AtomicBoolean propagated = new AtomicBoolean(false);
		AtomicReference<RuntimeException> failure = new AtomicReference<>();

		Submittable throwing = dependsOn -> { throw new IllegalStateException("submit failed"); };
		Submittable later = dependsOn -> {
			laterSubmitted.set(true);
			return null;
		};

		Thread submitter = new Thread(() -> {
			try {
				Submittable.submit(List.of(dependsOn -> started, throwing, later), null);
			} catch (RuntimeException e) {
				failure.set(e);
			} finally {
				propagated.set(true);
			}
		}, "SubmittableGroupTest submitter");
		submitter.setDaemon(true);
		submitter.start();

		// The started member is still running, so the failure must not have reached the caller
		submitter.join(250);
		assertFalse(propagated.get());

		started.countDown();
		submitter.join(10000);
		assertTrue(propagated.get());

		assertTrue(failure.get() instanceof IllegalStateException);
		assertEquals("submit failed", failure.get().getMessage());
		assertEquals(0, failure.get().getSuppressed().length);
		assertFalse(laterSubmitted.get());
	}

	/**
	 * A started member that itself fails while the group submission is settling it must not
	 * replace the submission failure: the submission failure propagates, carrying the member's
	 * failure as suppressed so neither is lost.
	 */
	@Test(timeout = 30000)
	public void partialSubmissionFailureKeepsStartedMemberFailureAsSuppressed() {
		Semaphore failing = () -> { throw new RuntimeException("member failed"); };
		Submittable throwing = dependsOn -> { throw new IllegalStateException("submit failed"); };

		try {
			Submittable.submit(List.of(dependsOn -> null, dependsOn -> failing, throwing), null);
			Assert.fail("The submission failure must propagate");
		} catch (IllegalStateException e) {
			assertEquals("submit failed", e.getMessage());
			assertEquals(1, e.getSuppressed().length);
			assertEquals("member failed", e.getSuppressed()[0].getMessage());
		}
	}

	/**
	 * A failure in the very first submission has nothing started to settle and propagates
	 * unchanged.
	 */
	@Test(timeout = 30000)
	public void firstSubmissionFailurePropagatesUnchanged() {
		AtomicBoolean laterSubmitted = new AtomicBoolean(false);
		Submittable throwing = dependsOn -> { throw new IllegalStateException("submit failed"); };
		Submittable later = dependsOn -> {
			laterSubmitted.set(true);
			return null;
		};

		try {
			Submittable.submit(List.of(throwing, later), null);
			Assert.fail("The submission failure must propagate");
		} catch (IllegalStateException e) {
			assertEquals("submit failed", e.getMessage());
			assertEquals(0, e.getSuppressed().length);
		}

		assertFalse(laterSubmitted.get());
	}

	/**
	 * A started member that rethrows the very same failure instance as the submission (both
	 * observing one failed dependency) must not replace that failure with a self-suppression
	 * {@link IllegalArgumentException}: the original failure propagates unchanged.
	 */
	@Test(timeout = 30000)
	public void partialSubmissionFailureSharingOneInstancePropagatesUnchanged() {
		IllegalStateException shared = new IllegalStateException("dependency failed");
		Semaphore failing = () -> { throw shared; };
		Submittable throwing = dependsOn -> { throw shared; };

		try {
			Submittable.submit(List.of(dependsOn -> failing, throwing), null);
			Assert.fail("The submission failure must propagate");
		} catch (IllegalStateException e) {
			assertTrue(e == shared);
			assertEquals(0, e.getSuppressed().length);
		}
	}

	/**
	 * Re-attributing a merged completion through {@link OperationSemaphore#withRequester} must
	 * preserve the rethrow-on-failure contract: the view shares the recorded member failure, so
	 * its {@link Semaphore#waitFor()} rethrows the failure rather than returning normally after
	 * the shared latch releases. Without sharing the failure reference the view would have its own
	 * empty reference and silently swallow the failure.
	 */
	@Test(timeout = 30000)
	public void reattributedMergedCompletionStillRethrowsMemberFailure() {
		Semaphore failing = () -> { throw new RuntimeException("member failed"); };
		LatchSemaphore ok = new LatchSemaphore(1);
		ok.countDown();

		Semaphore merged = OperationSemaphore.all(null, List.of(failing, ok));
		assertTrue(merged instanceof OperationSemaphore);

		Semaphore view = ((OperationSemaphore) merged).withRequester(null);

		try {
			view.waitFor();
			Assert.fail("A re-attributed view must rethrow the recorded member failure");
		} catch (RuntimeException e) {
			assertEquals("member failed", e.getMessage());
		}
	}
}
