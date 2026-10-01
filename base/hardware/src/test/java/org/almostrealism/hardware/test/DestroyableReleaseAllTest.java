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

import io.almostrealism.lifecycle.Destroyable;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates {@link Destroyable#releaseAll(Iterable)}: every release action runs even when earlier
 * ones fail, and the first failure is rethrown with the later ones attached as suppressed.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}:
 * that class lives in the engine layer, which sits above this module.</p>
 */
public class DestroyableReleaseAllTest {

	/** Every action runs, in iteration order, when none of them fails. */
	@Test(timeout = 10000)
	public void runsEveryActionInOrder() {
		List<Integer> ran = new ArrayList<>();
		Destroyable.releaseAll(List.<Runnable>of(() -> ran.add(1), () -> ran.add(2), () -> ran.add(3)));
		Assert.assertEquals(List.of(1, 2, 3), ran);
	}

	/**
	 * Regression: a failing action must not stop the later ones. The first failure is rethrown,
	 * and each later failure is attached to it as suppressed, in order.
	 */
	@Test(timeout = 10000)
	public void failureDoesNotSkipLaterActions() {
		List<Integer> ran = new ArrayList<>();
		IllegalStateException first = new IllegalStateException("first");
		IllegalArgumentException second = new IllegalArgumentException("second");

		try {
			Destroyable.releaseAll(List.<Runnable>of(
					() -> { ran.add(1); throw first; },
					() -> ran.add(2),
					() -> { ran.add(3); throw second; },
					() -> ran.add(4)));
			Assert.fail("The first failure must propagate");
		} catch (IllegalStateException e) {
			Assert.assertSame(first, e);
			Assert.assertEquals(1, e.getSuppressed().length);
			Assert.assertSame(second, e.getSuppressed()[0]);
		}

		Assert.assertEquals(List.of(1, 2, 3, 4), ran);
	}

	/** A failure raised only by the last action is rethrown with nothing suppressed. */
	@Test(timeout = 10000)
	public void lastActionFailurePropagates() {
		List<Integer> ran = new ArrayList<>();
		RuntimeException last = new RuntimeException("last");

		try {
			Destroyable.releaseAll(List.<Runnable>of(() -> ran.add(1), () -> { throw last; }));
			Assert.fail("The failure must propagate");
		} catch (RuntimeException e) {
			Assert.assertSame(last, e);
			Assert.assertEquals(0, e.getSuppressed().length);
		}

		Assert.assertEquals(List.of(1), ran);
	}

	/**
	 * The same exception instance thrown by two actions must not be suppressed onto itself,
	 * which {@link Throwable#addSuppressed} rejects with an {@link IllegalArgumentException}.
	 */
	@Test(timeout = 10000)
	public void repeatedExceptionInstanceIsNotSelfSuppressed() {
		RuntimeException shared = new RuntimeException("shared");

		try {
			Destroyable.releaseAll(List.<Runnable>of(() -> { throw shared; }, () -> { throw shared; }));
			Assert.fail("The failure must propagate");
		} catch (RuntimeException e) {
			Assert.assertSame(shared, e);
			Assert.assertEquals(0, e.getSuppressed().length);
		}
	}

	/** A null or empty iterable is a no-op rather than a failure. */
	@Test(timeout = 10000)
	public void nullAndEmptyAreNoOps() {
		try {
			Destroyable.releaseAll(null);
			Destroyable.releaseAll(Collections.<Runnable>emptyList());
		} catch (RuntimeException e) {
			throw new AssertionError("Releasing nothing must not fail", e);
		}
	}
}
