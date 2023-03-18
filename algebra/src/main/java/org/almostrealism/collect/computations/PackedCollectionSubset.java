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

import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;

public class PackedCollectionSubset<T extends PackedCollection<?>> implements CollectionProducerComputation<T> {
	private TraversalPolicy shape;
	private int[] position;

	public PackedCollectionSubset(TraversalPolicy shape, Producer<PackedCollection<?>> collection, int... position) {
		this.shape = shape;
		this.position = position;
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public Scope<T> getScope() {
		throw new UnsupportedOperationException();
	}
}
