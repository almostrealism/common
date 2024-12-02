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

public class SuppliedValue<T> implements Destroyable {
	protected Supplier<T> supplier;
	protected T value;

	protected Predicate<T> valid;
	private Consumer<T> clear;

	protected SuppliedValue() { }

	public SuppliedValue(Supplier<T> supplier) {
		this.supplier = supplier;
	}

	protected T createValue() {
		return supplier.get();
	}

	public T getValue() {
		if (!isAvailable()) value = createValue();
		return value;
	}

	public void setValid(Predicate<T> valid) { this.valid = valid; }
	public void setClear(Consumer<T> clear) { this.clear = clear; }

	public boolean isAvailable() { return value != null && (valid == null || valid.test(value)); }

	public void applyAll(Consumer<T> consumer) {
		if (consumer == null || !isAvailable()) return;

		consumer.accept(getValue());
	}

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
