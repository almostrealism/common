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

import org.almostrealism.lifecycle.SuppliedValue;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Default implementation of {@link ContextSpecific} that creates standard {@link SuppliedValue} instances.
 *
 * <p>{@link DefaultContextSpecific} extends the base {@link ContextSpecific} pattern with optional
 * validation support. Each context gets its own {@link SuppliedValue}, with optional validation to
 * determine if cached values are still valid.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Simple context-specific value
 * ContextSpecific<Cache> cacheProvider = new DefaultContextSpecific<>(
 *     () -> new Cache(1000)
 * );
 * cacheProvider.init();
 *
 * // With disposal logic
 * ContextSpecific<ThreadPool> poolProvider = new DefaultContextSpecific<>(
 *     () -> new ThreadPool(4),
 *     pool -> pool.shutdown()
 * );
 * poolProvider.init();
 *
 * // With validation predicate
 * DefaultContextSpecific<Config> configProvider = new DefaultContextSpecific<>(
 *     () -> loadConfig()
 * );
 * configProvider.setValid(config -> config.timestamp > lastUpdate);
 * configProvider.init();
 * }</pre>
 *
 * <h2>Validation Support</h2>
 *
 * <p>If a validation {@link Predicate} is set via {@link #setValid}, each {@link SuppliedValue}
 * will check if its cached value is still valid before returning it. If invalid, a new value
 * is created from the supplier.</p>
 *
 * @param <T> Type of context-specific value
 * @see ContextSpecific
 * @see ThreadLocalContextSpecific
 * @see SuppliedValue
 */
public class DefaultContextSpecific<T> extends ContextSpecific<T> {
	private Predicate<T> valid;

	/**
	 * Constructs a context-specific value with the given supplier.
	 *
	 * @param supply Supplier to create new instances for each context
	 */
	public DefaultContextSpecific(Supplier<T> supply) {
		super(supply);
	}

	/**
	 * Constructs a context-specific value with the given supplier and disposal logic.
	 *
	 * @param supply Supplier to create new instances for each context
	 * @param disposal Consumer to clean up values when contexts are destroyed
	 */
	public DefaultContextSpecific(Supplier<T> supply, Consumer<T> disposal) {
		super(supply, disposal);
	}

	/**
	 * Sets a validation predicate to determine if cached values are still valid.
	 *
	 * <p>If set, the {@link SuppliedValue} will re-evaluate the supplier whenever the
	 * predicate returns false for the cached value.</p>
	 *
	 * @param valid Predicate to check if a value is still valid
	 */
	public void setValid(Predicate<T> valid) {
		this.valid = valid;
	}

	/**
	 * Creates a new {@link SuppliedValue} with optional validation support.
	 *
	 * @param supply Supplier to create the value
	 * @return A new SuppliedValue configured with the validation predicate (if set)
	 */
	@Override
	public SuppliedValue createValue(Supplier supply) {
		SuppliedValue v = new SuppliedValue(supply);
		v.setValid(valid);
		return v;
	}
}
