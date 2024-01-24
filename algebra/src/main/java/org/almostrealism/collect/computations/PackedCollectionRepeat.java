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

import io.almostrealism.expression.Cast;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;

public class PackedCollectionRepeat<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	public static boolean enableTraverseEach = false;

	private TraversalPolicy subsetShape;
	private TraversalPolicy sliceShape;

	public PackedCollectionRepeat(int repeat, Producer<?> collection) {
		this(shape(collection).item(), repeat, collection);
	}

	public PackedCollectionRepeat(TraversalPolicy shape, int repeat, Producer<?> collection) {
		super(enableTraverseEach ?
					shape(collection).replace(shape.prependDimension(repeat)).traverseEach() :
					shape(collection).replace(shape.prependDimension(repeat)).traverse(),
				collection, null);
		this.subsetShape = shape.getDimensions() == 0 ? shape(1) : shape;
		this.sliceShape = subsetShape.prependDimension(repeat);
	}

	private PackedCollectionRepeat(TraversalPolicy shape, TraversalPolicy subsetShape,
								   TraversalPolicy sliceShape, Producer<?> collection) {
		super(shape, collection, null);
		this.subsetShape = subsetShape;
		this.sliceShape = sliceShape;
	}

	@Override
	public int getMemLength() { return 1; }

	@Override
	public int getCount() {
		return getShape().traverseEach().getCount();
	}

	@Override
	protected Expression projectIndex(Expression index) {
		// Identify the slice
		Expression slice;

		if (sliceShape.getTotalSize() == 1) {
			slice = index;
		} else if (index.getType() == Integer.class ||
				(index instanceof Cast && Objects.equals("int", ((Cast) index).getTypeName()))) {
			slice = index.divide(e(sliceShape.getTotalSize()));
		} else {
			slice = index.divide(e((double) sliceShape.getTotalSize())).floor();
		}

		// Find the index in that slice
		Expression offset = index.toInt().mod(e(subsetShape.getTotalSize()), false);

		// Position the offset relative to the slice
		offset = slice.multiply(e(subsetShape.getTotalSize())).add(offset);

		return offset;
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		Expression offset = projectIndex(index);
		OptionalDouble offsetValue = offset.getSimplified().doubleValue();
		if (offsetValue.isEmpty()) throw new UnsupportedOperationException();

		return getArgument(1).getValueRelative((int) offsetValue.getAsDouble());
	}

	@Override
	public PackedCollectionRepeat<T> generate(List<Process<?, ?>> children) {
		return new PackedCollectionRepeat<>(getShape(), subsetShape, sliceShape, (Producer<?>) children.get(1));
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Repeat cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}
}
