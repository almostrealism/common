/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;
import java.util.stream.IntStream;

public class PackedCollectionEnumerate<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	public static boolean enablePreferIsolation = true;

	private TraversalPolicy inputShape;
	private int traversalDepth;

	private TraversalPolicy subsetShape;
	private TraversalPolicy strideShape;

	public PackedCollectionEnumerate(TraversalPolicy shape, Producer<?> collection) {
		this(shape, computeStride(shape, collection), collection);
	}

	public PackedCollectionEnumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		this(shape, stride, collection, 0);
	}

	public PackedCollectionEnumerate(TraversalPolicy shape, TraversalPolicy stride,
									 Producer<?> collection, int traversalDepth) {
		super(computeShape(shape, stride, collection, traversalDepth), null, collection);
		this.inputShape = shape(collection);
		this.traversalDepth = traversalDepth;
		this.subsetShape = shape;
		this.strideShape = stride;
	}

	@Override
	public int getMemLength() { return 1; }

	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		if (super.isIsolationTarget(context)) return true;

		if (enablePreferIsolation &&
				getParallelism() > minCount &&
				getOutputSize() <= MemoryProvider.MAX_RESERVATION) {
			return true;
		}

		return false;
	}

	@Override
	protected Expression<?> projectIndex(Expression<?> index) {
		TraversalPolicy blockShape = getShape();

		// Determine the current block
		long blockSize = blockShape.sizeLong(traversalDepth);
		Expression block = index.divide(blockSize);
		index = index.imod(blockSize);

		// Determine which slice to extract
		// Starting over from the beginning for each new block
		Expression slice;

		if (subsetShape.getTotalSize() == 1) {
			slice = index;
		} else if (!index.isFP()) {
			slice = index.divide(e(subsetShape.getTotalSizeLong()));
		} else {
			throw new IllegalArgumentException();
		}

		// Find the index in that slice
		Expression offset = index.toInt().imod(subsetShape.getTotalSizeLong());

		// Determine the location of the slice
		Expression<?> p[] = new Expression[subsetShape.getDimensions()];

		for (int j = 0; j < subsetShape.getDimensions(); j++) {
			if (strideShape.length(j) > 0) {
				p[j] = slice.multiply(e(strideShape.length(j)));
			} else {
				p[j] = e(0);
			}
		}

		Expression blockOffset = inputShape.subset(subsetShape, offset, p);

		return block.multiply(e(blockShape.getTotalSizeLong())).add(blockOffset);
	}

	@Override
	public PackedCollectionEnumerate<T> generate(List<Process<?, ?>> children) {
		return (PackedCollectionEnumerate)
				new PackedCollectionEnumerate<>(subsetShape, strideShape,
								(Producer) children.get(1), traversalDepth)
						.addAllDependentLifecycles(getDependentLifecycles());
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Enumerate cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}

	private static TraversalPolicy computeShape(TraversalPolicy shape, TraversalPolicy stride,
												Producer<?> collection, int traversalDepth) {
		TraversalPolicy superShape = shape(collection);

		int count = IntStream.range(0, shape.getDimensions()).map(dim -> {
						int pad = stride.length(dim) - shape.length(dim);
						return stride.length(dim) > 0 ? (superShape.length(dim) + pad) / stride.length(dim) : -1;
					})
					.filter(i -> i > 0).min()
					.orElseThrow(() -> new IllegalArgumentException("Invalid stride"));

		int dims[] = new int[shape.getDimensions() + 1];

		for (int i = 0; i < dims.length; i++) {
			if (i < traversalDepth) {
				dims[i] = superShape.length(i);
			} else if (i == traversalDepth) {
				dims[i] = count;
			} else {
				dims[i] = shape.length(i - 1);
			}
		}

		// return shape.prependDimension(count).traverseEach();
		return new TraversalPolicy(dims).traverseEach();
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
			if (dims[i] > 1 || (axis < 0 && (i + 1) >= dims.length)) {
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

		return new TraversalPolicy(true, dims);
	}
}
