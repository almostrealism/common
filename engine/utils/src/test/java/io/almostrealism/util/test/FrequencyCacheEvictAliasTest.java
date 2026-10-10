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

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link FrequencyCache#evict(Object)} and the capacity-enforcement path
 * when several keys share a single value through the cache's value-deduplication
 * (reverse map).
 *
 * <p>{@link FrequencyCache} documents that "Eviction of one key removes all keys
 * sharing that value" and that the eviction listener is the only hook for releasing
 * the native resources a value holds. The capacity-eviction path
 * ({@code prepareCapacity}) honours this by removing every key that references the
 * evicted entry and firing the listener once for the removed value. These tests
 * assert that both the explicit {@link FrequencyCache#evict} path and the
 * capacity-enforcement path behave the same way, so that evicting one alias key
 * does not leave another alias key referencing a value whose resources have already
 * been released, and so that a value shared by several keys is released exactly once.</p>
 */
public class FrequencyCacheEvictAliasTest extends TestSuiteBase {

	/**
	 * When two keys alias the same value, evicting one must remove the other as
	 * well, matching the documented contract that eviction of one key removes all
	 * keys sharing that value. Otherwise the surviving key still resolves to a
	 * value that has been removed from the reverse cache (and whose listener has
	 * fired), which is a stale, released reference.
	 */
	@Test(timeout = 5000)
	public void evictRemovesAllKeysSharingTheValue() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.5);

		cache.put("k1", "shared");
		cache.put("k2", "shared");

		// Value deduplication collapses both keys onto a single entry.
		Assert.assertEquals("Both keys should share a single value", 1, cache.size());

		cache.evict("k1");

		Assert.assertNull("The evicted key must not be retrievable", cache.get("k1"));
		Assert.assertFalse("Evicting one key must remove all keys sharing that value",
				cache.containsKey("k2"));
		Assert.assertNull("A key sharing the evicted value must not resolve to it",
				cache.get("k2"));
		Assert.assertEquals("No distinct values should remain after evicting the shared value",
				0, cache.size());
	}

	/**
	 * The eviction listener releases the resources a value holds. Once {@code evict}
	 * has fired the listener for a shared value, no remaining key may hand that value
	 * back out — doing so is a use-after-release of whatever the listener destroyed.
	 *
	 * <p>The value is a single deduplicated object, so the listener must fire exactly
	 * once for it regardless of how many keys aliased it. Firing once per alias key
	 * would release (and typically destroy) the same native resources repeatedly.</p>
	 */
	@Test(timeout = 5000)
	public void evictDoesNotLeaveAKeyPointingToAReleasedValue() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.5);

		List<String> released = new ArrayList<>();
		cache.setEvictionListener((key, value) -> released.add(value));

		cache.put("k1", "shared");
		cache.put("k2", "shared");

		cache.evict("k1");

		Assert.assertEquals("A deduplicated value must be released exactly once, "
				+ "not once per aliasing key", 1, released.size());
		Assert.assertEquals("Evicting the shared value must release that value",
				"shared", released.get(0));

		String stillReachable = cache.get("k2");
		Assert.assertNull("No key may return a value whose resources were already released",
				stillReachable);
	}

	/**
	 * Three keys aliasing one value must still release that value exactly once on
	 * explicit eviction, and remove all three keys. This guards the per-value release
	 * contract against any alias count, not just two.
	 */
	@Test(timeout = 5000)
	public void evictReleasesSharedValueOnceForManyAliases() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.5);

		List<String> released = new ArrayList<>();
		cache.setEvictionListener((key, value) -> released.add(value));

		cache.put("k1", "shared");
		cache.put("k2", "shared");
		cache.put("k3", "shared");

		Assert.assertEquals("All three keys should collapse onto a single value", 1, cache.size());

		cache.evict("k2");

		Assert.assertEquals("A value shared by three keys must be released exactly once",
				1, released.size());
		Assert.assertEquals("shared", released.get(0));
		Assert.assertFalse("k1 aliasing the evicted value must be removed", cache.containsKey("k1"));
		Assert.assertFalse("k2 the evicted key must be removed", cache.containsKey("k2"));
		Assert.assertFalse("k3 aliasing the evicted value must be removed", cache.containsKey("k3"));
		Assert.assertEquals("No distinct values should remain", 0, cache.size());
	}

	/**
	 * Capacity enforcement must honour the same contract as explicit eviction: when
	 * the lowest-scored value is shared by several alias keys, enforcing capacity must
	 * remove every alias key and release the value exactly once. The shared value is
	 * driven to eviction by leaving it untouched while a competing value is accessed
	 * repeatedly, so the shared value scores lowest on both recency and frequency and
	 * is the deterministic victim regardless of the frequency bias. "k1"/"k2" alias the
	 * shared value, "k3" holds a second distinct value accessed twice to keep it, and
	 * inserting "k4" with a third distinct value pushes the cache past capacity and
	 * evicts the shared value through the {@code prepareCapacity} call site that the
	 * explicit-{@code evict} tests above do not exercise.
	 */
	@Test(timeout = 5000)
	public void capacityEvictionRemovesAllAliasKeysAndReleasesOnce() {
		int distinctCapacity = 2;
		FrequencyCache<String, String> cache = new FrequencyCache<>(distinctCapacity, 0.5);

		List<String> released = new ArrayList<>();
		cache.setEvictionListener((key, value) -> released.add(value));

		cache.put("k1", "shared");
		cache.put("k2", "shared");
		Assert.assertEquals("Both keys should share a single value", 1, cache.size());

		cache.put("k3", "other");
		Assert.assertEquals("Cache should be at capacity with two distinct values",
				distinctCapacity, cache.size());

		cache.get("k3");
		cache.get("k3");

		cache.put("k4", "new");

		Assert.assertEquals("A value shared by two keys must be released exactly once "
				+ "during capacity eviction", 1, released.size());
		Assert.assertEquals("Capacity eviction must release the lowest-scored shared value",
				"shared", released.get(0));
		Assert.assertFalse("k1 aliasing the evicted value must be removed", cache.containsKey("k1"));
		Assert.assertFalse("k2 aliasing the evicted value must be removed", cache.containsKey("k2"));
		Assert.assertNull("A key sharing the evicted value must not resolve to it", cache.get("k1"));
		Assert.assertTrue("The retained value's key must survive", cache.containsKey("k3"));
		Assert.assertTrue("The newly inserted key must be present", cache.containsKey("k4"));
		Assert.assertEquals("Capacity must hold exactly the surviving values",
				distinctCapacity, cache.size());
	}
}
