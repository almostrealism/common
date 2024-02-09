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

package io.almostrealism.uml;

import java.util.function.Supplier;

public class ThreadLocalSuppliedValue<T> extends SuppliedValue<T> {
	private ThreadLocal<T> value;

	public ThreadLocalSuppliedValue(Supplier<T> supplier) {
		super(supplier);
		this.value = new ThreadLocal<>();
	}

	@Override
	public T getValue() {
		if (value.get() == null) value.set(supplier.get());
		return value.get();
	}

	@Override
	public boolean isAvailable() { return value.get() != null; }
}
