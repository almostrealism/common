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

public class ThreadLocalSuppliedValue<T> extends SuppliedValue<T> {
	private WeakHashMap<Thread, T> values;

	public ThreadLocalSuppliedValue(Supplier<T> supplier) {
		super(supplier);
	}

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

	@Override
	public boolean isAvailable() {
		return values != null && values.get(Thread.currentThread()) != null
				&& (valid == null || valid.test(values.get(Thread.currentThread())));
	}

	@Override
	public void applyAll(Consumer<T> consumer) {
		if (consumer == null || !isAvailable()) return;

		if (values != null) {
			values.values().stream()
					.filter(Objects::nonNull)
					.forEach(consumer);
		}
	}

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
