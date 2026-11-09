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

package org.almostrealism.audio;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link CompleteIdentifierSet}, covering the once-per-model
 * revalidation contract and the thread-safe iteration that lets the analysis
 * worker add identifiers while {@link AudioLibrary#refresh()} scans the set.
 */
public class CompleteIdentifierSetTest extends TestSuiteBase {

	/** A null model is a no-op: nothing is removed and the set is left untouched. */
	@Test(timeout = 60000)
	public void revalidateNullModelIsNoOp() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		set.add("a");
		set.add("b");

		List<String> removed = set.revalidate(null, id -> false);

		Assert.assertTrue("null model removes nothing", removed.isEmpty());
		Assert.assertEquals("set is untouched", 2, set.size());
	}

	/** Revalidation drops items failing the completeness test and returns exactly those. */
	@Test(timeout = 60000)
	public void revalidateRemovesIncompleteItems() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		set.add("keep");
		set.add("drop");

		List<String> removed = set.revalidate("m1", id -> id.equals("keep"));

		Assert.assertEquals("only the incomplete id is removed", List.of("drop"), removed);
		Assert.assertTrue("complete id is kept", set.contains("keep"));
		Assert.assertFalse("incomplete id is gone", set.contains("drop"));
		Assert.assertEquals(1, set.size());
	}

	/** The costly scan runs once per model: a repeat of the same model is a no-op. */
	@Test(timeout = 60000)
	public void revalidateRunsOncePerModel() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		set.add("x");
		set.add("y");

		List<String> first = set.revalidate("m1", id -> id.equals("x"));
		Assert.assertEquals(List.of("y"), first);

		// Same model again: no re-scan even though "x" would now be judged incomplete.
		AtomicBoolean checked = new AtomicBoolean(false);
		List<String> second = set.revalidate("m1", id -> {
			checked.set(true);
			return false;
		});

		Assert.assertTrue("repeat of the same model removes nothing", second.isEmpty());
		Assert.assertFalse("the completeness predicate is not even consulted", checked.get());
		Assert.assertTrue("the surviving id is still present", set.contains("x"));
	}

	/** A change to a genuinely new model re-runs the scan against that model. */
	@Test(timeout = 60000)
	public void revalidateRescansForNewModel() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		set.add("x");

		Assert.assertTrue(set.revalidate("m1", id -> true).isEmpty());

		List<String> removed = set.revalidate("m2", id -> false);
		Assert.assertEquals("the new model finds x incomplete", List.of("x"), removed);
		Assert.assertTrue("x is removed", set.isEmpty());
	}

	/**
	 * Regression test for the concurrency fix: the worker thread adds identifiers
	 * (as it finishes computing features) while a refresh iterates the set. A
	 * plain {@link java.util.HashSet} would throw
	 * {@link java.util.ConcurrentModificationException} here; the concurrent
	 * backing set must not.
	 */
	@Test(timeout = 60000)
	public void concurrentAddDuringIterationDoesNotThrow() throws InterruptedException {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		for (int i = 0; i < 1000; i++) set.add("seed-" + i);

		AtomicBoolean stop = new AtomicBoolean(false);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		// Churn a bounded key space (disjoint from the seeds) with concurrent
		// adds and removes so the set is structurally modified throughout the
		// iteration below without growing without bound.
		Thread adder = new Thread(() -> {
			int i = 0;
			while (!stop.get()) {
				String key = "churn-" + (i % 2000);
				if ((i & 1) == 0) set.add(key);
				else set.remove(key);
				i++;
			}
		});
		adder.start();

		try {
			// Iterate the set the way refresh() does, repeatedly, while the
			// adder mutates it. The weakly consistent iterator must never throw.
			for (int pass = 0; pass < 2000; pass++) {
				int counted = 0;
				for (String id : set) {
					if (id != null) counted++;
				}
				Assert.assertTrue("iteration still sees the seeded ids", counted >= 1000);
			}
		} catch (Throwable t) {
			failure.set(t);
		} finally {
			stop.set(true);
			adder.join(10000);
		}

		Assert.assertNull("iteration under concurrent add must not throw", failure.get());
	}

	/**
	 * Regression test for the once-per-model contract under overlapping refreshes:
	 * when two threads revalidate the same new model concurrently, exactly one scan
	 * must win and return the stale identifiers, so {@link AudioLibrary#refresh()}
	 * never enqueues the same recomputation work twice. Without the atomic
	 * check/scan/publish both callers would observe the old model and both return
	 * the stale list.
	 */
	@Test(timeout = 60000)
	public void concurrentRevalidateScansOncePerModel() throws InterruptedException {
		for (int trial = 0; trial < 200; trial++) {
			CompleteIdentifierSet set = new CompleteIdentifierSet();
			set.add("stale");

			AtomicInteger scans = new AtomicInteger();
			AtomicInteger nonEmptyResults = new AtomicInteger();
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch go = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();

			Runnable revalidate = () -> {
				try {
					ready.countDown();
					go.await();
					List<String> removed = set.revalidate("m1", id -> {
						scans.incrementAndGet();
						return false;
					});
					if (!removed.isEmpty()) {
						Assert.assertEquals(List.of("stale"), removed);
						nonEmptyResults.incrementAndGet();
					}
				} catch (Throwable t) {
					failure.set(t);
				}
			};

			Thread a = new Thread(revalidate);
			Thread b = new Thread(revalidate);
			a.start();
			b.start();
			ready.await();
			go.countDown();
			a.join(10000);
			b.join(10000);

			Assert.assertNull("concurrent revalidate must not throw", failure.get());
			Assert.assertEquals("the scan runs exactly once across overlapping calls",
					1, scans.get());
			Assert.assertEquals("only one call returns the stale identifiers",
					1, nonEmptyResults.get());
			Assert.assertFalse("the stale id is removed", set.contains("stale"));
		}
	}

	/**
	 * {@link CompleteIdentifierSet#restore} seeds the stored identifiers together
	 * with the model they were validated against, so a reopen under that same
	 * model skips the costly scan — the completeness predicate is never consulted.
	 */
	@Test(timeout = 60000)
	public void restoreUnderValidatedModelSkipsTheScan() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		set.restore(List.of("a", "b"), "model-a");

		Assert.assertTrue("stored ids are seeded", set.contains("a"));
		Assert.assertEquals(2, set.size());

		AtomicBoolean checked = new AtomicBoolean(false);
		List<String> removed = set.revalidate("model-a", id -> {
			checked.set(true);
			return false;
		});

		Assert.assertTrue("a reopen under the validated model removes nothing", removed.isEmpty());
		Assert.assertFalse("the completeness predicate is not consulted — no store scan", checked.get());
		Assert.assertEquals("both items are retained", 2, set.size());
	}

	/**
	 * A set restored with no validated model (a store written before the model
	 * was recorded) is scanned on the first revalidation, as before.
	 */
	@Test(timeout = 60000)
	public void restoreWithNoValidatedModelStillScans() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		set.restore(List.of("a", "b"), null);

		AtomicBoolean checked = new AtomicBoolean(false);
		List<String> removed = set.revalidate("model-a", id -> {
			checked.set(true);
			return id.equals("a");
		});

		Assert.assertTrue("a store with no recorded model is scanned", checked.get());
		Assert.assertEquals("the incomplete item is removed", List.of("b"), removed);
	}

	/**
	 * {@link CompleteIdentifierSet#covers} vouches for identifiers only under the
	 * model the set was last checked against, and only when it holds every one.
	 */
	@Test(timeout = 60000)
	public void coversOnlyUnderTheCheckedModelAndOnlyHeldIdentifiers() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();
		set.restore(List.of("a", "b"), null);
		Assert.assertFalse("an unchecked set vouches for nothing", set.covers("model-a", List.of("a")));
		Assert.assertFalse("a null model never matches", set.covers(null, List.of("a")));

		set.revalidate("model-a", id -> true);
		Assert.assertTrue("every held id is vouched for", set.covers("model-a", List.of("a", "b")));
		Assert.assertTrue("an empty store is trivially covered", set.covers("model-a", List.of()));
		Assert.assertFalse("an id the set does not hold is not vouched for",
				set.covers("model-a", List.of("a", "b", "c")));
		Assert.assertFalse("a different model is not vouched for", set.covers("model-b", List.of("a")));

		set.revalidate("model-b", id -> true);
		Assert.assertFalse("once revalidated for a newer model, the old model is no longer vouched for",
				set.covers("model-a", List.of("a")));
		Assert.assertTrue(set.covers("model-b", List.of("a", "b")));
	}

	/** The type behaves as a {@link Set}: add/remove/contains/size all delegate correctly. */
	@Test(timeout = 60000)
	public void behavesAsASet() {
		Set<String> set = new CompleteIdentifierSet();
		Assert.assertTrue(set.add("a"));
		Assert.assertFalse("duplicate add returns false", set.add("a"));
		Assert.assertTrue(set.contains("a"));
		Assert.assertEquals(1, set.size());
		Assert.assertTrue(set.remove("a"));
		Assert.assertFalse(set.contains("a"));
		Assert.assertTrue(set.isEmpty());
	}

	/**
	 * A {@code null} identifier is tolerated, never stored, and never reported as
	 * contained. The backing {@link java.util.concurrent.ConcurrentHashMap} key
	 * set rejects {@code null}, so without this guard the membership methods would
	 * throw {@link NullPointerException} when a provider yields {@code null} for a
	 * missing or unreadable file — the regression the original failing
	 * {@code AudioLibraryCacheTest} cases exposed.
	 */
	@Test(timeout = 60000)
	public void nullIdentifierIsToleratedNotStored() {
		CompleteIdentifierSet set = new CompleteIdentifierSet();

		Assert.assertFalse("add(null) stores nothing", set.add(null));
		Assert.assertFalse("null is never contained", set.contains(null));
		Assert.assertFalse("remove(null) removes nothing", set.remove(null));
		Assert.assertTrue("the set is unchanged by null operations", set.isEmpty());
	}
}
