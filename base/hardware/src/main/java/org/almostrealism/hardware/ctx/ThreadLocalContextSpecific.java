/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.ctx;

import org.almostrealism.lifecycle.ThreadLocalSuppliedValue;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-local implementation of {@link ContextSpecific} that creates {@link ThreadLocalSuppliedValue} instances.
 *
 * <p>{@link ThreadLocalContextSpecific} ensures that each thread accessing the context-specific value
 * gets its own independent instance. This is essential for thread-safety when multiple threads work
 * with the same context or when values must not be shared between threads.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Thread-local cache (each thread gets its own cache)
 * ContextSpecific<Cache> cacheProvider = new ThreadLocalContextSpecific<>(
 *     () -> new Cache(1000),
 *     cache -> cache.clear()
 * );
 * cacheProvider.init();
 *
 * // Thread 1 accesses cache
 * Cache cache1 = cacheProvider.getValue();  // Creates new cache for thread 1
 *
 * // Thread 2 accesses cache
 * Cache cache2 = cacheProvider.getValue();  // Creates different cache for thread 2
 *
 * assert cache1 != cache2;  // Each thread has its own instance
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Unlike {@link DefaultContextSpecific}, this implementation is inherently thread-safe because
 * each thread maintains its own value via {@link ThreadLocalSuppliedValue}. No synchronization
 * is required for concurrent access.</p>
 *
 * <h2>Common Use Cases</h2>
 *
 * <ul>
 *   <li><b>Thread-Local Caches</b>: Each thread maintains its own cache without contention</li>
 *   <li><b>Thread-Local Buffers</b>: Reusable buffers that must not be shared between threads</li>
 *   <li><b>Thread-Local State</b>: Per-thread state that varies by context (e.g., profiling data)</li>
 *   <li><b>Thread-Local Connections</b>: Thread-specific resource handles</li>
 * </ul>
 *
 * <h2>Memory Considerations</h2>
 *
 * <p><b>Warning:</b> Thread-local values can lead to memory leaks if threads are not properly cleaned up.
 * Ensure that:</p>
 * <ul>
 *   <li>Disposal logic is provided to clean up thread-local values</li>
 *   <li>{@link #destroy()} is called when the context-specific value is no longer needed</li>
 *   <li>Long-lived thread pools have appropriate cleanup mechanisms</li>
 * </ul>
 *
 * @param <T> Type of context-specific value
 * @see ContextSpecific
 * @see DefaultContextSpecific
 * @see ThreadLocalSuppliedValue
 */
public class ThreadLocalContextSpecific<T> extends ContextSpecific<T> {
	/**
	 * Constructs a thread-local context-specific value with the given supplier.
	 *
	 * @param supply Supplier to create new instances for each thread in each context
	 */
	public ThreadLocalContextSpecific(Supplier<T> supply) {
		super(supply);
	}

	/**
	 * Constructs a thread-local context-specific value with the given supplier and disposal logic.
	 *
	 * @param supply Supplier to create new instances for each thread in each context
	 * @param disposal Consumer to clean up values when contexts are destroyed
	 */
	public ThreadLocalContextSpecific(Supplier<T> supply, Consumer<T> disposal) {
		super(supply, disposal);
	}

	/**
	 * Creates a new {@link ThreadLocalSuppliedValue} for a context.
	 *
	 * <p>Each thread accessing this value will get its own independent instance.</p>
	 *
	 * @param supply Supplier to create the value
	 * @return A new ThreadLocalSuppliedValue
	 */
	@Override
	public ThreadLocalSuppliedValue createValue(Supplier supply) {
		return new ThreadLocalSuppliedValue(supply);
	}
}
