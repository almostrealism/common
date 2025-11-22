/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Manages a collection of {@link CachedValue} instances with LRU (Least Recently Used)
 * eviction support and access tracking.
 *
 * <p>CacheManager provides a centralized way to manage cached computations, tracking
 * when each cached value was last accessed. This enables eviction strategies such as
 * removing the least recently used entries when the cache exceeds a maximum size.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Automatic access timestamp tracking for all cached values</li>
 *   <li>LRU ordering for eviction decisions</li>
 *   <li>Configurable validation predicate for cache entries</li>
 *   <li>Access event listener support</li>
 *   <li>Factory method for creating managed cached values</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * CacheManager<ComputedData> cache = new CacheManager<>();
 *
 * // Configure maximum entries with automatic eviction
 * cache.setAccessListener(CacheManager.maxCachedEntries(cache, 100));
 *
 * // Create a managed cached value
 * CachedValue<ComputedData> cached = cache.get(args -> expensiveComputation(args));
 *
 * // Access the cached value - timestamps are tracked automatically
 * ComputedData result = cached.evaluate(inputArgs);
 * }</pre>
 *
 * <h2>Eviction Strategy</h2>
 * <p>The static method {@link #maxCachedEntries(CacheManager, int)} provides an eviction
 * strategy that automatically clears the oldest cached entries when the cache size
 * exceeds the specified maximum. This is typically set as the access listener.</p>
 *
 * @param <T> the type of values stored in the cache
 * @see CachedValue
 * @see Evaluable
 */
public class CacheManager<T> {
	/**
	 * Map of cached values to their last access timestamp (milliseconds since epoch).
	 * Used for LRU ordering and eviction decisions.
	 */
	private HashMap<CachedValue<T>, Long> values;

	/**
	 * Optional listener invoked on each cache access.
	 * Typically used to trigger eviction checks.
	 */
	private Runnable access;

	/**
	 * Consumer for clearing cached values.
	 * Passed to newly created {@link CachedValue} instances.
	 */
	private Consumer<T> clear;

	/**
	 * Validation predicate for cache entries.
	 * Passed to newly created {@link CachedValue} instances.
	 */
	private Predicate<T> valid;

	/**
	 * Creates a new cache manager with an empty cache.
	 */
	public CacheManager() {
		values = new HashMap<>();
	}

	/**
	 * Sets a listener that will be invoked on each cache access.
	 *
	 * <p>This is commonly used with {@link #maxCachedEntries(CacheManager, int)}
	 * to implement automatic eviction when the cache grows too large.</p>
	 *
	 * @param listener the access listener, or {@code null} to disable
	 */
	public void setAccessListener(Runnable listener) {
		this.access = listener;
	}

	/**
	 * Sets the consumer that will be called when clearing cached values.
	 *
	 * <p>This consumer is passed to all {@link CachedValue} instances created
	 * by this manager, enabling custom cleanup logic when values are evicted.</p>
	 *
	 * @param clear the clear consumer, or {@code null} for default behavior
	 */
	public void setClear(Consumer<T> clear) {
		this.clear = clear;
	}

	/**
	 * Sets the validation predicate for cached values.
	 *
	 * <p>This predicate is passed to all {@link CachedValue} instances created
	 * by this manager. A cached value that fails validation will be recreated
	 * on next access.</p>
	 *
	 * @param valid the validation predicate, or {@code null} to skip validation
	 */
	public void setValid(Predicate<T> valid) {
		this.valid = valid;
	}

	/**
	 * Returns all available cached values, ordered by last access time (oldest first).
	 *
	 * <p>Only cached values that are currently available (have been populated and
	 * pass validation) are included. The ordering enables LRU eviction strategies
	 * where the first entries in the list are candidates for removal.</p>
	 *
	 * @return a list of cached values sorted by access time, oldest first
	 */
	public List<CachedValue<T>> getCachedOrdered() {
		List<CachedValue<T>> values = new ArrayList<>(this.values.keySet().stream()
				.filter(CachedValue::isAvailable).collect(Collectors.toList()));
		values.sort((a, b) -> (int) (this.values.get(a) - this.values.get(b)));
		return values;
	}

	/**
	 * Creates a new managed {@link CachedValue} that wraps the given evaluable source.
	 *
	 * <p>The returned cached value is configured with this manager's clear consumer
	 * and validation predicate. When evaluated, the cached value will:</p>
	 * <ol>
	 *   <li>Record its access timestamp in this manager</li>
	 *   <li>Invoke the access listener (if configured)</li>
	 *   <li>Delegate to the source evaluable</li>
	 * </ol>
	 *
	 * <p>The access listener invocation enables automatic eviction when used with
	 * {@link #maxCachedEntries(CacheManager, int)}.</p>
	 *
	 * @param source the evaluable that produces the cached value
	 * @return a new managed cached value
	 */
	public CachedValue<T> get(Evaluable<T> source) {
		CachedValue<T> v = new CachedValue<>(null, clear);
		v.setValid(valid);
		v.setEvaluable(args -> {
			values.put(v, System.currentTimeMillis());
			if (access != null) access.run();
			return source.evaluate(args);
		});
		return v;
	}

	/**
	 * Creates an eviction strategy that limits the cache to a maximum number of entries.
	 *
	 * <p>Returns a {@link Runnable} that, when invoked, checks if the cache exceeds
	 * the maximum size and clears the oldest entries (by access time) until the
	 * cache is within bounds.</p>
	 *
	 * <h2>Usage</h2>
	 * <pre>{@code
	 * CacheManager<Data> cache = new CacheManager<>();
	 * cache.setAccessListener(CacheManager.maxCachedEntries(cache, 100));
	 * }</pre>
	 *
	 * @param <T> the type of values in the cache
	 * @param mgr the cache manager to apply eviction to
	 * @param max the maximum number of entries to retain
	 * @return a runnable that enforces the maximum entry limit
	 */
	public static <T> Runnable maxCachedEntries(CacheManager<T> mgr, int max) {
		return () -> {
			List<CachedValue<T>> ordered = mgr.getCachedOrdered();

			int count = ordered.size() - max;
			if (count <= 0) return;

			for (int i = 0; i < count; i++) ordered.get(i).clear();
			// System.out.println("CacheManager: Cleared " + count + " cached values");
		};
	}
}
