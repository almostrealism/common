/*
 * Copyright 2022 Michael Murray
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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class ValueFromPackedCollection<T extends MemoryData> extends DynamicProducerComputationAdapter<PackedCollection, T> {
	private TraversalPolicy shape;

	public ValueFromPackedCollection(TraversalPolicy shape, Supplier<Evaluable<? extends T>> result,
									 IntFunction<MemoryBank<T>> kernelDestination,
									 Supplier<Evaluable<? extends PackedCollection>> collection,
									 Supplier<Evaluable<? extends Scalar>> index) {
		super(shape.size(1), result, kernelDestination, collection, (Supplier) index);
		this.shape = shape;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (pos < getMemLength()) {
				String number = "(" + Hardware.getLocalHardware().getNumberTypeName() + ")";

				if (getArgument(2, 2).isStatic()) {
					return getArgument(1, shape.getTotalSize()).get(getMemLength() + " * " + getInputValue(2, 0).getExpression() + " + floor(" + number + pos + ")");
				} else {
					return getArgument(1, shape.getTotalSize()).get(getMemLength() + " * " + getInputValue(2, 0).getExpression() + " + floor(" + number + pos + ")", getArgument(2, 2));
				}
			} else {
				return new Expression<>(Double.class, stringForDouble(0.0));
			}
		};
	}
}
