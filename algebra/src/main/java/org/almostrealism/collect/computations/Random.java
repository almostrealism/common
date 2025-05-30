/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryBank;

import java.util.stream.IntStream;

// TODO  It seems like this should actually implement CollectionProducer
public class Random implements Producer<PackedCollection<?>>, Shape<Producer<PackedCollection<?>>>, OperationInfo {
	private static long seed;

	private OperationMetadata metadata;
	private java.util.Random random;
	private TraversalPolicy shape;
	private boolean normal;

	private double[] values;

	public Random(TraversalPolicy shape) {
		this(shape, false);
	}

	public Random(TraversalPolicy shape, boolean normal) {
		this.metadata = new OperationMetadata("Random", "Generate random values",
				"Generate random values " + shape.toStringDetail());
		this.random = new java.util.Random();
		this.shape = shape;
		this.normal = normal;

		if (shape.getTotalSizeLong() == 0) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	protected void initValues() {
		if (values == null) {
			values = IntStream.range(0, getShape().getTotalSize())
					.mapToDouble(i -> normal ? random.nextGaussian() : random.nextDouble())
					.toArray();
		}
	}

	public void refresh() { values = null; }

	@Override
	public Evaluable<PackedCollection<?>> get() {
		return new Evaluable<>() {
			@Override
			public Multiple<PackedCollection<?>> createDestination(int size) {
				return new PackedCollection<>(getShape().prependDimension(size));
			}

			@Override
			public PackedCollection<?> evaluate(Object... args) {
				PackedCollection<?> destination = new PackedCollection<>(getShape());
				into(destination).evaluate(args);
				return destination;
			}

			@Override
			public Evaluable<PackedCollection<?>> into(Object destination) {
				return args -> {
					initValues();
					((MemoryBank) destination).setMem(values);
					return (PackedCollection<?>) destination;
				};
			}
		};
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public Producer<PackedCollection<?>> traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	@Override
	public Producer<PackedCollection<?>> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}

	// TODO  There should be an option to use this ring xor algorithm
	// TODO  (as a genuine Computation) instead of the java.util.Random

	public static int nextInt() {
		seed ^= seed >> 12;
		seed ^= seed << 25;
		seed ^= seed >> 27;
		return (int) ((seed * 0x2545F4914F6CDD1DL) >> 32);
	}

	public static float nextFloat() {
		return (nextInt() >>> 8) / 16777216.0f;
	}
}
