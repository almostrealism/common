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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Position;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PackedCollectionFromPackedCollection<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends DynamicProducerComputationAdapter<I, O> implements CollectionProducer<O> {
	private TraversalPolicy inputShape;

	public PackedCollectionFromPackedCollection(TraversalPolicy inputShape, TraversalPolicy outputShape,
												Supplier<Evaluable<? extends O>> result,
												IntFunction<MemoryBank<O>> kernelDestination,
												Supplier<Evaluable<? extends I>> collection,
												Supplier<Evaluable<? extends Position>> position) {
		super(outputShape.getTotalSize(), result, kernelDestination, collection, (Supplier) position);
		this.inputShape = inputShape;
	}

	@Override
	public TraversalPolicy getShape() { return inputShape; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			// TODO  ????
			if (pos == 0) {
				if (getArgument(2).isStatic()) {
					return getArgument(1).get("2 * " + getInputValue(2, 0).getExpression());
				} else {
					return getArgument(1).get("2 * " + getInputValue(2, 0).getExpression(), getArgument(2));
				}
			} else if (pos == 1) {
				return new Expression<>(Double.class, stringForDouble(1.0));
			} else {
				throw new IllegalArgumentException();
			}
		};
	}
}
