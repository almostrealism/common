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

	/**
	 * Regression: an {@link Error} (such as the {@code AssertionError}/{@code OutOfMemoryError} an
	 * arbitrary completion callback may raise) must not skip later actions. The first failure is
	 * rethrown and the later ones are attached as suppressed, exactly as for a {@link RuntimeException}.
	 */
	@Test(timeout = 10000)
	public void errorDoesNotSkipLaterActions() {
		List<Integer> ran = new ArrayList<>();
		AssertionError first = new AssertionError("first");
		RuntimeException second = new RuntimeException("second");

		try {
			Destroyable.releaseAll(List.<Runnable>of(
					() -> { ran.add(1); throw first; },
					() -> ran.add(2),
					() -> { ran.add(3); throw second; },
					() -> ran.add(4)));
			Assert.fail("The first failure must propagate");
		} catch (AssertionError e) {
			Assert.assertSame(first, e);
			Assert.assertEquals(1, e.getSuppressed().length);
			Assert.assertSame(second, e.getSuppressed()[0]);
		}

		Assert.assertEquals(List.of(1, 2, 3, 4), ran);
	}

	/** A {@link RuntimeException} raised first keeps a later {@link Error} as suppressed. */
	@Test(timeout = 10000)
	public void runtimeExceptionFirstSuppressesLaterError() {
		RuntimeException first = new IllegalStateException("first");
		Error second = new LinkageError("second");

		try {
			Destroyable.releaseAll(List.<Runnable>of(() -> { throw first; }, () -> { throw second; }));
			Assert.fail("The first failure must propagate");
		} catch (RuntimeException e) {
			Assert.assertSame(first, e);
			Assert.assertEquals(1, e.getSuppressed().length);
			Assert.assertSame(second, e.getSuppressed()[0]);
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

	/** A {@link Destroyable} that records its own destruction and may throw from {@link #destroy()}. */
	private static final class Probe implements Destroyable {
		/** The failure to raise from {@link #destroy()}, or null to destroy cleanly. */
		private final RuntimeException failure;

		/** Whether {@link #destroy()} has been invoked. */
		private boolean destroyed;

		private Probe() { this(null); }

		private Probe(RuntimeException failure) { this.failure = failure; }

		@Override
		public void destroy() {
			destroyed = true;
			if (failure != null) throw failure;
		}
	}

	/** destroyAll destroys every target and leaves the primary throwable unmodified when none fail. */
	@Test(timeout = 10000)
	public void destroyAllDestroysEveryTargetWithoutFailure() {
		Probe a = new Probe();
		Probe b = new Probe();
		IllegalStateException primary = new IllegalStateException("build failed");

		Destroyable.destroyAll(primary, List.of(a, b, "not-destroyable"));

		Assert.assertTrue("first target destroyed", a.destroyed);
		Assert.assertTrue("second target destroyed", b.destroyed);
		Assert.assertEquals("no cleanup failure to suppress", 0, primary.getSuppressed().length);
	}

	/**
	 * Regression: a target whose destroy() throws must not skip the later targets, and its failure
	 * is attached to the primary throwable as suppressed rather than replacing it.
	 */
	@Test(timeout = 10000)
	public void destroyAllSuppressesFailuresOntoPrimary() {
		IllegalStateException primary = new IllegalStateException("build failed");
		RuntimeException firstFailure = new RuntimeException("release A failed");
		RuntimeException secondFailure = new RuntimeException("release C failed");
		Probe a = new Probe(firstFailure);
		Probe b = new Probe();
		Probe c = new Probe(secondFailure);

		Destroyable.destroyAll(primary, List.of(a, b, c));

		Assert.assertTrue("failing target A still ran", a.destroyed);
		Assert.assertTrue("target B after a failure still ran", b.destroyed);
		Assert.assertTrue("failing target C still ran", c.destroyed);
		Assert.assertEquals("both cleanup failures suppressed", 2, primary.getSuppressed().length);
		Assert.assertSame(firstFailure, primary.getSuppressed()[0]);
		Assert.assertSame(secondFailure, primary.getSuppressed()[1]);
	}

	/** The varargs form behaves like the iterable form and tolerates null entries. */
	@Test(timeout = 10000)
	public void destroyAllVarargsSkipsNullAndNonDestroyable() {
		Probe a = new Probe();
		Probe b = new Probe();
		IllegalStateException primary = new IllegalStateException("build failed");

		Destroyable.destroyAll(primary, a, null, "not-destroyable", b);

		Assert.assertTrue("first target destroyed", a.destroyed);
		Assert.assertTrue("second target destroyed", b.destroyed);
		Assert.assertEquals("no cleanup failure to suppress", 0, primary.getSuppressed().length);
	}

	/** A null iterable of targets is a no-op rather than a failure. */
	@Test(timeout = 10000)
	public void destroyAllNullTargetsIsNoOp() {
		IllegalStateException primary = new IllegalStateException("build failed");
		Destroyable.destroyAll(primary, (Iterable<?>) null);
		Assert.assertEquals(0, primary.getSuppressed().length);
	}
}
