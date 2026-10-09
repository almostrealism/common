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
 * Tests for {@link FrequencyCache#evict(Object)} when several keys share a single
 * value through the cache's value-deduplication (reverse map).
 *
 * <p>{@link FrequencyCache} documents that "Eviction of one key removes all keys
 * sharing that value" and that the eviction listener is the only hook for releasing
 * the native resources a value holds. The capacity-eviction path
 * ({@code prepareCapacity}) honours this by removing every key that references the
 * evicted entry. These tests assert that the explicit {@link FrequencyCache#evict}
 * path behaves the same way, so that evicting one alias key does not leave another
 * alias key referencing a value whose resources have already been released.</p>
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
	 */
	@Test(timeout = 5000)
	public void evictDoesNotLeaveAKeyPointingToAReleasedValue() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.5);

		List<String> released = new ArrayList<>();
		cache.setEvictionListener((key, value) -> released.add(value));

		cache.put("k1", "shared");
		cache.put("k2", "shared");

		cache.evict("k1");

		Assert.assertTrue("Evicting the shared value must release it", released.contains("shared"));

		String stillReachable = cache.get("k2");
		Assert.assertNull("No key may return a value whose resources were already released",
				stillReachable);
	}
}
