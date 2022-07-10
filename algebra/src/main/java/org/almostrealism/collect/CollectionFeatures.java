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

package org.almostrealism.collect;

import io.almostrealism.code.NameProvider;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.computations.PackedCollectionExpressionComputation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Function;
import java.util.function.Supplier;

public interface CollectionFeatures {
	default CollectionProducer<PackedCollection> integers(int from, int to) {
		return new CollectionProducer() {
			@Override
			public TraversalPolicy getShape() {
				return new TraversalPolicy(from - to);
			}

			@Override
			public Scope getScope() {
				throw new UnsupportedOperationException();
			}

			@Override
			public KernelizedEvaluable<PackedCollection> get() {
				return new KernelizedEvaluable<>() {
					@Override
					public MemoryBank<PackedCollection> createKernelDestination(int size) {
						throw new UnsupportedOperationException();
					}

					@Override
					public PackedCollection evaluate(Object... args) {
						int len = to - from;
						PackedCollection collection = new PackedCollection(2, len);

						for (int i = 0; i < len; i++) {
							collection.setMem(2 * i, from + i, 1.0);
						}

						return collection;
					}
				};
			}
		};
	}

	default <T extends PackedCollection> PackedCollectionExpressionComputation<T> add(
			TraversalPolicy shape, Supplier<Evaluable<? extends T>> a, Supplier<Evaluable<? extends T>> b) {
		Function<NameProvider, Expression<Double>> expression = np ->
			new Sum(np.getArgument(1, 1).valueAt(0), np.getArgument(2, 1).valueAt(0));
		return new PackedCollectionExpressionComputation<>(shape, expression, a, b);
	}

	default <T extends PackedCollection> PackedCollectionExpressionComputation<T> multiply(
			TraversalPolicy shape, Supplier<Evaluable<? extends T>> a, Supplier<Evaluable<? extends T>> b) {
		Function<NameProvider, Expression<Double>> expression = np ->
				new Product(np.getArgument(1, 1).valueAt(0), np.getArgument(2, 1).valueAt(0));
		return new PackedCollectionExpressionComputation<>(shape, expression, a, b);
	}
}
