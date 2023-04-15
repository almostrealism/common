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

package org.almostrealism.collect;

import io.almostrealism.code.ExpressionFeatures;
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
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.computations.ArrayVariableComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionFromPackedCollection;
import org.almostrealism.collect.computations.PackedCollectionMap;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.StaticCollectionComputation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.computations.Assignment;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface CollectionFeatures extends ExpressionFeatures {
	boolean enableShapelessWarning = false;

	default TraversalPolicy shape(int... dims) { return new TraversalPolicy(dims); }

	default TraversalPolicy shape(Supplier s) {
		if (s instanceof Shape) {
			return ((Shape) s).getShape();
		} else {
			if (enableShapelessWarning) {
				System.out.println("WARN: " + s.getClass() + " does not have a Shape");
			}

			return shape(1);
		}
	}

	default int size(Supplier s) {
		if (s == null) {
			return -1;
		} else if (s instanceof MemoryDataComputation) {
			return ((MemoryDataComputation) s).getMemLength();
		} else {
			return shape(s).getSize();
		}
	}

	default int size(Shape s) {
		return s.getShape().getSize();
	}

	default <T> Producer<T> p(T value) {
		if (value instanceof Shape) {
			return new CollectionProducerBase<>() {
				@Override
				public Evaluable get() {
					return new Provider<>(value);
				}

				@Override
				public TraversalPolicy getShape() {
					return ((Shape) value).getShape();
				}

				@Override
				public Producer reshape(TraversalPolicy shape) {
					return CollectionFeatures.this.reshape(shape, this);
				}
			};
		} else {
			return () -> new Provider<>(value);
		}
	}

	default <T extends MemoryData> Assignment<T> a(int memLength, Evaluable<T> result, Evaluable<T> value) {
		return a(memLength, () -> result, () -> value);
	}

	default <T extends MemoryData> Assignment<T> a(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		return new Assignment<>(memLength, result, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(double... values) {
		PackedCollection<T> c = new PackedCollection<>(values.length);
		c.setMem(0, values);
		return new StaticCollectionComputation(c);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(TraversalPolicy shape, double... values) {
		if (values.length != shape.getTotalSize()) {
			throw new IllegalArgumentException("Wrong number of values for shape");
		}

		PackedCollection<T> c = new PackedCollection<>(shape);
		c.setMem(0, values);
		return new StaticCollectionComputation(c);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(PackedCollection<?> supplier) {
		return new StaticCollectionComputation(supplier);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> concat(Producer<PackedCollection<?>>... producers) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions = IntStream.range(0, producers.length)
				.mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>) args -> args.get(i + 1).getValue(0))
				.collect(Collectors.toList());
		return new ExpressionComputation(expressions, producers);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(Producer producer) {
		if (producer instanceof CollectionProducer) {
			return (CollectionProducer<T>) producer;
		} else if (producer instanceof Shape) {
			return new CollectionProducer<T>() {
				@Override
				public Evaluable<T> get() {
					return producer.get();
				}

				@Override
				public TraversalPolicy getShape() {
					return ((Shape) producer).getShape();
				}

				@Override
				public Producer<T> reshape(TraversalPolicy shape) {
					return new ReshapeProducer<>(shape, producer);
				}
			};
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(Producer supplier, int index) {
		return new ExpressionComputation<>(List.of(args -> args.get(1).getValue(index)), supplier);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection>> collection, Supplier<Evaluable<? extends Scalar>> index) {
		return (CollectionProducerComputation<T>) new PackedCollectionFromPackedCollection(shape, collection, index);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection<?>> function) {
		return new DynamicCollectionProducer(shape, function);
	}

	default CollectionProducerComputation<PackedCollection<?>> kernel(IntFunction<Expression> kernelIndex,
																	  TraversableKernelExpression kernel,
																	  Producer... arguments) {
		return kernel(kernelIndex, kernel.getShape(), kernel, arguments);
	}

	default CollectionProducerComputation<PackedCollection<?>> kernel(IntFunction<Expression> kernelIndex, TraversalPolicy shape,
																	  KernelExpression kernel, Producer... arguments) {
		Expression index = kernelIndex.apply(0);
		Expression pos[] = shape.position(index);

		return new ArrayVariableComputation<>(
				shape, List.of(args -> {
					CollectionVariable vars[] = new CollectionVariable[args.size()];
					for (int i = 0; i < vars.length; i++) {
						vars[i] = args.get(i) instanceof CollectionVariable ? (CollectionVariable) args.get(i) : null;
					}

					return kernel.apply(vars, pos);
				}), arguments);
	}

	default <T extends Shape<T>> CollectionProducer<T> traverse(int axis, Producer<T> producer) {
		return new ReshapeProducer<>(axis, producer);
	}

	default <T extends Shape<T>> Producer traverseEach(Producer<T> producer) {
		return new ReshapeProducer<>(((Shape) producer).getShape().traverseEach(), producer);
	}

	default <T extends Shape<T>> Producer reshape(TraversalPolicy shape, Producer producer) {
		return new ReshapeProducer<>(shape, producer);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subset(TraversalPolicy shape, Producer<?> collection, int... position) {
		return new PackedCollectionSubset<>(shape, collection, position);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride, Producer<?> collection) {
		throw new UnsupportedOperationException();
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape, Producer<?> collection) {
		return new PackedCollectionEnumerate<>(shape, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		return new PackedCollectionEnumerate<>(shape, stride, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return new PackedCollectionMap<>(collection, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> reduce(Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return reduce(shape(1), collection, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> reduce(TraversalPolicy itemShape, Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return new PackedCollectionMap<>(shape(collection).reduce(itemShape), collection, mapper);
	}

	default Random rand(int... dims) { return rand(shape(dims)); }
	default Random rand(TraversalPolicy shape) { return new Random(shape); }

	default Random randn(int... dims) { return randn(shape(dims)); }
	default Random randn(TraversalPolicy shape) { return new Random(shape, true); }

	default CollectionProducerComputation<PackedCollection<?>> integers(int from, int to) {
		return new CollectionProducerComputation() {
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

	default <T extends PackedCollection<?>> ExpressionComputation<T> add(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		return add(shape.getSize(), (Supplier) a, (Supplier) b);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> add(int depth,
																		 Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
				IntStream.range(0, depth).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
								np -> new Sum(np.get(1).getValue(i), np.get(2).getValue(i)))
						.collect(Collectors.toList());
		return new ExpressionComputation<>(expressions, a, b);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> subtract(Producer<T> a, Producer<T> b) {
		return add(a, minus(b));
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> multiply(
			Producer<T> a, Producer<T> b) {
		return multiply(a, b, null);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> multiply(
			Producer<T> a, Producer<T> b,
			Evaluable<T> shortCircuit) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		return multiply(shape, (Supplier) a, (Supplier) b, shortCircuit);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> multiply(TraversalPolicy shape,
																			  Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b,
																			  Evaluable<T> shortCircuit) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
				IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
								np -> new Product(np.get(1).getValue(i), np.get(2).getValue(i)))
						.collect(Collectors.toList());
		ExpressionComputation<T> exp = new ExpressionComputation<>(shape, expressions, a, b);
		exp.setShortCircuit(shortCircuit);
		return exp;
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> divide(Producer<T> a, Producer<T> b) {
		return multiply(a, pow(b, c(-1.0)));
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> minus(Producer<T> a) {
		return minus(size(a), (Supplier) a);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> minus(int depth, Supplier<Evaluable<? extends PackedCollection<?>>> a) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
				IntStream.range(0, depth).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
								np -> new Minus(np.get(1).getValue(i)))
						.collect(Collectors.toList());
		return new ExpressionComputation<>(expressions, (Supplier) a);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> pow(Producer<T> base, Producer<T> exp) {
		int depth = size(base) == size(exp) ? size(base) : 1;
		return pow(depth, (Supplier) base, (Supplier) exp);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> pow(int depth,
																		 Supplier<Evaluable<? extends PackedCollection<?>>> base, Supplier<Evaluable<? extends PackedCollection<?>>> exp) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
				IntStream.range(0, depth).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
								np -> new Exponent(np.get(1).getValue(i), np.get(2).getValue(i)))
						.collect(Collectors.toList());
		return new ExpressionComputation<>(expressions, base, exp);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> floor(
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

	default <T extends PackedCollection<?>> ExpressionComputation<T> sum(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		int size = shape(input).getTotalSize();
		Function<List<MultiExpression<Double>>, Expression<Double>> expression= np ->
			new Sum(IntStream.range(0, size).mapToObj(i -> np.get(1).getValue(i)).toArray(Expression[]::new));
		return new ExpressionComputation<>(List.of(expression), input);
	}

	static CollectionFeatures getInstance() {
		return new CollectionFeatures() { };
	}
}
