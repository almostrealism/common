/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.util;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.SupplierArgumentMap;

import java.util.function.Supplier;

public class ProviderAwareArgumentMap<S, A> extends SupplierArgumentMap<S, A> {
	@Override
	public ArrayVariable<A> get(Supplier key) {
		ArrayVariable<A> arg = super.get(key);
		if (arg != null) return arg;

		Object provider = key.get();
		if (provider instanceof Provider == false) return null;

		Object value = ((Provider) provider).get();

		return get((Supplier supplier) -> {
			Object v = supplier.get();
			if (v instanceof Provider == false) return false;
			return ((Provider) v).get() == value;
		}).orElse(null);
	}
}
