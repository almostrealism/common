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
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversableExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class PackedCollectionRepeat<T extends PackedCollection<?>>
		extends DynamicCollectionProducerComputationAdapter<PackedCollection<?>, T>
		implements TraversableExpression<Double> {
	private TraversalPolicy subsetShape;

	public PackedCollectionRepeat(int repeat, Producer<?> collection) {
		this(shape(collection).item(), repeat, collection);
	}

	public PackedCollectionRepeat(TraversalPolicy shape, int repeat, Producer<?> collection) {
		super(shape(collection).replace(shape.prependDimension(repeat)), (Supplier) collection);
		this.subsetShape = shape;
	}

	@Override
	public int getMemLength() { return 1; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i != 0)
				throw new IllegalArgumentException("Invalid position");

			Expression index = new StaticReference(Double.class, KernelSupport.getKernelIndex(0));
			return getValueAt(index);
		};
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		// Find the index in the output shape
		Expression index = getShape().index(pos);
		return getValueAt(index);
	}

	public Expression<Double> getValueAt(Expression index) {
		CollectionVariable var = getCollectionArgumentVariable(1);
		if (var == null) return null;

		// Find the index in that slice
		Expression offset = new Mod(new Cast("int", index), e(subsetShape.getTotalSize()), false);

		return var.getValueAt(offset);
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Repeat cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}
}
