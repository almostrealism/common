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

import org.almostrealism.algebra.computations.StaticComputationAdapter;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;

import java.util.function.Supplier;

public class StaticCollectionComputation<T extends PackedCollection<?>> extends StaticComputationAdapter<T> implements CollectionProducerComputation<T> {

	public StaticCollectionComputation(PackedCollection<?> value) {
		super((T) value, (Supplier) PackedCollection.blank(value.getShape()), len -> new PackedCollection(value.getShape().prependDimension(len)));
	}

	@Override
	public TraversalPolicy getShape() {
		return getValue().getShape();
	}

	@Override
	public CollectionProducerComputation<T> traverse(int axis) {
		return new StaticCollectionComputation(getValue().traverse(axis));
	}
}
