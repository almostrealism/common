/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.optimize;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

public interface Dataset<T extends MemoryData> extends Iterable<ValueTarget<T>> {

	default List<Dataset<T>> split(double ratio) {
		List<ValueTarget<T>> a = new ArrayList<>();
		List<ValueTarget<T>> b = new ArrayList<>();

		forEach(v -> {
			if (Math.random() < ratio) {
				a.add(v);
			} else {
				b.add(v);
			}
		});

		return List.of(of(a), of(b));
	}

	static <T extends MemoryData> Dataset<T> of(Iterable<ValueTarget<T>> targets) {
		return () -> targets.iterator();
	}

	static <T extends PackedCollection<?>> FunctionalDataset<T> of(Iterable<PackedCollection<?>> inputs,
														  Function<PackedCollection<?>, Collection<ValueTarget<T>>> function) {
		List<PackedCollection<?>> list = new ArrayList<>();
		inputs.forEach(list::add);
		return new FunctionalDataset(list, function);
	}
}
