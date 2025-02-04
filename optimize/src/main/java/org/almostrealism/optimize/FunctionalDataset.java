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
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

public class FunctionalDataset<T extends MemoryData> implements Dataset<T> {
	private List<PackedCollection<?>> inputs;
	private Function<PackedCollection<?>, ValueTarget<T>> function;

	public FunctionalDataset(List<PackedCollection<?>> inputs,
							 Function<PackedCollection<?>, ValueTarget<T>> function) {
		this.inputs = inputs;
		this.function = function;
	}

	@Override
	public Iterator<ValueTarget<T>> iterator() {
		return inputs.stream().map(function).iterator();
	}

	@Override
	public List<Dataset<T>> split(double ratio) {
		List<PackedCollection<?>> a = new ArrayList<>();
		List<PackedCollection<?>> b = new ArrayList<>();

		inputs.forEach(v -> {
			if (Math.random() < ratio) {
				a.add(v);
			} else {
				b.add(v);
			}
		});

		return List.of(new FunctionalDataset<>(a, function),
					   new FunctionalDataset<>(b, function));
	}
}
