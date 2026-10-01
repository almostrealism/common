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
