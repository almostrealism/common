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

package org.almostrealism.lifecycle;

import io.almostrealism.lifecycle.Destroyable;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Provides lazy initialization with validation and lifecycle management for expensive resources.
 *
 * <p>SuppliedValue manages a value that is created on-demand from a {@link Supplier} and
 * can be validated, cleared, and destroyed. This is particularly useful for:</p>
 * <ul>
 *   <li>Expensive resources that should only be created when needed</li>
 *   <li>Resources that may become invalid and need recreation</li>
 *   <li>Resources requiring explicit cleanup (via {@link Destroyable})</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * SuppliedValue<ExpensiveResource> resource =
 *     new SuppliedValue<>(() -> new ExpensiveResource());
 *
 * // Resource is created only when first accessed
 * ExpensiveResource r = resource.getValue();
 *
 * // Clean up when done
 * resource.destroy();
 * }</pre>
 *
 * <h2>Validation</h2>
 * <pre>{@code
 * SuppliedValue<Connection> conn = new SuppliedValue<>(() -> openConnection());
 * conn.setValid(c -> c.isConnected());  // Recreate if connection drops
 *
 * // If connection becomes invalid, getValue() will create a new one
 * Connection c = conn.getValue();
 * }</pre>
 *
 * <h2>Custom Cleanup</h2>
 * <pre>{@code
 * SuppliedValue<FileHandle> handle = new SuppliedValue<>(() -> openFile());
 * handle.setClear(h -> h.close());  // Custom cleanup logic
 *
 * handle.clear();  // Calls custom cleanup
 * }</pre>
 *
 * @param <T> the type of value managed
 * @see Destroyable
 * @see ThreadLocalSuppliedValue
 */
public class SuppliedValue<T> implements Destroyable {
	protected Supplier<T> supplier;
	protected T value;

	protected Predicate<T> valid;
	private Consumer<T> clear;

	/**
	 * Creates a supplied value with no supplier (for subclasses).
	 */
	protected SuppliedValue() { }

	/**
	 * Creates a supplied value with the given supplier.
	 *
	 * @param supplier the supplier to create values
	 */
	public SuppliedValue(Supplier<T> supplier) {
		this.supplier = supplier;
	}

	/**
	 * Creates a new value by invoking the supplier.
	 * Subclasses can override to customize value creation.
	 *
	 * @return a new value
	 */
	protected T createValue() {
		return supplier.get();
	}

	/**
	 * Gets the value, creating it if it's not currently available.
	 * A value is considered unavailable if it's null or fails validation.
	 *
	 * @return the value
	 */
	public T getValue() {
		if (!isAvailable()) value = createValue();
		return value;
	}

	/**
	 * Sets a validation predicate that determines if the current value is still valid.
	 * If validation fails, the value will be recreated on next access.
	 *
	 * @param valid the validation predicate
	 */
	public void setValid(Predicate<T> valid) { this.valid = valid; }

	/**
	 * Sets a custom cleanup consumer that will be called when the value is cleared.
	 *
	 * @param clear the cleanup consumer
	 */
	public void setClear(Consumer<T> clear) { this.clear = clear; }

	/**
	 * Checks if a value is currently available and valid.
	 *
	 * @return true if a value is available
	 */
	public boolean isAvailable() { return value != null && (valid == null || valid.test(value)); }

	/**
	 * Applies a consumer to the value if it's available.
	 *
	 * @param consumer the consumer to apply
	 */
	public void applyAll(Consumer<T> consumer) {
		if (consumer == null || !isAvailable()) return;

		consumer.accept(getValue());
	}

	/**
	 * Clears the current value, calling the custom clear consumer if set,
	 * or destroying the value if it implements {@link Destroyable}.
	 */
	public void clear() {
		if (value == null) return;

		if (clear != null) {
			clear.accept(value);
		} else if (value instanceof Destroyable) {
			((Destroyable) value).destroy();
			return;
		}

		// Do not allow any other destroy steps
		// if the clear operation was handled
		// by a custom consumer
		value = null;
	}

	@Override
	public void destroy() {
		clear();

		if (value instanceof Destroyable) {
			((Destroyable) value).destroy();
		}
	}
}
