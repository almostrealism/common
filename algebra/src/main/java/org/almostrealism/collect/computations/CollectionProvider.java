/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataCopy;

public class CollectionProvider<T extends PackedCollection<?>> extends Provider<T> implements CollectionFeatures {
	public CollectionProvider(T value) {
		super(value);
	}

	@Override
	public Multiple<T> createDestination(int size) {
		return new PackedCollection<>(shape(get()));
	}

	@Override
	public Evaluable<T> into(Object destination) {
		Runnable copy = new MemoryDataCopy("CollectionProvider Evaluate Into",
				this::get, () -> (MemoryData) destination, shape(get()).getTotalSize()).get();
		return args -> {
			copy.run();
			return (T) destination;
		};
	}
}
