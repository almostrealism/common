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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
 * cache.setMaxEntries(100);
 *
 * // Create a managed cached value
 * CachedValue<ComputedData> cached = cache.get(args -> expensiveComputation(args));
 *
 * // Access the cached value - timestamps are tracked automatically
 * ComputedData result = cached.evaluate(inputArgs);
 * }</pre>
 *
 * <h2>Eviction Strategy</h2>
 * <p>{@link #setMaxEntries(int)} bounds the cache: after every access the manager clears
 * and stops tracking its least recently used entries until the bound holds again.</p>
 *
 * @param <T> the type of values stored in the cache
 * @see CachedValue
 * @see Evaluable
 */
public class CacheManager<T> {
	/**
	 * Map of cached values to their last access timestamp (milliseconds since epoch).
	 * Used for LRU ordering and eviction decisions. Every access synchronizes on this
	 * manager: one owner may release its entries while others are computing theirs on
	 * render threads.
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
	 * Maximum number of available entries retained after each access, or
	 * {@code 0} for an unbounded cache. See {@link #setMaxEntries(int)}.
	 */
	private int maxEntries;

	/**
	 * Creates a new cache manager with an empty cache.
	 */
	public CacheManager() {
		values = new HashMap<>();
	}

	/**
	 * Sets a listener that will be invoked on each cache access.
	 *
	 * <p>The listener runs after this manager has applied its own
	 * {@link #setMaxEntries(int) entry bound}.</p>
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
	 * Bounds this cache to the given number of available entries. After every access
	 * the least recently used entries beyond the bound are cleared, invoking the
	 * {@link #setClear(Consumer) clear consumer}, and dropped from the access-time map.
	 *
	 * <p>Dropping matters as much as clearing: otherwise the map would keep every entry
	 * ever evicted, and with it the source {@link Evaluable} and everything its closure
	 * captured, growing without bound for as long as new entries keep arriving. It is
	 * also safe: an evicted entry that is evaluated again recomputes its value and
	 * re-registers itself.</p>
	 *
	 * @param maxEntries the maximum number of entries to retain, or {@code 0} for no bound
	 */
	public void setMaxEntries(int maxEntries) {
		if (maxEntries < 0) {
			throw new IllegalArgumentException("maxEntries must not be negative: " + maxEntries);
		}

		this.maxEntries = maxEntries;
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
	public synchronized List<CachedValue<T>> getCachedOrdered() {
		List<CachedValue<T>> values = new ArrayList<>(this.values.keySet().stream()
				.filter(CachedValue::isAvailable).collect(Collectors.toList()));
		values.sort(Comparator.comparingLong(this.values::get));
		return values;
	}

	/**
	 * Creates a new managed {@link CachedValue} that wraps the given evaluable source.
	 *
	 * <p>The returned cached value is configured with this manager's clear consumer
	 * and validation predicate. When evaluated, the cached value will:</p>
	 * <ol>
	 *   <li>Record its access timestamp in this manager</li>
	 *   <li>Evict the least recently used entries beyond the {@link #setMaxEntries(int) bound}</li>
	 *   <li>Invoke the access listener (if configured)</li>
	 *   <li>Delegate to the source evaluable</li>
	 * </ol>
	 *
	 * @param source the evaluable that produces the cached value
	 * @return a new managed cached value
	 */
	public CachedValue<T> get(Evaluable<T> source) {
		CachedValue<T> v = new CachedValue<>(null, clear);
		v.setValid(valid);
		v.setEvaluable(args -> {
			synchronized (this) {
				values.put(v, System.currentTimeMillis());
			}

			evictBeyondMaxEntries();
			if (access != null) access.run();
			return source.evaluate(args);
		});
		return v;
	}

	/**
	 * Clears and drops the least recently used available entries until no more than
	 * {@link #setMaxEntries(int) maxEntries} remain. Does nothing for an unbounded cache.
	 */
	private void evictBeyondMaxEntries() {
		if (maxEntries <= 0) return;

		List<CachedValue<T>> ordered = getCachedOrdered();
		int count = ordered.size() - maxEntries;

		for (int i = 0; i < count; i++) {
			CachedValue<T> evicted = ordered.get(i);
			evicted.clear();
			drop(evicted);
		}
	}

	/**
	 * Drops all entries from the internal access-time map without invoking any
	 * entry's clear consumer.
	 *
	 * <p>Every tracked entry holds a strong reference to its source
	 * {@link Evaluable} and, through it, to any objects captured in its closure.
	 * This method drops those references for every entry at once, so the captured
	 * graph becomes eligible for garbage collection.</p>
	 *
	 * <p>The clear consumer (configured via {@link #setClear(Consumer)}) is
	 * intentionally <em>not</em> invoked here. The clear consumer is designed
	 * for active eviction during normal cache use, where the cached value will
	 * never be requested again. A cache-wide drop, in contrast, may be triggered
	 * between independent rendering scenes that share underlying memory through
	 * delegate chains; eagerly destroying every cached value would cascade into
	 * still-live state. Native memory referenced by the dropped values is
	 * released by ordinary GC + finalizer paths.</p>
	 */
	public synchronized void dropAll() {
		values.clear();
	}

	/**
	 * Drops a single entry from the internal access-time map without invoking its
	 * clear consumer. This is the per-entry form of {@link #dropAll()}, for an owner
	 * that knows exactly which entries it created and is releasing them while other
	 * owners' entries in the same cache must remain tracked for eviction.
	 *
	 * <p>As with {@link #dropAll()}, the value itself is not destroyed. If the dropped
	 * value is evaluated again it re-registers itself on its next computation.</p>
	 *
	 * @param value the cached value to stop tracking
	 * @return {@code true} if the value was tracked by this manager
	 */
	public synchronized boolean drop(CachedValue<T> value) {
		return values.remove(value) != null;
	}

	/**
	 * Returns whether the given value is currently tracked by this manager, that is,
	 * whether it has been computed through this manager and not since dropped.
	 *
	 * @param value the cached value to look up
	 * @return {@code true} if the value is held in the internal access-time map
	 */
	public synchronized boolean contains(CachedValue<T> value) {
		return values.containsKey(value);
	}
}
