/*
 * Copyright 2021 Michael Murray
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
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PackedCollectionAdd<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends DynamicProducerComputationAdapter<I, O> implements CollectionProducer<O> {
	private TraversalPolicy shape;

	public PackedCollectionAdd(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection>> a,
						 Supplier<Evaluable<? extends PackedCollection>> b) {
		super(shape.size(0), () -> args -> (O) new PackedCollection(shape.size(0)),
				i -> { throw new UnsupportedOperationException(); },
				(Supplier) a, (Supplier) b);
		this.shape = shape;
	}

	@Override
	public TraversalPolicy getShape() { return shape; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> new Sum(getArgument(1).valueAt(i), getArgument(2).valueAt(i));
	}
}
