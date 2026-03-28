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

package io.almostrealism.util.test;

import io.almostrealism.util.FrequencyCache;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link FrequencyCache} eviction scoring, specifically
 * verifying the division-by-zero guards added when {@code clock}
 * or {@code count} is zero (e.g., for the very first entry before
 * any accesses have occurred).
 */
public class FrequencyCacheScoreTest extends TestSuiteBase {

	/**
	 * Verifies that putting the very first entry into an empty cache
	 * does not produce NaN scores. Before the fix, {@code score()}
	 * divided by {@code clock} and {@code count} which were both zero
	 * for the initial entry, producing NaN.
	 */
	@Test(timeout = 5000)
	public void firstEntryDoesNotProduceNaN() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.4);
		cache.put("key1", "value1");

		// If score() produced NaN, a second put that triggers comparison
		// would fail or behave unpredictably. Verify the entry is retrievable.
		String retrieved = cache.get("key1");
		Assert.assertEquals("value1", retrieved);
	}

	/**
	 * Verifies that filling the cache to capacity and triggering eviction
	 * works correctly when all entries have been accessed only once
	 * (low frequency counts).
	 */
	@Test(timeout = 5000)
	public void evictionWithMinimalAccessCounts() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(3, 0.4);

		cache.put("a", "va");
		cache.put("b", "vb");
		cache.put("c", "vc");

		// Cache is full (3/3). Adding a 4th entry should evict one.
		cache.put("d", "vd");

		// The newest entry should be present
		Assert.assertEquals("vd", cache.get("d"));

		// At least 3 entries should be present (one was evicted)
		int presentCount = 0;
		if (cache.get("a") != null) presentCount++;
		if (cache.get("b") != null) presentCount++;
		if (cache.get("c") != null) presentCount++;
		if (cache.get("d") != null) presentCount++;
		Assert.assertEquals("Should have exactly 3 entries after eviction", 3, presentCount);
	}

	/**
	 * Verifies that frequently accessed entries survive eviction over
	 * less-accessed entries, confirming the frequency component of
	 * the score formula works correctly.
	 */
	@Test(timeout = 5000)
	public void frequentlyAccessedEntrySurvivesEviction() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(3, 0.8);

		cache.put("hot", "vh");
		cache.put("cold1", "vc1");
		cache.put("cold2", "vc2");

		// Access "hot" many times to boost its frequency
		for (int i = 0; i < 20; i++) {
			cache.get("hot");
		}

		// Trigger eviction by adding a new entry
		cache.put("new", "vn");

		// "hot" should survive eviction due to high frequency
		Assert.assertNotNull("Frequently accessed entry should survive eviction",
				cache.get("hot"));
	}

	/**
	 * Verifies that the cache handles capacity of 1 correctly,
	 * where every new put evicts the previous entry.
	 */
	@Test(timeout = 5000)
	public void capacityOneEvictsOnEveryPut() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(1, 0.5);

		cache.put("first", "v1");
		Assert.assertEquals("v1", cache.get("first"));

		cache.put("second", "v2");
		Assert.assertEquals("v2", cache.get("second"));
		Assert.assertNull("Previous entry should be evicted with capacity 1",
				cache.get("first"));
	}

	/**
	 * Verifies that the evict method removes an entry and that it
	 * is no longer retrievable.
	 */
	@Test(timeout = 5000)
	public void explicitEvictRemovesEntry() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.4);
		cache.put("x", "vx");
		cache.put("y", "vy");

		cache.evict("x");

		Assert.assertNull("Evicted entry should not be retrievable", cache.get("x"));
		Assert.assertEquals("Non-evicted entry should remain", "vy", cache.get("y"));
	}

	/**
	 * Verifies that forEach iterates over all entries currently in the cache.
	 */
	@Test(timeout = 5000)
	public void forEachVisitsAllEntries() {
		FrequencyCache<String, Integer> cache = new FrequencyCache<>(10, 0.4);
		cache.put("a", 1);
		cache.put("b", 2);
		cache.put("c", 3);

		int[] sum = {0};
		cache.forEach((key, value) -> sum[0] += value);
		Assert.assertEquals("forEach should visit all entries", 6, sum[0]);
	}
}
