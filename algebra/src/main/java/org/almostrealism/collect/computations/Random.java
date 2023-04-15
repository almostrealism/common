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

package org.almostrealism.collect.computations;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;

import java.util.stream.IntStream;

public class Random implements KernelizedProducer<PackedCollection<?>>, Shape<Producer<PackedCollection<?>>> {
	private java.util.Random random;
	private TraversalPolicy shape;
	private boolean normal;

	public Random(TraversalPolicy shape) {
		this(shape, false);
	}

	public Random(TraversalPolicy shape, boolean normal) {
		this.random = new java.util.Random();
		this.shape = shape;
		this.normal = normal;
	}

	@Override
	public KernelizedEvaluable<PackedCollection<?>> get() {
		return new KernelizedEvaluable<>() {
			@Override
			public MemoryBank<PackedCollection<?>> createKernelDestination(int size) {
				return new PackedCollection<>(getShape().prependDimension(size));
			}

			@Override
			public PackedCollection<?> evaluate(Object... args) {
				PackedCollection<?> destination = new PackedCollection<>(getShape());
				kernelEvaluate(destination);
				return destination;
			}

			@Override
			public void kernelEvaluate(MemoryBank destination, MemoryData... args) {
				destination.setMem(IntStream.range(0, getShape().getTotalSize())
						.mapToDouble(i -> normal ? random.nextGaussian() : random.nextDouble())
						.toArray());
			}
		};
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public Producer<PackedCollection<?>> reshape(TraversalPolicy shape) {
		return new ReshapeProducer<>(shape, (Producer) this);
	}
}
