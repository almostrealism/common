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

import io.almostrealism.code.NameProvider;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.collect.ExpressionComputation;

import java.util.function.Function;
import java.util.function.Supplier;

public class PackedCollectionExpressionComputation<T extends PackedCollection> extends ExpressionComputation<T> implements CollectionProducer<T> {
	private TraversalPolicy shape;

	public PackedCollectionExpressionComputation(TraversalPolicy shape,
												 Function<NameProvider, Expression<Double>> expression,
												 Supplier<Evaluable<? extends T>>... arguments) {
		super(() -> args -> (T) new PackedCollection(shape),
				len -> (MemoryBank<T>) new PackedCollection(shape.prependDimension(len)),
				expression, arguments);
		this.shape = shape;
	}

	@Override
	public TraversalPolicy getShape() { return shape; }
}
