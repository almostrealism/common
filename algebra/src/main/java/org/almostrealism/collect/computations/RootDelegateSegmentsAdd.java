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

package org.almostrealism.collect.computations;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import org.almostrealism.hardware.MemoryBank;

import java.util.List;

public class RootDelegateSegmentsAdd<T extends MemoryBank> extends RootDelegateKernelOperation<T> {
	public RootDelegateSegmentsAdd(List<Producer<T>> input, T destination) {
		super(input, destination);
	}

	@Override
	public DynamicOperationComputationAdapter<Void> construct(Producer<PackedCollection> destination, Producer<PackedCollection> data,
														   Producer<PackedCollection> offsets, Producer<PackedCollection> count) {
		return new PackedCollectionSegmentsAdd(destination, data, offsets, count);
	}
}
