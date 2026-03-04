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

/**
 * A frequency-biased eviction cache that combines access frequency and recency
 * to determine which entries to evict when capacity is exceeded.
 *
 * <p>{@link FrequencyCache} is a hybrid LFU/LRU cache that uses a configurable
 * {@code frequencyBias} parameter to blend two eviction signals:</p>
 * <ul>
 *   <li><strong>Frequency:</strong> How often an entry has been accessed relative
 *       to total accesses</li>
 *   <li><strong>Recency:</strong> How recently the entry was last accessed
 *       relative to the logical clock</li>
 * </ul>
 *
 * <p>The eviction score for each entry is computed as:</p>
 * <pre>
 * score = frequencyBias * (frequency / totalAccesses)
 *       + (1 - frequencyBias) * (1 - age)
 * </pre>
 * <p>where {@code age = (clock - lastAccessTime) / clock}. Higher scores
 * indicate more valuable entries; the entry with the lowest score is evicted
 * first.</p>
 *
 * <h2>Primary Usage: Instruction Set Caching</h2>
 *
 * <p>{@link FrequencyCache} is used by
 * {@link org.almostrealism.hardware.DefaultComputer} to cache
 * {@link org.almostrealism.hardware.instructions.ScopeInstructionsManager}
 * instances indexed by computation signature strings. This avoids redundant
 * kernel compilation when multiple operations share the same structural
 * signature. The typical configuration is:</p>
 *
 * <pre>{@code
 * FrequencyCache<String, ScopeInstructionsManager<ScopeSignatureExecutionKey>>
 *     instructionsCache = new FrequencyCache<>(500, 0.4);
 * instructionsCache.setEvictionListener((key, mgr) -> mgr.destroy());
 * }</pre>
 *
 * <p>With {@code capacity=500} and {@code frequencyBias=0.4}, the cache holds
 * up to 500 compiled instruction managers. Eviction favors entries that are
 * both infrequently accessed (40% weight) and old (60% weight). Evicted
 * managers have their native resources destroyed via the eviction listener.</p>
 *
 * <h2>Value Deduplication</h2>
 *
 * <p>{@link FrequencyCache} maintains a reverse map from values to cache
 * entries. When the same value is inserted under a different key, both keys
 * share the same {@link CacheEntry}, ensuring:</p>
 * <ul>
 *   <li>Frequency tracking is unified across all keys pointing to the same value</li>
 *   <li>Memory usage is reduced by not duplicating cache metadata</li>
 *   <li>Eviction of one key removes all keys sharing that value</li>
 * </ul>
 *
 * <h2>Eviction Listener</h2>
 *
 * <p>An optional {@link BiConsumer} eviction listener is invoked for every
 * key removed during capacity enforcement. This enables resource cleanup
 * (e.g., destroying compiled native code when an instruction manager is
 * evicted).</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The {@link #prepareCapacity()} method is {@code synchronized} to prevent
 * concurrent evictions. However, individual {@link #get(Object)} and
 * {@link #put(Object, Object)} calls are <strong>not</strong> synchronized.
 * Callers that require fully thread-safe operation must provide external
 * synchronization.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <pre>{@code
 * // Create cache with capacity 100, 50% frequency bias
 * FrequencyCache<String, CompiledKernel> cache = new FrequencyCache<>(100, 0.5);
 *
 * // Set eviction listener for resource cleanup
 * cache.setEvictionListener((sig, kernel) -> kernel.destroy());
 *
 * // Insert or retrieve entries
 * CompiledKernel kernel = cache.computeIfAbsent(signature, () -> compile(scope));
 *
 * // Query by frequency
 * Stream<CompiledKernel> hotKernels = cache.valuesByFrequency(freq -> freq > 10);
 * }</pre>
 *
 * @param <K> The key type
 * @param <V> The value type
 * @see org.almostrealism.hardware.DefaultComputer
 * @see org.almostrealism.hardware.instructions.ScopeInstructionsManager
 */
public class FrequencyCache<K, V> {
	/**
	 * Internal cache entry that tracks a value, its access frequency,
	 * and the logical clock time of its last access.
	 *
	 * <p>Multiple keys may share the same {@link CacheEntry} instance
	 * when they map to the same value (via the reverse cache). This
	 * ensures that frequency tracking is unified across aliases.</p>
	 */
	protected class CacheEntry {
		/** The cached value. */
		V value;

		/** The number of times this entry has been accessed. */
		int frequency;

		/** The logical clock time of the most recent access. */
		long time;

		/**
		 * Creates a new cache entry with frequency zero and the
		 * current logical clock time.
		 *
		 * @param value the value to cache
		 */
		CacheEntry(V value) {
			this.value = value;
			this.frequency = 0;
			this.time = clock;
		}

		/**
		 * Records an access to this entry by incrementing the global
		 * access count, the entry frequency, and updating the last
		 * access time to the current clock value.
		 */
		void accessed() {
			FrequencyCache.this.count++;
			this.frequency++;
			this.time = clock;
		}
	}

	/** Total number of accesses across all entries (used for frequency normalization). */
	private long count = 0;

	/** Monotonically increasing logical clock, incremented on each get or put. */
	private long clock = 0;

	/**
	 * Weight given to access frequency in the eviction score (range 0.0 to 1.0).
	 * The remaining weight {@code (1 - frequencyBias)} is given to recency.
	 * A value of 0.0 produces pure LRU behavior; 1.0 produces pure LFU behavior.
	 */
	private final double frequencyBias;

	/** Primary map from keys to cache entries. */
	private final Map<K, CacheEntry> cache;

	/**
	 * Reverse map from values to cache entries. Enables value deduplication
	 * and is used to find the lowest-scoring entry during eviction.
	 */
	private final Map<V, CacheEntry> reverseCache;

	/** Maximum number of distinct values the cache will hold. */
	private final int capacity;

	/**
	 * Optional listener invoked for each key-value pair removed during eviction.
	 * The listener receives the evicted key and its associated value.
	 */
	private BiConsumer<K, V> evictionListener;

	/**
	 * Creates a cache with default capacity of 200 and frequency bias of 0.5.
	 */
	public FrequencyCache() {
		this(200, 0.5);
	}

	/**
	 * Creates a cache with the specified capacity and frequency bias.
	 *
	 * @param capacity      the maximum number of distinct values to hold
	 * @param frequencyBias weight for frequency in eviction scoring (0.0 to 1.0);
	 *                      0.0 is pure LRU, 1.0 is pure LFU
	 * @throws IllegalArgumentException if frequencyBias is outside [0.0, 1.0]
	 */
	public FrequencyCache(int capacity, double frequencyBias) {
		if (frequencyBias > 1 || frequencyBias < 0) throw new IllegalArgumentException();

		this.capacity = capacity;
		this.frequencyBias = frequencyBias;
		this.cache = new HashMap<>(capacity);
		this.reverseCache = new HashMap<>(capacity);
	}

	/**
	 * Registers a listener to be notified when entries are evicted.
	 *
	 * <p>The listener is invoked for each key removed during capacity enforcement
	 * in {@link #prepareCapacity()}. It is also invoked during explicit
	 * {@link #evict(Object)} calls.</p>
	 *
	 * @param listener the eviction listener, or null to disable notifications
	 */
	public void setEvictionListener(BiConsumer<K, V> listener) {
		this.evictionListener = listener;
	}

	/**
	 * Retrieves the value associated with the given key, updating access
	 * frequency and the logical clock.
	 *
	 * @param key the key to look up
	 * @return the cached value, or {@code null} if not present
	 */
	public V get(K key) {
		CacheEntry entry = cache.get(key);
		if (entry == null) {
			return null;
		}

		clock++;
		entry.accessed();
		return entry.value;
	}

	/**
	 * Returns whether the cache contains a mapping for the given key.
	 *
	 * @param key the key to test
	 * @return {@code true} if the key is present in the cache
	 */
	public boolean containsKey(K key) {
		return cache.containsKey(key);
	}

	/**
	 * Returns whether the cache contains no entries.
	 *
	 * @return {@code true} if the cache is empty
	 */
	public boolean isEmpty() { return cache.isEmpty(); }

	/**
	 * Returns whether the cache has reached its capacity.
	 *
	 * @return {@code true} if the number of distinct values equals or exceeds capacity
	 */
	public boolean isFull() {
		return size() >= capacity;
	}

	/**
	 * Returns the number of distinct values currently in the cache.
	 *
	 * <p>Note that the number of keys may be larger than the number of values
	 * when multiple keys map to the same value via the reverse cache.</p>
	 *
	 * @return the number of distinct cached values
	 */
	public int size() { return reverseCache.size(); }

	/**
	 * Inserts or updates a key-value mapping in the cache.
	 *
	 * <p>If the value already exists in the cache (via the reverse map), the
	 * key is associated with the existing {@link CacheEntry} and its access
	 * frequency is incremented. Otherwise, capacity is confirmed (potentially
	 * evicting the lowest-scored entry) and a new entry is created.</p>
	 *
	 * @param key   the key
	 * @param value the value to associate with the key
	 */
	public void put(K key, V value) {
		clock++;

		CacheEntry entry = reverseCache.get(value);

		if (entry == null) {
			confirmCapacity();
			cache.put(key, new CacheEntry(value));
			reverseCache.put(value, cache.get(key));
		} else {
			cache.put(key, entry);
			entry.accessed();
		}
	}

	/**
	 * Returns the value for the given key, computing it from the supplier
	 * if absent.
	 *
	 * @param key      the key
	 * @param supplier supplies the value if the key is not present
	 * @return the existing or newly computed value
	 */
	public V computeIfAbsent(K key, Supplier<V> supplier) {
		return computeIfAbsent(key, k -> supplier.get());
	}

	/**
	 * Returns the value for the given key, computing it from the function
	 * if absent.
	 *
	 * <p>If the key is already in the cache, returns the cached value
	 * (updating access frequency). Otherwise, applies the function to
	 * compute a value, inserts it, and returns it.</p>
	 *
	 * @param key      the key
	 * @param supplier function that computes the value from the key
	 * @return the existing or newly computed value
	 */
	public V computeIfAbsent(K key, Function<K, V> supplier) {
		if (cache.containsKey(key)) {
			return get(key);
		}

		V value = supplier.apply(key);
		put(key, value);
		return value;
	}

	/**
	 * Explicitly removes the entry for the given key from the cache.
	 *
	 * <p>If the key is present, its value is also removed from the reverse
	 * cache and the eviction listener is invoked.</p>
	 *
	 * @param key the key to remove
	 */
	public void evict(K key) {
		CacheEntry e = cache.remove(key);
		if (e == null) return;

		reverseCache.remove(e.value);

		if (evictionListener != null) {
			evictionListener.accept(key, e.value);
		}
	}

	/**
	 * Returns a stream of values filtered and sorted by access frequency.
	 *
	 * <p>Useful for inspecting cache contents, e.g., finding hot entries:</p>
	 * <pre>{@code
	 * Stream<V> hot = cache.valuesByFrequency(freq -> freq > 10);
	 * }</pre>
	 *
	 * @param frequencyFilter predicate to filter entries by access frequency
	 * @return a stream of values sorted by ascending frequency
	 */
	public Stream<V> valuesByFrequency(IntPredicate frequencyFilter) {
		return cache.values().stream()
				.filter(e -> frequencyFilter.test(e.frequency))
				.sorted(Comparator.comparing(v -> v.frequency))
				.map(e -> e.value);
	}

	/**
	 * Returns a stream of value-frequency pairs filtered by frequency.
	 *
	 * @param frequencyFilter predicate to filter entries by access frequency
	 * @return a stream of map entries mapping values to their frequencies
	 */
	public Stream<Map.Entry<V, Integer>> entriesByFrequency(IntPredicate frequencyFilter) {
		return cache.values().stream()
				.filter(e -> frequencyFilter.test(e.frequency))
				.map(e -> Map.entry(e.value, e.frequency));
	}

	/**
	 * Iterates over all key-value pairs in the cache.
	 *
	 * @param consumer the action to perform for each key-value pair
	 */
	public void forEach(BiConsumer<K, V> consumer) {
		cache.forEach((k, v) -> consumer.accept(k, v.value));
	}

	/**
	 * Checks whether eviction is needed and triggers it if the cache
	 * is at or above capacity.
	 */
	protected void confirmCapacity() {
		if (reverseCache.size() < capacity) return;

		prepareCapacity();
	}

	/**
	 * Evicts the lowest-scored entries until the cache is below capacity.
	 *
	 * <p>This method is {@code synchronized} to prevent concurrent evictions.
	 * For each eviction, the entry with the lowest {@link #score(CacheEntry)}
	 * is removed from the reverse cache. All keys in the primary cache that
	 * reference the same entry are also removed, with the eviction listener
	 * invoked for each.</p>
	 */
	protected synchronized void prepareCapacity() {
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

	/**
	 * Computes the eviction score for a cache entry.
	 *
	 * <p>The score blends access frequency and recency using the
	 * {@link #frequencyBias} parameter:</p>
	 * <pre>
	 * score = frequencyBias * (frequency / totalAccesses)
	 *       + (1 - frequencyBias) * (1 - age)
	 * </pre>
	 * <p>where {@code age = (clock - lastAccessTime) / clock}. Entries with
	 * higher scores are considered more valuable and are evicted last.</p>
	 *
	 * @param entry the cache entry to score
	 * @return the eviction score (higher = more valuable)
	 */
	protected double score(CacheEntry entry) {
		double age = (clock - entry.time) / (double) clock;
		double f = entry.frequency / (double) count;
		return frequencyBias * f + (1 - frequencyBias) * (1 - age);
	}
}