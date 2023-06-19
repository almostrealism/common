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

import io.almostrealism.expression.Cast;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mod;
import io.almostrealism.relation.Producer;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;

import java.util.OptionalInt;
import java.util.function.Supplier;

public class PackedCollectionRepeat<T extends PackedCollection<?>>
		extends KernelProducerComputationAdapter<PackedCollection<?>, T> {
	private TraversalPolicy subsetShape;

	public PackedCollectionRepeat(int repeat, Producer<?> collection) {
		this(shape(collection).item(), repeat, collection);
	}

	public PackedCollectionRepeat(TraversalPolicy shape, int repeat, Producer<?> collection) {
		super(shape(collection).replace(shape.prependDimension(repeat)), (Supplier) collection);
		this.subsetShape = shape.getDimensions() == 0 ? shape(1) : shape;
	}

	@Override
	public int getMemLength() { return 1; }

	@Override
	public Expression<Double> getValueAt(Expression index) {
		// Find the index in that slice
		Expression offset = new Mod(new Cast("int", index), e(subsetShape.getTotalSize()), false);

		// If the offset is a known constant, the value can be
		// directly obtained
		OptionalInt offsetValue = offset.intValue();
		if (offsetValue.isPresent()) {
			return getArgument(1).getValueAt(offsetValue.getAsInt());
		}

		// Otherwise the value will only be available if the
		// argument is a Shape implementation represented by
		// a CollectionVariable which supports TraversableExpression
		// operations like getValueAt
		CollectionVariable var = getCollectionArgumentVariable(1);
		if (var == null) return null;

		return var.getValueAt(offset);
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Repeat cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}
}
