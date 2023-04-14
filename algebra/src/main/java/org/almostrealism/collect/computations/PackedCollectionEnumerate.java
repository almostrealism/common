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

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class PackedCollectionEnumerate<T extends PackedCollection<?>>
		extends DynamicCollectionProducerComputationAdapter<PackedCollection<?>, T>
		implements CollectionProducerComputation<T> {

	private TraversalPolicy strideShape;
	private TraversalPolicy subsetShape;

	public PackedCollectionEnumerate(TraversalPolicy shape, Producer<?> collection) {
		this(shape, computeStride(shape, collection), collection);
	}

	public PackedCollectionEnumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		super(computeShape(shape, stride, collection), (Supplier) collection);
		this.subsetShape = shape;
		this.strideShape = stride;
		setDestination(() -> { throw new UnsupportedOperationException(); });
		setInputs(new Destination(), (Supplier) collection);
		init();
	}

	@Override
	public int getMemLength() { return 1; }

	@Override
	protected MemoryBank<?> createKernelDestination(int len) {
		if (len != getShape().getTotalSize())
			throw new IllegalArgumentException("Enumerate kernel size must match original shape (" + getShape().getTotalSize() + ")");

		return new PackedCollection<>(getShape().traverseEach());
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i != 0) throw new IllegalArgumentException("Invalid position");

			Expression index = new Expression(Double.class, KernelSupport.getKernelIndex(0));

			// Index into subset shape
			Expression offset = e(index.getExpression() + " % " + subsetShape.getTotalSize());

			// The current slice
			Expression slice = index.divide(e((double) subsetShape.getTotalSize())).floor();

			TraversalPolicy inputShape = ((Shape) getInputs().get(1)).getShape();
			Expression<?> pos[] = new Expression[subsetShape.getDimensions()];

			for (int j = 0; j < subsetShape.getDimensions(); j++) {
				if (strideShape.length(j) > 0) {
					pos[j] = slice.multiply(e(strideShape.length(j)));
				} else {
					pos[j] = e(0);
				}
			}

			Expression<?> p = inputShape.subset(subsetShape, offset, pos);
			return getArgument(1, inputShape.getTotalSize()).get(p, -1);
		};
	}

	private class Destination implements Producer<PackedCollection<?>>, Delegated<DestinationSupport<T>>, KernelSupport {
		@Override
		public Evaluable<PackedCollection<?>> get() {
			return args -> new PackedCollection<>(getShape().traverseEach());
		}

		@Override
		public DestinationSupport<T> getDelegate() {
			return PackedCollectionEnumerate.this;
		}
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Subset cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}

	private static TraversalPolicy computeShape(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		TraversalPolicy superShape = shape(collection);

		int count = IntStream.range(0, shape.getDimensions()).map(dim -> {
						int pad = stride.length(dim) - shape.length(dim);
						return stride.length(dim) > 0 ? (superShape.length(dim) + pad) / stride.length(dim) : -1;
					})
					.filter(i -> i > 0).min()
					.orElseThrow(() -> new IllegalArgumentException("Invalid stride"));

		return shape.prependDimension(count).traverseEach();
	}

	private static TraversalPolicy computeStride(TraversalPolicy shape, Producer<?> collection) {
		TraversalPolicy superShape = shape(collection);

		int dims[] = new int[shape.getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			if (superShape.length(i) % shape.length(i) != 0) {
				throw new IllegalArgumentException("Dimension " + i +
						" of collection is not divisible by the corresponding dimension of the subset shape");
			} else {
				dims[i] = superShape.length(i) / shape.length(i);
			}
		}

		int axis = -1;

		for (int i = 0; i < dims.length; i++) {
			if (dims[i] > 1) {
				if (axis >= 0) {
					throw new UnsupportedOperationException("Enumeration across more than one axis is not currently supported");
				} else {
					axis = i;
					dims[i] = shape.length(i);
				}
			} else {
				dims[i] = 0;
			}
		}

		return new TraversalPolicy(dims);
	}
}
