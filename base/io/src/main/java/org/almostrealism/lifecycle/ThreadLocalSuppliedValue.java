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

package org.almostrealism.lifecycle;

import io.almostrealism.lifecycle.Destroyable;

import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A thread-local variant of {@link SuppliedValue} that maintains separate values for each thread.
 *
 * <p>ThreadLocalSuppliedValue creates and manages a separate value for each thread that
 * accesses it. Values are stored in a {@link WeakHashMap} keyed by thread, allowing
 * automatic cleanup when threads are garbage collected.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Each thread gets its own DecimalFormat instance
 * ThreadLocalSuppliedValue<DecimalFormat> formatter =
 *     new ThreadLocalSuppliedValue<>(() -> new DecimalFormat("##0.00"));
 *
 * // In thread 1:
 * DecimalFormat fmt1 = formatter.getValue();  // Creates new instance for thread 1
 *
 * // In thread 2:
 * DecimalFormat fmt2 = formatter.getValue();  // Creates separate instance for thread 2
 *
 * // Clean up all thread values
 * formatter.destroy();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class provides thread isolation - each thread sees its own value. The
 * underlying WeakHashMap uses thread references as keys, so values are automatically
 * cleaned up when their associated threads are garbage collected.</p>
 *
 * @param <T> the type of value managed per thread
 * @see SuppliedValue
 * @see ThreadLocal
 */
public class ThreadLocalSuppliedValue<T> extends SuppliedValue<T> {
	private WeakHashMap<Thread, T> values;

	/**
	 * Creates a thread-local supplied value with the given supplier.
	 * Each thread will receive its own value created by this supplier.
	 *
	 * @param supplier the supplier to create thread-specific values
	 */
	public ThreadLocalSuppliedValue(Supplier<T> supplier) {
		super(supplier);
	}

	/**
	 * Gets the value for the current thread, creating it if necessary.
	 *
	 * @return the thread-local value
	 */
	@Override
	public T getValue() {
		T v = values == null ? null : values.get(Thread.currentThread());

		if (v == null) {
			if (values == null) values = new WeakHashMap<>();
			v = supplier.get();
			values.put(Thread.currentThread(), v);
		}

		return v;
	}

	/**
	 * Checks if a value is available for the current thread.
	 *
	 * @return true if the current thread has a valid value
	 */
	@Override
	public boolean isAvailable() {
		return values != null && values.get(Thread.currentThread()) != null
				&& (valid == null || valid.test(values.get(Thread.currentThread())));
	}

	/**
	 * Applies a consumer to all thread-local values.
	 * This operates on values from all threads, not just the current one.
	 *
	 * @param consumer the consumer to apply to each value
	 */
	@Override
	public void applyAll(Consumer<T> consumer) {
		if (consumer == null || !isAvailable()) return;

		if (values != null) {
			values.values().stream()
					.filter(Objects::nonNull)
					.forEach(consumer);
		}
	}

	/**
	 * Destroys all thread-local values.
	 * <p>If values implement {@link Destroyable}, their destroy methods are called.
	 * After this method returns, all thread values are cleared.</p>
	 */
	@Override
	public void destroy() {
		if (values != null) {
			values.values().forEach(v -> {
				if (v instanceof Destroyable) ((Destroyable) v).destroy();
			});

			values = null;
		}
	}
}
