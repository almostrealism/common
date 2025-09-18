/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ComplexProductExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface PairFeatures extends CollectionFeatures {

	default CollectionProducer<Pair<?>> pair(double x, double y) { return value(new Pair(x, y)); }

	default CollectionProducer<Pair<?>> pair(Producer<PackedCollection<?>> x,
											 Producer<PackedCollection<?>> y) {
		return concat(shape(2), x, y);
	}

	default CollectionProducer<Pair<?>> v(Pair value) { return value(value); }

	default CollectionProducer<Pair<?>> value(Pair value) {
		return DefaultTraversableExpressionComputation.fixed((Pair<?>) value, Pair.postprocessor());
	}

	default CollectionProducer<Scalar> l(Producer<Pair<?>> p) {
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> args.get(1).getValueRelative(0),
				args -> new DoubleConstant(1.0)), (Supplier) p).setPostprocessor(Scalar.postprocessor());
	}

	default CollectionProducer<Scalar> r(Supplier<Evaluable<? extends Pair<?>>> p) {
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> args.get(1).getValueRelative(1),
				args -> new DoubleConstant(1.0)), (Supplier) p).setPostprocessor(Scalar.postprocessor());
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> multiplyComplex(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(a);
		int size = shape(b).getSize();

		if (shape.getSize() != size) {
			if (shape.getSize() != 1 && size != 1) {
				throw new IllegalArgumentException("Cannot multiply a collection of size " + shape.getSize() +
						" with a collection of size " + size);
			} else {
				// TODO This should actually just call traverseEach if the shapes don't match, but one size is = 1
				System.out.println("WARN: Multiplying a collection of size " + shape.getSize() +
						" with a collection of size " + size + " (will broadcast)");
			}
		}

		return new DefaultTraversableExpressionComputation<>("multiplyComplex", shape,
				(Function<TraversableExpression[], CollectionExpression>)
						args -> new ComplexProductExpression(shape, args[1], args[2]),
				(Supplier) a, (Supplier) b).setPostprocessor(ComplexNumber.complexPostprocessor());
	}

	default ExpressionComputation<Pair<?>> complexFromParts(Supplier<Evaluable<? extends PackedCollection<?>>> real,
															Supplier<Evaluable<? extends PackedCollection<?>>> imag) {
		long size = shape(real).getTotalSizeLong();
		if (shape(imag).getTotalSizeLong() != size) {
			throw new IllegalArgumentException();
		}

		boolean pair = size == 1;

		Function<List<ArrayVariable<Double>>, Expression<Double>> comp[] = new Function[2];
		comp[0] = args -> args.get(1).getValueRelative(0);
		comp[1] = args -> args.get(2).getValueRelative(0);
		return (ExpressionComputation<Pair<?>>) new ExpressionComputation(
//				new TraversalPolicy(size, 2).traverse(1),
				shape(real).traverseEach().append(shape(2)),
				List.of(comp), real, imag).setPostprocessor(pair ? Pair.postprocessor() : null);
	}

	default Producer<Pair<?>> pairFromBank(Producer<PackedCollection<Pair<?>>> bank, Producer<PackedCollection<?>> index) {
		int count = shape(index).getCount();
		Producer<PackedCollection<?>> pair =
				add(repeat(2, traverse(1, index)).multiply(2), repeat(count, c(0.0, 1.0)));
		return (Producer) c(shape(index).append(shape(2)), bank, pair);
	}

	static PairFeatures getInstance() {
		return new PairFeatures() { };
	}
}
