/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.ArithmeticSequenceExpression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.ComparisonExpression;
import io.almostrealism.collect.ConditionalFilterExpression;
import io.almostrealism.collect.IndexOfPositionExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Difference;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Logarithm;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerFeatures;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.bool.GreaterThanCollection;
import org.almostrealism.bool.LessThanCollection;
import org.almostrealism.collect.computations.AggregatedProducerComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProvider;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.ConstantRepeatedProducerComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.DynamicIndexProjectionProducerComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionMap;
import org.almostrealism.collect.computations.PackedCollectionPad;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.TraversableRepeatedProducerComputation;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.io.Console;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface CollectionFeatures extends ExpressionFeatures, ProducerFeatures {
	boolean enableShapelessWarning = false;
	boolean enableIndexProjectionDeltaAlt = true;
	boolean enableTraversableRepeated = true;
	boolean enableCollectionIndexSize = false;
	boolean enableAxisAlignment = true;
	boolean enableVariableRepeat = false;
	boolean enableStrictAssignmentSize = true;

	Console console = Computation.console.child();

	default TraversalPolicy shape(int... dims) { return new TraversalPolicy(dims); }
	default TraversalPolicy position(int... dims) { return new TraversalPolicy(true, dims); }

	default TraversalPolicy shape(Supplier s) {
		if (s instanceof Shape) {
			return ((Shape) s).getShape();
		} else {
			if (enableShapelessWarning) {
				console.warn(s.getClass() + " does not have a Shape");
			}

			return shape(1);
		}
	}

	default TraversalPolicy shape(TraversableExpression t) {
		if (t instanceof Shape) {
			return ((Shape) t).getShape();
		} else {
			if (enableShapelessWarning) {
				System.out.println("WARN: " + t.getClass() + " does not have a Shape");
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


	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target) {
		return padDimensions(shape, min, target, false);
	}

	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target, boolean post) {
		if (shape.getDimensions() < min) {
			return shape;
		}

		while (shape.getDimensions() < target) {
			shape = post ? shape.appendDimension(1) : shape.prependDimension(1);
		}

		return shape;
	}

	default PackedCollection<?> pack(double... values) {
		return PackedCollection.of(values);
	}

	default PackedCollection<?> pack(float... values) {
		return PackedCollection.of(IntStream.range(0, values.length).mapToDouble(i -> values[i]).toArray());
	}

	default PackedCollection<?> empty(TraversalPolicy shape) {
		return new PackedCollection<>(shape);
	}

	default <T> Producer<T> p(T value) {
		if (value instanceof Producer) {
			throw new IllegalArgumentException();
		} else if (value instanceof Shape) {
			return new CollectionProviderProducer((Shape) value);
		} else {
			return () -> new Provider<>(value);
		}
	}

	default <T, V> Provider<T> p(Supplier<V> ev, Function<V, T> func) {
		if (ev instanceof CollectionProvider) {
			return new CollectionProvider(null) {
				@Override
				public T get() {
					return func.apply((V) ((CollectionProvider) ev).get());
				}
			};
		} else {
			return new Provider<>(null) {
				@Override
				public T get() {
					return func.apply((V) ((Provider) ev).get());
				}
			};
		}
	}

	@Override
	default <T> Producer<?> delegate(Producer<T> producer) {
		if (producer == null) return null;

		return new DelegatedCollectionProducer<>(c(producer));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(double... values) {
		PackedCollection<?> c = PackedCollection.factory().apply(values.length);
		c.setMem(0, values);
		return (CollectionProducer<T>) c(c);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(TraversalPolicy shape, double... values) {
		if (values.length != shape.getTotalSize()) {
			throw new IllegalArgumentException("Wrong number of values for shape");
		}

		PackedCollection<T> c = new PackedCollection<>(shape);
		c.setMem(0, values);
		return (CollectionProducerComputation<T>) c(c);
	}

	default <T extends PackedCollection<?>> CollectionProducerBase<T, CollectionProducer<T>> c(TraversalPolicy shape, Evaluable<PackedCollection<?>> ev) {
		return c(new CollectionProducerBase<>() {
			@Override
			public Evaluable get() { return ev; }

			@Override
			public TraversalPolicy getShape() {
				return shape;
			}

			@Override
			public Producer<Object> traverse(int axis) {
				return (CollectionProducer) CollectionFeatures.this.traverse(axis, (Producer) this);
			}

			@Override
			public Producer reshape(TraversalPolicy shape) {
				return CollectionFeatures.this.reshape(shape, this);
			}
		});
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(T value) {
		if (ExpressionComputation.enableTraversableFixed) {
			return DefaultTraversableExpressionComputation.fixed(value);
		} else {
			return ExpressionComputation.fixed(value);
		}
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> cp(V value) {
		return c(p(value));
	}

	default <T extends MemoryData> Assignment<T> a(String shortDescription, Producer<T> result, Producer<T> value) {
		Assignment<T> a = a(result, value);
		a.getMetadata().setShortDescription(shortDescription);
		return a;
	}

	default <T extends MemoryData> Assignment<T> a(Producer<T> result, Producer<T> value) {
		TraversalPolicy resultShape = shape(result);
		TraversalPolicy valueShape = shape(value);

		if (resultShape.getSize() != valueShape.getSize()) {
			int axis = TraversalPolicy.compatibleAxis(resultShape, valueShape, enableStrictAssignmentSize);
			if (axis == -1) {
				throw new IllegalArgumentException();
			} else if (axis < resultShape.getTraversalAxis()) {
				console.warn("Assignment destination (" + resultShape.getCountLong() +
						") adjusted to match source (" + valueShape.getCountLong() + ")");
			}

			return a(traverse(axis, (Producer) result), value);
		}

		return new Assignment<>(shape(result).getSize(), result, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> concat(Producer<PackedCollection<?>>... producers) {
		return (CollectionProducerComputation) concat(0, producers);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> concat(int axis, Producer<PackedCollection<?>>... producers) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expressions[] = IntStream.range(0, producers.length)
				.mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>) args -> args.get(i + 1).getValueRelative(0))
				.toArray(Function[]::new);
		if (axis != 0) {
			return new ExpressionComputation(shape(producers.length, 1), List.of(expressions), producers).traverse(axis);
		} else {
			return new ExpressionComputation(shape(producers.length, 1), List.of(expressions), producers);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> concat(int axis, int depth, Producer<PackedCollection<?>>... producers) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expressions = IntStream.range(0, producers.length)
				.mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>) args -> args.get(i + 1).getValueRelative(0))
				.collect(Collectors.toList());

		for (int i = 0; i < producers.length; i++) {
			for (int j = 0; j < depth; j++) {
				int x = i;
				int y = j;
				expressions.add(args -> args.get(x + 1).getValueRelative(y));
			}
		}

		if (axis != 0) {
			return new ExpressionComputation(shape(producers.length, depth), expressions, producers).traverse(axis);
		} else {
			return new ExpressionComputation(shape(producers.length, depth), expressions, producers);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> concat(TraversalPolicy shape, Producer<PackedCollection<?>>... producers) {
		List<TraversalPolicy> shapes = Stream.of(producers)
				.map(this::shape)
				.filter(s -> s.getDimensions() == shape.getDimensions())
				.collect(Collectors.toList());

		if (shapes.size() != producers.length) {
			throw new IllegalArgumentException("All inputs must have the same number of dimensions");
		}

		int axis = -1;

		for (TraversalPolicy s : shapes) {
			for (int d = 0; d < shape.getDimensions(); d++) {
				if (s.length(d) != shape.length(d)) {
					if (axis < 0) axis = d;

					if (axis != d) {
						throw new IllegalArgumentException("Cannot concatenate over more than one axis at once");
					}
				}
			}
		}

		int total = 0;
		List<TraversalPolicy> positions = new ArrayList<>();

		for (TraversalPolicy s : shapes) {
			if (total >= shape.length(axis)) {
				throw new IllegalArgumentException("The result is not large enough to concatenate all inputs");
			}

			int pos[] = new int[s.getDimensions()];
			pos[axis] = total;
			total += s.length(axis);
			positions.add(new TraversalPolicy(true, pos));
		}

		return add(IntStream.range(0, shapes.size())
				.mapToObj(i -> pad(shape, positions.get(i), producers[i]))
				.collect(Collectors.toList()));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> c(Producer producer) {
		if (producer instanceof CollectionProducer) {
			return (CollectionProducer<T>) producer;
		} else if (producer instanceof Shape) {
			return new ReshapeProducer(((Shape) producer).getShape().getTraversalAxis(), producer);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(Producer supplier, int index) {
		return new ExpressionComputation<>(List.of(args -> args.get(1).getValueRelative(index)), supplier);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(Producer<T> collection,
																			   Producer<PackedCollection<?>> index) {
		if (enableCollectionIndexSize) {
			return c(shape(index), collection, index);
		} else {
			return c(shape(collection), collection, index);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(TraversalPolicy shape,
																			   Producer<T> collection,
																			   Producer<PackedCollection<?>> index) {
		DefaultTraversableExpressionComputation exp = new DefaultTraversableExpressionComputation<>("valueAtIndex", shape,
				args -> CollectionExpression.create(shape, idx -> args[1].getValueAt(args[2].getValueAt(idx))),
				(Supplier) collection, index);
		if (shape.getTotalSize() == 1) {
			exp.setShortCircuit(args -> {
				Evaluable<? extends PackedCollection> out = ag -> new PackedCollection(1);
				Evaluable<? extends PackedCollection> c = collection.get();
				Evaluable<? extends PackedCollection> i = index.get();

				PackedCollection<?> col = c.evaluate(args);
				PackedCollection idx = i.evaluate(args);
				PackedCollection dest = out.evaluate(args);
				dest.setMem(col.toDouble((int) idx.toDouble(0)));
				return dest;
			});
		}

		return exp;
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(Producer<T> collection,
																			   TraversalPolicy collectionShape,
																			   Producer<PackedCollection<?>>... pos) {
		return c(shape(pos[0]), collection, collectionShape, pos);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(TraversalPolicy outputShape,
																			   Producer<T> collection,
																			   TraversalPolicy collectionShape,
																			   Producer<PackedCollection<?>>... pos) {
		return c(outputShape, collection, index(collectionShape, pos));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> index(TraversalPolicy shapeOf,
																				   Producer<PackedCollection<?>>... pos) {
		return index(shape(pos[0]), shapeOf, pos);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> index(TraversalPolicy shape,
																				   TraversalPolicy shapeOf,
																				   Producer<PackedCollection<?>>... pos) {
		return new DefaultTraversableExpressionComputation<>("index", shape,
						(args) ->
								new IndexOfPositionExpression(shape, shapeOf,
										Stream.of(args).skip(1).toArray(TraversableExpression[]::new)), pos);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection<?>> function) {
		return new DynamicCollectionProducer(shape, function);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection<?>> function, boolean kernel) {
		return new DynamicCollectionProducer(shape, function, kernel);
	}

	default <T, P extends Producer<T>> Producer<T> alignTraversalAxes(
			List<Producer<T>> producers, BiFunction<TraversalPolicy, List<Producer<T>>, P> processor) {
		return TraversalPolicy
				.alignTraversalAxes(
						producers.stream().map(this::shape).collect(Collectors.toList()),
						producers,
						(i, p) -> traverse(i, (Producer) p),
						(i, p) -> {
							if (enableVariableRepeat || Countable.isFixedCount(p)) {
								return (Producer) repeat(i, (Producer) p);
							} else {
								return p;
							}
						},
						processor);
	}

	default <T> TraversalPolicy largestTotalSize(List<Producer<T>> producers) {
		return producers.stream().map(this::shape).max(Comparator.comparing(TraversalPolicy::getTotalSizeLong)).get();
	}

	default <T> long lowestCount(List<Producer<T>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).min().getAsLong();
	}

	default <T> long highestCount(List<Producer<T>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).max().getAsLong();
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> traverse(int axis, Producer<T> producer) {
		return new ReshapeProducer(axis, producer);
	}

	default <T extends PackedCollection<?>> Producer each(Producer<T> producer) {
		return traverseEach(producer);
	}

	default <T extends PackedCollection<?>> Producer traverseEach(Producer<T> producer) {
		return new ReshapeProducer(((Shape) producer).getShape().traverseEach(), producer);
	}

	default <T extends Shape<T>> Producer reshape(TraversalPolicy shape, Producer producer) {
		return new ReshapeProducer<>(shape, producer);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subset(TraversalPolicy shape, Producer<?> collection, int... position) {
		return new PackedCollectionSubset<>(shape, collection, position);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subset(TraversalPolicy shape, Producer<?> collection, Expression... position) {
		return new PackedCollectionSubset<>(shape, collection, position);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subset(TraversalPolicy shape, Producer<?> collection, Producer<?> position) {
		return new PackedCollectionSubset<>(shape, collection, position);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> repeat(int repeat, Producer<?> collection) {
		return new PackedCollectionRepeat<>(repeat, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> repeat(int axis, int repeat, Producer<?> collection) {
		return repeat(repeat, traverse(axis, (Producer) collection));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, Producer<?> collection) {
		return enumerate(axis, len, len, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride, Producer<?> collection) {
		return enumerate(axis, len, stride, 1, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride, int repeat, Producer<?> collection) {
		CollectionProducerComputation<T> result = null;

		TraversalPolicy inputShape = shape(collection);
		int traversalDepth = PackedCollectionEnumerate.enableDetectTraversalDepth ? inputShape.getTraversalAxis() : 0;

		inputShape = inputShape.traverse(traversalDepth).item();
		axis = axis - traversalDepth;

		for (int i = 0; i < repeat; i++) {
			TraversalPolicy shp = inputShape.traverse(axis).replaceDimension(len);
			TraversalPolicy st = inputShape.traverse(axis).stride(stride);
			result = enumerate(shp, st, result == null ? collection : result);
			inputShape = shape(result).traverse(traversalDepth).item();
		}

		return result;
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape, Producer<?> collection) {
		return new PackedCollectionEnumerate<>(shape, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		return new PackedCollectionEnumerate<>(shape, stride, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> pad(int axes[], int depth, Producer<?> collection) {
		TraversalPolicy shape = shape(collection);
		if (shape.getOrder() != null) {
			throw new UnsupportedOperationException();
		}

		int depths[] = new int[shape.getDimensions()];
		for (int i = 0; i < axes.length; i++) {
			depths[axes[i]] = depth;
		}

		return pad(collection, depths);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> pad(Producer<?> collection, int... depths) {
		TraversalPolicy shape = shape(collection);

		int dims[] = new int[shape.getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			dims[i] = shape.length(i) + 2 * depths[i];
		}

		shape = new TraversalPolicy(dims).traverse(shape.getTraversalAxis());
		return pad(shape, new TraversalPolicy(true, depths), collection);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> pad(TraversalPolicy shape,
																				 Producer<?> collection,
																				 int... pos) {
		int traversalAxis = shape.getTraversalAxis();

		if (traversalAxis == shape.getDimensions()) {
			return pad(shape, new TraversalPolicy(true, pos), collection);
		} else {
			return (CollectionProducer<T>) pad(shape.traverseEach(), new TraversalPolicy(true, pos), collection)
					.traverse(traversalAxis);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> pad(TraversalPolicy shape,
																				 TraversalPolicy position,
																				 Producer<?> collection) {
		return new PackedCollectionPad<>(shape, position, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(Producer<?> collection, Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return new PackedCollectionMap<>(collection, (Function) mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(TraversalPolicy itemShape, Producer<?> collection, Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return new PackedCollectionMap<>(shape(collection).replace(itemShape), collection, (Function) mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> reduce(Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return map(shape(1), collection, (Function) mapper);
	}

	/**
	 * @deprecated Use {@link #repeat(int, Producer)}
	 */
	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducer<T> expand(int repeat, Producer<?> collection) {
		// return expand(repeat, collection, v -> v.repeat(repeat));
		return (CollectionProducer) repeat(repeat, collection).consolidate();
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> expand(int repeat, Producer<?> collection, Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return map(shape(collection).item().prependDimension(repeat), collection, mapper);
	}

	default Random rand(int... dims) { return rand(shape(dims)); }
	default Random rand(TraversalPolicy shape) { return new Random(shape); }

	default Random randn(int... dims) { return randn(shape(dims)); }
	default Random randn(TraversalPolicy shape) { return new Random(shape, true); }

	default DefaultTraversableExpressionComputation<PackedCollection<?>> compute(CollectionExpression expression) {
		return new DefaultTraversableExpressionComputation<>(null, expression.getShape(), expression);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> compute(
			String name, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Evaluable<T> shortCircuit, Producer<T>... arguments) {
		CollectionProducerComputationBase<T, T> c =
				(CollectionProducerComputationBase) alignTraversalAxes(List.of(arguments),
				(shape, args) -> new DefaultTraversableExpressionComputation(
						name, largestTotalSize(args), expression.apply(shape),
							args.toArray(Supplier[]::new))
						.setShortCircuit(shortCircuit));
		long count = highestCount(List.of(arguments));

		if (c.getShape().getCountLong() != count) {
			for (int i = 0; i <= c.getShape().getDimensions(); i++) {
				if (c.getShape().traverse(i).getCountLong() == count) {
					return c.traverse(i);
				}
			}
		}

		return c;
	}

	default CollectionProducerComputation<PackedCollection<?>> integers() {
		return new DefaultTraversableExpressionComputation<>("integers", shape(1),
				args -> new ArithmeticSequenceExpression(shape(1))) {
			@Override
			public boolean isFixedCount() {
				return false;
			}
		};
	}


	default CollectionProducerComputation<PackedCollection<?>> integers(int from, int to) {
		int len = to - from;
		TraversalPolicy shape = shape(len).traverseEach();

		return new DefaultTraversableExpressionComputation<>("integers", shape,
				args -> new ArithmeticSequenceExpression(shape, from, 1));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> add(Producer<T> a, Producer<T> b) {
		if (a == null || b == null) {
			throw new IllegalArgumentException();
		}

		return compute("add", shape -> args ->
						sum(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				null, a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> add(List<Producer<?>> operands) {
		if (operands.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException();
		}

		return compute("add", shape -> args ->
						sum(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				null, operands.toArray(new Producer[0]));
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeAdd(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		Function<List<ArrayVariable<Double>>, Expression<Double>> expressions[] =
				IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>)
								np -> Sum.of(np.get(1).getValueRelative(i), np.get(2).getValueRelative(i)))
						.toArray(Function[]::new);
		return new ExpressionComputation<>(List.of(expressions), (Supplier) a, (Supplier) b);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> subtract(Producer<T> a, Producer<T> b) {
		return add(a, minus(b));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> subtractIgnoreZero(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(a);
		int size = shape(b).getSize();

		if (shape.getSize() != size) {
			if (shape.getSize() == 1) {
				return subtractIgnoreZero(a, traverseEach((Producer) b));
			} else if (size == 1) {
				return subtractIgnoreZero(traverseEach((Producer) a), b);
			}

			throw new IllegalArgumentException("Cannot subtract a collection of size " + size +
					" from a collection of size " + shape.getSize());
		}

		return new DefaultTraversableExpressionComputation<>(null, shape,
				args -> CollectionExpression.create(shape, index -> {
					Expression<Double> difference = conditional(args[1].getValueAt(index).eq(args[2].getValueAt(index)),
							epsilon(),
							Difference.of(args[1].getValueAt(index), args[2].getValueAt(index)));
					return conditional(args[1].getValueAt(index).eq(e(0.0)), e(0.0), difference);
				}),
				(Supplier) a, (Supplier) b);
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeSubtract(Producer<T> a, Producer<T> b) {
		return relativeAdd(a, minus(b));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> multiply(
			Producer<T> a, Producer<T> b) {
		return multiply(a, b, null);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> multiply(
			Producer<T> a, Producer<T> b,
			Evaluable<T> shortCircuit) {
		return compute("multiply", shape -> args ->
					product(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				shortCircuit, a, b);
	}

	@Deprecated
	default <T extends PackedCollection<?>> ExpressionComputation<T> relativeMultiply(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b,
																					  Evaluable<T> shortCircuit) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		return relativeMultiply(shape, a, b, shortCircuit);
	}

	@Deprecated
	default <T extends PackedCollection<?>> ExpressionComputation<T> relativeMultiply(TraversalPolicy shape,
																					  Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b,
																					  Evaluable<T> shortCircuit) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expressions[] =
				IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>)
								np -> (Expression<Double>) Product.of(np.get(1).getValueRelative(i), np.get(2).getValueRelative(i)))
						.toArray(Function[]::new);
		ExpressionComputation<T> exp = new ExpressionComputation<>(shape, List.of(expressions), a, b);
		exp.setShortCircuit(shortCircuit);
		return exp;
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> divide(Producer<T> a, Producer<T> b) {
		return compute("divide", shape -> (args) -> CollectionExpression.create(shape, index -> Quotient.of(args[1].getValueAt(index), args[2].getValueAt(index))), null, a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> minus(Producer<T> a) {
		return new DefaultTraversableExpressionComputation<>(null, shape(a),
				args -> CollectionExpression.create(shape(a), index -> Minus.of(args[1].getValueAt(index))),
				(Supplier) a);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> sqrt(Producer<T> value) {
		return pow(value, c(0.5));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> pow(Producer<T> base, Producer<T> exp) {
		return compute("pow", shape -> (args) ->
				CollectionExpression.create(shape, index -> Exponent.of(args[1].getValueAt(index), args[2].getValueAt(index))),
				null, base, exp);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> exp(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation<>(
				"exp", shape,
				args -> new UniformCollectionExpression(shape, in -> Exp.of(in[0]), args[1]),
				(Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> expIgnoreZero(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		TraversalPolicy shape = shape(value);

		return new DefaultTraversableExpressionComputation<>(
				"expIgnoreZero", shape,
				args ->
						new ConditionalFilterExpression(shape,
								Expression::eqZero, Exp::of,
								false, args[1]),
				(Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> log(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation<>(
				"log", shape,
				args -> new UniformCollectionExpression(shape, in -> Logarithm.of(in[0]), args[1]),
				(Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> sq(Producer<T> value) {
		return multiply(value, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> floor(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation<>(
				"floor", shape,
				args -> new UniformCollectionExpression(shape, in -> Floor.of(in[0]), args[1]),
				(Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> min(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation<>("min", shape,
				args -> new UniformCollectionExpression(shape,
								in -> Min.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> max(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation<>("max", shape,
				args -> new UniformCollectionExpression(shape,
								in -> Max.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> rectify(Producer<T> a) {
		return compute("rectify", shape -> args ->
						rectify(shape, args[1]),
				null, a);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> mod(Producer<T> a, Producer<T> b) {
		if (enableAxisAlignment) {
			return compute("mod", shape -> args ->
					mod(shape, args[1], args[2]),
					null, a, b);
		} else {
			TraversalPolicy shape;

			if (shape(a).getSize() == shape(b).getSize()) {
				shape = shape(a);
			} else {
				shape = shape(1);
			}

			return new DefaultTraversableExpressionComputation<>("mod", shape,
					args -> new UniformCollectionExpression(shape,
								in -> Mod.of(in[0], in[1]), args[1], args[2]),
					(Supplier) a, (Supplier)  b);
		}
	}

	@Deprecated
	default <T extends PackedCollection<?>> ExpressionComputation<T> relativeMod(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				Mod.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> bound(Supplier<Evaluable<? extends PackedCollection<?>>> a, double min, double max) {
		return min(max(a, c(min)), c(max));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> abs(Producer<T> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation<>(
				"abs", shape,
				args -> new UniformCollectionExpression(shape, in -> new Absolute(in[0]), args[1]),
				(Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> magnitude(Producer<T> vector) {
		if (shape(vector).getSize() == 1) {
			return abs(vector);
		} else {
			return sq(vector).sum().sqrt();
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> max(Producer<T> input) {
		DynamicIndexProjectionProducerComputation<T> projection =
				new DynamicIndexProjectionProducerComputation<>(shape(input).replace(shape(1)),
						(args, idx) -> args[2].getValueAt(idx),
						true, input, indexOfMax(input));

		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		AggregatedProducerComputation c = new AggregatedProducerComputation<>(shape.replace(shape(1)), size,
				(args, index) -> minValue(),
				(out, arg) -> Max.of(out, arg),
				(Supplier) input);
		if (enableIndexProjectionDeltaAlt) c.setDeltaAlternate(projection);
		return c;
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> indexOfMax(Producer<T> input) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		if (enableTraversableRepeated) {
			return new TraversableRepeatedProducerComputation<>(shape.replace(shape(1)), size,
					(args, index) -> e(0),
					(args, currentIndex) -> index ->
							conditional(args[1].getValueRelative(index)
											.greaterThan(args[1].getValueRelative(currentIndex)),
									index, currentIndex),
					(Supplier) input);
		} else {
			return new ConstantRepeatedProducerComputation<>(shape.replace(shape(1)), size,
					(args, index) -> e(0),
					(args, index) -> {
						Expression<?> currentIndex = args[0].getValueRelative(e(0));
						return conditional(args[1].getValueRelative(index)
										.greaterThan(args[1].getValueRelative(currentIndex)),
								index, currentIndex);
					},
					(Supplier) input);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> sum(Producer<T> input) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		AggregatedProducerComputation<T> sum = new AggregatedProducerComputation<>(shape.replace(shape(1)), size,
				(args, index) -> e(0.0),
				(out, arg) -> out.add(arg),
				(Supplier) input);
		sum.setReplaceLoop(true);
		return sum;
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> mean(Producer<T> input) {
		return sum(input).divide(c(shape(input).getSize()));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> subtractMean(Producer<T> input) {
		Producer<T> mean = mean(input);
		return subtract(input, mean);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> variance(Producer<T> input) {
		return mean(sq(subtractMean(input)));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> sigmoid(Producer<T> input) {
		return divide(c(1.0), minus(input).exp().add(c(1.0)));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> greaterThan(Producer<T> a, Producer<T> b,
																			  Producer<T> trueValue, Producer<T> falseValue) {
		return greaterThan(a, b, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> greaterThan(Producer<?> a, Producer<?> b,
																			  Producer<T> trueValue, Producer<T> falseValue,
																			  boolean includeEqual) {
		return (CollectionProducer<T>) new GreaterThanCollection(a, b, trueValue, falseValue, includeEqual);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> equals(Producer<?> a, Producer<?> b,
																		Producer<T> trueValue, Producer<T> falseValue) {
		return compute("equals",
				shape -> args ->
						conditional(shape, equals(shape, args[1], args[2]), args[3], args[4]),
				null, (Producer) a, (Producer) b, (Producer) trueValue, (Producer) falseValue);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> greaterThanConditional(Producer<?> a, Producer<?> b,
																						 Producer<T> trueValue, Producer<T> falseValue) {
		return greaterThanConditional(a, b, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> greaterThanConditional(Producer<?> a, Producer<?> b,
																			   Producer<T> trueValue, Producer<T> falseValue,
																			   boolean includeEqual) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation<>("greaterThan", shape,
				args -> new ComparisonExpression(shape,
						(l, r) -> greater(l, r, includeEqual),
						args[1], args[2], args[3], args[4]),
				(Supplier) a, (Supplier) b,
				(Supplier) trueValue, (Supplier) falseValue);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lessThan(Producer<T> a, Producer<T> b,
																		   Producer<T> trueValue, Producer<T> falseValue) {
		return lessThan(a, b, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lessThan(Producer<?> a, Producer<?> b,
																		   Producer<T> trueValue, Producer<T> falseValue,
																		   boolean includeEqual) {
		return (CollectionProducer<T>) new LessThanCollection(a, b, trueValue, falseValue, includeEqual);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> subdivide(Producer<T> input, Function<Producer<T>, CollectionProducer<T>> operation) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		int split = KernelPreferences.getWorkSubdivisionMinimum();

		if (size > split) {
			while (split > 1) {
				CollectionProducer<T> slice = subdivide(input, operation, split);
				if (slice != null) return slice;
				split /= 2;
			}
		}

		return null;
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> subdivide(Producer<T> input, Function<Producer<T>, CollectionProducer<T>> operation, int sliceSize) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		if (size % sliceSize == 0) {
			TraversalPolicy split = shape.replace(shape(sliceSize, size / sliceSize)).traverse();
			return operation.apply(operation.apply((Producer<T>) reshape(split, input)).consolidate());
		}

		return null;
	}

	static CollectionFeatures getInstance() {
		return new CollectionFeatures() { };
	}
}
