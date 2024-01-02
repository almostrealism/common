/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.util;

import java.util.*;
import java.util.stream.Collectors;

public class FrequencyCache<K, V> {
	protected class CacheEntry {
		V value;
		int frequency;
		long time;

		CacheEntry(V value) {
			this.value = value;
			this.frequency = 0;
			this.time = clock;
		}

		void accessed() {
			this.frequency++;
			this.time = clock;
		}
	}

	private long clock = 0;
	private final double frequencyBias;
	private final Map<K, CacheEntry> cache;
	private final Map<V, CacheEntry> reverseCache;
	private final int capacity;

	public FrequencyCache(int capacity, double frequencyBias) {
		if (frequencyBias > 1 || frequencyBias < 0) throw new IllegalArgumentException();

		this.capacity = capacity;
		this.frequencyBias = frequencyBias;
		this.cache = new HashMap<>(capacity);
		this.reverseCache = new HashMap<>(capacity);
	}

	public V get(K key) {
		CacheEntry entry = cache.get(key);
		if (entry == null) {
			return null;
		}

		clock++;
		entry.accessed();
		return entry.value;
	}

	public void put(K key, V value) {
		clock++;

		CacheEntry entry = reverseCache.get(value);

		if (entry == null) {
			prepareCapacity();
			cache.put(key, new CacheEntry(value));
			reverseCache.put(value, cache.get(key));
		} else {
			cache.put(key, entry);
			entry.accessed();
		}
	}

	protected void prepareCapacity() {
		while (reverseCache.size() >= capacity) {
			Map.Entry<V, CacheEntry> ent = Collections.min(reverseCache.entrySet(),
					Comparator.comparing(e -> score(e.getValue())));
			reverseCache.remove(ent.getKey());

			List<K> matchingKeys = cache.entrySet()
					.stream().filter(e -> e.getValue() == ent.getValue())
					.map(Map.Entry::getKey).collect(Collectors.toList());
			matchingKeys.forEach(cache::remove);
		}
	}

	protected double score(CacheEntry entry) {
		long age = clock - entry.time;
		return frequencyBias * entry.frequency + (1 - frequencyBias) * age;
	}
}