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

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates {@link Semaphore#all(List)}, the merge
 * primitive for an operation that depends on several prior completions.
 */
public class SemaphoreCompositionTest extends TestSuiteBase {

	/**
	 * Merging nothing (or only nulls, representing fully synchronous work) yields null,
	 * and merging a single semaphore returns that semaphore directly.
	 */
	@Test(timeout = 30000)
	public void degenerateForms() {
		assertTrue(Semaphore.all(null) == null);
		assertTrue(Semaphore.all(Arrays.asList(null, null)) == null);

		DefaultLatchSemaphore only = new DefaultLatchSemaphore((Semaphore) null, 1);
		assertTrue(Semaphore.all(Arrays.asList(null, only, null)) == only);
	}

	/**
	 * A composite over several pending semaphores completes only once every member has
	 * completed, regardless of completion order.
	 */
	@Test(timeout = 30000)
	public void completesAfterAllMembers() throws InterruptedException {
		DefaultLatchSemaphore a = new DefaultLatchSemaphore((Semaphore) null, 1);
		DefaultLatchSemaphore b = new DefaultLatchSemaphore((Semaphore) null, 1);
		DefaultLatchSemaphore c = new DefaultLatchSemaphore((Semaphore) null, 1);

		Semaphore combined = Semaphore.all(Arrays.asList(a, null, b, c));
		assertTrue(combined != a && combined != b && combined != c);

		AtomicBoolean released = new AtomicBoolean(false);
		Thread waiter = new Thread(() -> {
			combined.waitFor();
			released.set(true);
		}, "SemaphoreCompositionTest waiter");
		waiter.start();

		b.countDown();
		c.countDown();

		// The composite must still be held open by the remaining member.
		waiter.join(250);
		assertTrue(!released.get());

		a.countDown();
		waiter.join(10000);
		assertTrue(released.get());
	}

	/**
	 * Members that {@link Semaphore#merge(Semaphore) merge} are folded together before a
	 * composite is built, so a selection whose members all merge yields the merged
	 * completion itself rather than a host-side composite.
	 */
	@Test(timeout = 30000)
	public void mergingMembersFoldIntoOne() {
		Timeline timeline = new Timeline();
		Semaphore first = timeline.completion(1);
		Semaphore second = timeline.completion(2);
		Semaphore third = timeline.completion(3);

		assertTrue(Semaphore.all(Arrays.asList(second, null, third, first)) == third);
	}

	/**
	 * A member that merges with none of the others is still covered after merging: the
	 * composite built over what remains completes only once every original member has, and
	 * the merged members are represented by the later of them.
	 */
	@Test(timeout = 30000)
	public void unmergedMembersStillComposed() throws InterruptedException {
		Timeline timeline = new Timeline();
		Semaphore first = timeline.completion(1);
		Semaphore second = timeline.completion(2);
		DefaultLatchSemaphore other = new DefaultLatchSemaphore((Semaphore) null, 1);

		Semaphore combined = Semaphore.all(Arrays.asList(first, other, second));
		assertTrue(combined != first && combined != second && combined != other);

		AtomicBoolean released = new AtomicBoolean(false);
		Thread waiter = new Thread(() -> {
			combined.waitFor();
			released.set(true);
		}, "SemaphoreCompositionTest merge waiter");
		waiter.start();

		other.countDown();
		timeline.reach(1);

		// The composite must still be held open by the later of the merged members.
		waiter.join(250);
		assertTrue(!released.get());

		timeline.reach(2);
		waiter.join(10000);
		assertTrue(released.get());
	}

	/**
	 * A point that advances monotonically, standing in for a provider whose work completes
	 * in order: a completion at a given point has completed once the timeline reaches it, so
	 * the later of two completions on one timeline implies the earlier.
	 */
	private static class Timeline {
		/** The furthest point this timeline has reached. */
		private long reached;

		/**
		 * Returns a completion that completes once this timeline reaches the given point.
		 *
		 * @param point the point at which the completion completes
		 * @return the completion
		 */
		Semaphore completion(long point) {
			return new TimelineCompletion(this, point);
		}

		/**
		 * Advances this timeline to the given point, completing every completion at or
		 * before it.
		 *
		 * @param point the point to advance to
		 */
		synchronized void reach(long point) {
			reached = Math.max(reached, point);
			notifyAll();
		}

		/**
		 * Blocks until this timeline reaches the given point.
		 *
		 * @param point the point to wait for
		 */
		synchronized void await(long point) {
			while (reached < point) {
				try {
					wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	/**
	 * A completion at one point of a {@link Timeline}. Two completions of the same timeline
	 * merge into the later one; completions of anything else do not merge.
	 */
	private static class TimelineCompletion implements Semaphore {
		/** The timeline this completion belongs to. */
		private final Timeline timeline;
		/** The point of the timeline at which this completion completes. */
		private final long point;

		/**
		 * Creates a completion at the given point of a timeline.
		 *
		 * @param timeline the timeline this completion belongs to
		 * @param point    the point at which it completes
		 */
		TimelineCompletion(Timeline timeline, long point) {
			this.timeline = timeline;
			this.point = point;
		}

		@Override
		public void waitFor() {
			timeline.await(point);
		}

		@Override
		public Semaphore merge(Semaphore other) {
			if (!(other instanceof TimelineCompletion)) return null;

			TimelineCompletion completion = (TimelineCompletion) other;
			if (completion.timeline != timeline) return null;
			return completion.point > point ? completion : this;
		}
	}
}
