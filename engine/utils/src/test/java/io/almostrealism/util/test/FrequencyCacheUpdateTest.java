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
 * Tests for {@link FrequencyCache} behaviour when an existing key is updated to
 * a new value. Updating a key must forget the value it previously held: the old
 * value has to be released so it neither inflates {@link FrequencyCache#size()}
 * nor leaks the native resources the eviction listener is responsible for
 * cleaning up.
 */
public class FrequencyCacheUpdateTest extends TestSuiteBase {

	/**
	 * Re-putting an existing key with a new value must not retain the old value.
	 *
	 * <p>The cache documents that the number of keys is always at least the
	 * number of distinct values (aliases mean values &le; keys). After a single
	 * key is updated to a new value there is exactly one key and therefore at
	 * most one reachable value, so {@link FrequencyCache#size()} must be 1.</p>
	 */
	@Test(timeout = 5000)
	public void updatingKeyReleasesOldValue() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.5);

		cache.put("k", "v1");
		cache.put("k", "v2");

		Assert.assertEquals("v2", cache.get("k"));
		Assert.assertEquals("Updating a key must not retain the previous value",
				1, cache.size());
	}

	/**
	 * The old value displaced by a key update must be reported to the eviction
	 * listener, since that listener is the cache's only hook for releasing the
	 * native resources a value holds.
	 */
	@Test(timeout = 5000)
	public void updatingKeyNotifiesEvictionListenerForOldValue() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.5);

		List<String> evicted = new ArrayList<>();
		cache.setEvictionListener((key, value) -> evicted.add(value));

		cache.put("k", "v1");
		cache.put("k", "v2");

		Assert.assertTrue("Displaced value v1 must be reported to the eviction listener",
				evicted.contains("v1"));
		Assert.assertFalse("The current value v2 must not be evicted",
				evicted.contains("v2"));
	}

	/**
	 * Repeatedly updating a single key under capacity must not grow the cache
	 * without bound. Only the most recent value is reachable, so the distinct
	 * value count stays at 1 regardless of how many updates occur.
	 */
	@Test(timeout = 5000)
	public void repeatedUpdatesDoNotAccumulateValues() {
		FrequencyCache<String, String> cache = new FrequencyCache<>(10, 0.5);

		for (int i = 0; i < 8; i++) {
			cache.put("k", "v" + i);
		}

		Assert.assertEquals("v7", cache.get("k"));
		Assert.assertEquals("Only the latest value should remain reachable",
				1, cache.size());
	}
}
