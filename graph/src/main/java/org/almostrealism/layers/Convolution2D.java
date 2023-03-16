/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.layers;

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class Convolution2D implements Layer, Setup, CollectionFeatures {
	private int filterCount;
	private PackedCollection<?> filters;

	public Convolution2D(int filters) {
		this.filterCount = filters;
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList();
		setup.add(a(filters.getShape().getTotalSize(), p(filters), _divide(randn(shape(filterCount, 3, 3)), c(9))));
		return setup;
	}

	@Override
	public Supplier<Runnable> forward(Producer<PackedCollection<?>> input) {
		return null;
	}
}
