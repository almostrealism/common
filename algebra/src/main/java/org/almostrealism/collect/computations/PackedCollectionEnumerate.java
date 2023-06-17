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
import io.almostrealism.expression.IgnoreMultiExpression;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Producer;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.KernelSupport;

import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class PackedCollectionEnumerate<T extends PackedCollection<?>>
		extends DynamicCollectionProducerComputationAdapter<PackedCollection<?>, T>
		implements TraversableExpression<Double>, IgnoreMultiExpression<Double> {

	private TraversalPolicy strideShape;
	private TraversalPolicy subsetShape;

	public PackedCollectionEnumerate(TraversalPolicy shape, Producer<?> collection) {
		this(shape, computeStride(shape, collection), collection);
	}

	public PackedCollectionEnumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		super(computeShape(shape, stride, collection), (Supplier) collection);
		this.subsetShape = shape;
		this.strideShape = stride;
	}

	@Override
	public int getMemLength() { return 1; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i != 0) throw new IllegalArgumentException("Invalid position");

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

		// Determine which slice to extract
		Expression slice = index.divide(e((double) subsetShape.getTotalSize())).floor();

		// Find the index in that slice
		// Expression offset = e("((int) " + index.getExpression() + ") % " + subsetShape.getTotalSize(), index);
		Expression offset = new Mod(new Cast("int", index), e(subsetShape.getTotalSize()), false);

		// Determine the location of the slice
		Expression<?> p[] = new Expression[subsetShape.getDimensions()];

		for (int j = 0; j < subsetShape.getDimensions(); j++) {
			if (strideShape.length(j) > 0) {
				p[j] = slice.multiply(e(strideShape.length(j)));
			} else {
				p[j] = e(0);
			}
		}

		return var.get(subsetShape, p).getValueAt(offset);
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Enumerate cannot be performed without a TraversalPolicy");

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

		return new TraversalPolicy(dims);
	}
}
