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
import java.util.function.Supplier;

public class SuppliedValue<T> implements Destroyable {
	protected Supplier<T> supplier;
	private T value;

	public SuppliedValue(Supplier<T> supplier) {
		this.supplier = supplier;
	}

	public T getValue() {
		if (value == null) value = supplier.get();
		return value;
	}

	public boolean isAvailable() { return value != null; }

	public void applyAll(Consumer<T> consumer) {
		if (consumer == null || !isAvailable()) return;

		consumer.accept(getValue());
	}

	@Override
	public void destroy() {
		if (value instanceof Destroyable) {
			((Destroyable) value).destroy();
		}

		value = null;
	}
}
