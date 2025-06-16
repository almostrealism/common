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

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
			FrequencyCache.this.count++;
			this.frequency++;
			this.time = clock;
		}
	}

	private long count = 0;
	private long clock = 0;
	private final double frequencyBias;
	private final Map<K, CacheEntry> cache;
	private final Map<V, CacheEntry> reverseCache;
	private final int capacity;

	private BiConsumer<K, V> evictionListener;

	public FrequencyCache() {
		this(200, 0.5);
	}

	public FrequencyCache(int capacity, double frequencyBias) {
		if (frequencyBias > 1 || frequencyBias < 0) throw new IllegalArgumentException();

		this.capacity = capacity;
		this.frequencyBias = frequencyBias;
		this.cache = new HashMap<>(capacity);
		this.reverseCache = new HashMap<>(capacity);
	}

	public void setEvictionListener(BiConsumer<K, V> listener) {
		this.evictionListener = listener;
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

	public boolean containsKey(K key) {
		return cache.containsKey(key);
	}

	public boolean isEmpty() { return cache.isEmpty(); }

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

	public V computeIfAbsent(K key, Supplier<V> supplier) {
		return computeIfAbsent(key, k -> supplier.get());
	}

	public V computeIfAbsent(K key, Function<K, V> supplier) {
		if (cache.containsKey(key)) {
			return get(key);
		}

		V value = supplier.apply(key);
		put(key, value);
		return value;
	}

	public void evict(K key) {
		CacheEntry e = cache.remove(key);
		if (e == null) return;

		reverseCache.remove(e.value);

		if (evictionListener != null) {
			evictionListener.accept(key, e.value);
		}
	}

	public Stream<V> valuesByFrequency(IntPredicate frequencyFilter) {
		return cache.values().stream()
				.filter(e -> frequencyFilter.test(e.frequency))
				.sorted(Comparator.comparing(v -> v.frequency))
				.map(e -> e.value);
	}

	public void forEach(BiConsumer<K, V> consumer) {
		cache.forEach((k, v) -> consumer.accept(k, v.value));
	}

	protected void prepareCapacity() {
		while (reverseCache.size() >= capacity) {
			Map.Entry<V, CacheEntry> ent = Collections.min(reverseCache.entrySet(),
					Comparator.comparing(e -> score(e.getValue())));
			reverseCache.remove(ent.getKey());

			List<K> matchingKeys = cache.entrySet()
					.stream().filter(e -> e.getValue() == ent.getValue())
					.map(e -> {
						if (evictionListener != null) {
							evictionListener.accept(e.getKey(), e.getValue().value);
						}

						return e;
					})
					.map(Map.Entry::getKey).collect(Collectors.toList());
			matchingKeys.forEach(cache::remove);
		}
	}

	protected double score(CacheEntry entry) {
		double age = (clock - entry.time) / (double) clock;
		double f = entry.frequency / (double) count;
		return frequencyBias * f + (1 - frequencyBias) * (1 - age);
	}
}