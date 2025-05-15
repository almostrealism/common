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

import io.almostrealism.collect.TraversalPolicy;
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

	default Dataset<PackedCollection<?>> batch(int batchSize) {
		return batches(batchSize, (Iterable) this);
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

	static <T extends PackedCollection<?>> Dataset<PackedCollection<?>> batches(int batchSize, Iterable<ValueTarget<T>> targets) {
		TraversalPolicy inputItem;
		TraversalPolicy targetItem;
		List<ValueTarget<PackedCollection<?>>> data = new ArrayList<>();

		int n = -1;
		PackedCollection<PackedCollection<?>> currentInput = null;
		PackedCollection<PackedCollection<?>> currentTarget = null;

		f: for (ValueTarget<T> target : targets) {
			if (n < 0) {
				inputItem = target.getInput().getShape();
				currentInput = new PackedCollection<>(inputItem.prependDimension(batchSize).traverse(1));

				targetItem = target.getExpectedOutput().getShape();
				currentTarget = new PackedCollection<>(targetItem.prependDimension(batchSize).traverse(1));

				n = 0;
			}

			currentInput.set(n, target.getInput());
			currentTarget.set(n, target.getExpectedOutput());
			n++;

			if (n >= batchSize) {
				data.add(ValueTarget.of(currentInput, currentTarget));
				currentInput = new PackedCollection<>(currentInput.getShape());
				currentTarget = new PackedCollection<>(currentTarget.getShape());
				n = 0;
			}
		}

		return Dataset.of(data);
	}
}
