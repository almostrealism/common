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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tests that {@link FrequencyCache} iterates its entries in insertion order rather than
 * hash order.
 *
 * <p>Callers rank cache entries and keep ties in iteration order &mdash;
 * {@link io.almostrealism.scope.ExpressionCache#getFrequentExpressions()} sorts by
 * estimated savings, and the order of equal-savings candidates decides which common
 * subexpressions are extracted. Expressions frequently share a hash, and a hash table
 * holding many entries with the same hash orders them by identity, which differs from
 * one run to the next. These tests insert many keys with one shared hash, enough to
 * share a single treeified bucket, and require the cache to report them in the order
 * they were inserted.</p>
 */
public class FrequencyCacheOrderTest extends TestSuiteBase {

	/** Number of colliding keys; well past the point where a hash bucket is treeified. */
	private static final int KEYS = 40;

	/** Cache capacity; large enough that no key is evicted during the test. */
	private static final int CAPACITY = 100;

	/**
	 * {@link FrequencyCache#entriesByFrequency} reports colliding entries in insertion
	 * order, with the frequency each one was accessed.
	 */
	@Test(timeout = 10000)
	public void entriesByFrequencyFollowsInsertionOrder() {
		List<CollidingKey> keys = keys();
		FrequencyCache<CollidingKey, CollidingKey> cache = populate(keys);

		List<Map.Entry<CollidingKey, Integer>> entries =
				cache.entriesByFrequency(f -> true).collect(Collectors.toList());

		Assert.assertEquals(keys, entries.stream().map(Map.Entry::getKey).collect(Collectors.toList()));

		for (int i = 0; i < KEYS; i++) {
			Assert.assertEquals("frequency of key " + i, i % 3, (int) entries.get(i).getValue());
		}
	}

	/**
	 * Filtering by frequency preserves insertion order among the entries that remain,
	 * which is the selection {@code ExpressionCache} makes before ranking.
	 */
	@Test(timeout = 10000)
	public void filteredEntriesFollowInsertionOrder() {
		List<CollidingKey> keys = keys();
		FrequencyCache<CollidingKey, CollidingKey> cache = populate(keys);

		List<CollidingKey> expected = new ArrayList<>();
		for (int i = 0; i < KEYS; i++) {
			if (i % 3 == 2) expected.add(keys.get(i));
		}

		List<CollidingKey> frequent = cache.entriesByFrequency(f -> f > 1)
				.map(Map.Entry::getKey).collect(Collectors.toList());

		Assert.assertEquals(expected, frequent);
	}

	/**
	 * {@link FrequencyCache#forEach} visits colliding keys in insertion order.
	 */
	@Test(timeout = 10000)
	public void forEachFollowsInsertionOrder() {
		List<CollidingKey> keys = keys();
		FrequencyCache<CollidingKey, CollidingKey> cache = populate(keys);

		List<CollidingKey> visited = new ArrayList<>();
		cache.forEach((k, v) -> {
			Assert.assertSame(k, v);
			visited.add(k);
		});

		Assert.assertEquals(keys, visited);
	}

	/**
	 * Creates {@link #KEYS} distinct keys that all share one hash.
	 *
	 * @return the keys, in the order they are to be inserted
	 */
	private static List<CollidingKey> keys() {
		List<CollidingKey> keys = new ArrayList<>();
		for (int i = 0; i < KEYS; i++) {
			keys.add(new CollidingKey(i));
		}
		return keys;
	}

	/**
	 * Inserts each key as its own value, then reads key {@code i} back {@code i % 3}
	 * times so the entries carry distinct, known frequencies.
	 *
	 * @param keys the keys to insert, in order
	 * @return the populated cache
	 */
	private static FrequencyCache<CollidingKey, CollidingKey> populate(List<CollidingKey> keys) {
		FrequencyCache<CollidingKey, CollidingKey> cache = new FrequencyCache<>(CAPACITY, 0.7);
		keys.forEach(k -> cache.put(k, k));

		for (int i = 0; i < keys.size(); i++) {
			for (int j = 0; j < i % 3; j++) {
				Assert.assertSame(keys.get(i), cache.get(keys.get(i)));
			}
		}

		Assert.assertEquals(keys.size(), cache.size());
		return cache;
	}

	/**
	 * A key that is equal only to keys with the same id, but whose hash is the same for
	 * every instance, and which has no natural ordering &mdash; so a hash table can only
	 * order instances by identity.
	 */
	private static final class CollidingKey {
		/** Distinguishes this key from the others. */
		private final int id;

		/**
		 * Creates a key.
		 *
		 * @param id distinguishes this key from the others
		 */
		CollidingKey(int id) {
			this.id = id;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof CollidingKey && ((CollidingKey) o).id == id;
		}

		@Override
		public int hashCode() {
			return 7;
		}

		@Override
		public String toString() {
			return "key" + id;
		}
	}
}
