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
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.StaticCollectionComputation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface CollectionFeatures {
	default TraversalPolicy shape(int... dims) { return new TraversalPolicy(dims); }

	default <T extends PackedCollection<?>> CollectionProducer<T> c(double... values) {
		PackedCollection<T> c = new PackedCollection<>(values.length);
		c.setMem(0, values);
		return new StaticCollectionComputation(c);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(PackedCollection<?> supplier) {
		return new StaticCollectionComputation(supplier);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(Producer<PackedCollection<?>>... producers) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions = IntStream.range(0, producers.length)
				.mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>) args -> args.get(i + 1).getValue(0))
				.collect(Collectors.toList());
		return new ExpressionComputation(expressions, producers);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(Supplier<Evaluable<? extends PackedCollection<?>>> supplier, int index) {
		return new ExpressionComputation<>(List.of(args -> args.get(1).getValue(index)), supplier);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(Producer supplier, int index) {
		return new ExpressionComputation<>(List.of(args -> args.get(1).getValue(index)), supplier);
	}

	default CollectionProducer<PackedCollection<?>> integers(int from, int to) {
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

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _add(
			Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
			new Sum(args.get(1).getValue(0), args.get(2).getValue(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _subtract(
			Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		return _add(a, _minus(b));
	}

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _multiply(
			Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		return _multiply(a, b, null);
	}

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _multiply(
			Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b,
			Evaluable<T> shortCircuit) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = np ->
				new Product(np.get(1).getValue(0), np.get(2).getValue(0));
		ExpressionComputation<T> exp = new ExpressionComputation<>(List.of(expression), a, b);
		exp.setShortCircuit(shortCircuit);
		return exp;
	}

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _divide(
			Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		return _multiply(a, _pow(b, c(-1.0)));
	}

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _minus(
			Supplier<Evaluable<? extends PackedCollection<?>>> a) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = np ->
				new Minus(np.get(1).getValue(0));
		return new ExpressionComputation<>(List.of(expression), a);
	}

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _pow(
			Supplier<Evaluable<? extends PackedCollection<?>>> base, Supplier<Evaluable<? extends PackedCollection<?>>> exp) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = np ->
				new Exponent(np.get(1).getValue(0), np.get(2).getValue(0));
		return new ExpressionComputation<>(List.of(expression), base, exp);
	}

	// TODO Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _floor(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = np ->
				new Floor(np.get(1).getValue(0));
		return new ExpressionComputation<>(List.of(expression), value);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> _min(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
				new Min(args.get(1).getValue(0), args.get(2).getValue(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> _max(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
				new Max(args.get(1).getValue(0), args.get(2).getValue(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> _mod(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
				new Mod(args.get(1).getValue(0), args.get(2).getValue(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> _bound(Supplier<Evaluable<? extends PackedCollection<?>>> a, double min, double max) {
		return _min(_max(a, c(min)), c(max));
	}

	static CollectionFeatures getInstance() {
		return new CollectionFeatures() { };
	}
}
