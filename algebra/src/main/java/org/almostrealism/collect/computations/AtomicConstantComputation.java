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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

public class AtomicConstantComputation<T extends PackedCollection<?>> extends SingleConstantComputation<T> {
	public AtomicConstantComputation() { this(1.0); }

	public AtomicConstantComputation(double value) {
		this(new TraversalPolicy(1), value);
	}

	protected AtomicConstantComputation(TraversalPolicy shape, double value) {
		super(shape, value);

		if (shape.getTotalSizeLong() != 1) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new AtomicConstantComputation<>(getShape().traverse(1), value);
	}

	@Override
	public CollectionProducerComputation<T> reshape(TraversalPolicy shape) {
		if (shape.getTotalSizeLong() == 1) {
			return new AtomicConstantComputation<>(shape, value);
		}

		return super.reshape(shape);
	}

	@Override
	public String describe() { return description(); }

}
