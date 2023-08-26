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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerForMemoryData;

import java.util.function.Function;

public class DynamicCollectionProducer<T extends PackedCollection<?>> extends DynamicProducerForMemoryData<T> implements CollectionProducer<T> {
	private TraversalPolicy shape;

	public DynamicCollectionProducer(TraversalPolicy shape, Function<Object[], T> function) {
		super(function, len -> new PackedCollection(shape.prependDimension(len)));
		this.shape = shape;
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	@Override
	public CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}
}
