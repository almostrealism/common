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
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.KernelExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Difference;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.bool.GreaterThanCollection;
import org.almostrealism.bool.LessThanCollection;
import org.almostrealism.collect.computations.ArrayVariableComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionMap;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.collect.computations.RepeatedCollectionProducerComputation;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.TraversableExpressionComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.computations.Assignment;

import java.util.List;
import java.util.function.BiFunction;
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
		if (value instanceof Producer) {
			throw new IllegalArgumentException();
		} else if (value instanceof Shape) {
			return new CollectionProviderProducer((Shape) value);
		} else {
			return () -> new Provider<>(value);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(double... values) {
		PackedCollection<T> c = new PackedCollection<>(values.length);
		c.setMem(0, values);
		return (CollectionProducerComputation<T>) c(c);
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
				return (CollectionProducer) CollectionFeatures.this.reshape(shape, this);
			}
		});
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(T value) {
		if (ExpressionComputation.enableTraversableFixed) {
			return TraversableExpressionComputation.fixed(value);
		} else {
			return ExpressionComputation.fixed(value);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> cp(T value) {
		return c(p(value));
	}

	default <T extends MemoryData> Assignment<T> a(String shortDescription, Producer<T> result, Producer<T> value) {
		Assignment<T> a = a(result, value);
		a.getMetadata().setShortDescription(shortDescription);
		return a;
	}

	default <T extends MemoryData> Assignment<T> a(Producer<T> result, Producer<T> value) {
		return new Assignment<>(shape(result).getSize(), result, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> concat(Producer<PackedCollection<?>>... producers) {
		return (CollectionProducerComputation) concat(0, producers);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> concat(int axis, Producer<PackedCollection<?>>... producers) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expressions = IntStream.range(0, producers.length)
				.mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>) args -> args.get(i + 1).getValueRelative(0))
				.collect(Collectors.toList());
		if (axis != 0) {
			return new ExpressionComputation(shape(producers.length, 1), expressions, producers).traverse(axis);
		} else {
			return new ExpressionComputation(shape(producers.length, 1), expressions, producers);
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
		return c(shape(collection), collection, index);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> c(TraversalPolicy shape,
																			   Producer<T> collection,
																			   Producer<PackedCollection<?>> index) {
		TraversableExpressionComputation exp = new TraversableExpressionComputation<>(shape,
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

	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection<?>> function) {
		return new DynamicCollectionProducer(shape, function);
	}

	default CollectionProducerComputation<PackedCollection<?>> kernel(IntFunction<Expression> kernelIndex, TraversalPolicy shape,
																	  KernelExpression kernel, Producer... arguments) {
		Expression index = kernelIndex.apply(0);
		Expression pos[] = shape.position(index);

		return new ArrayVariableComputation<>(
				shape.traverseEach(), List.of(args -> {
					CollectionVariable vars[] = new CollectionVariable[args.size()];
					for (int i = 0; i < vars.length; i++) {
						vars[i] = args.get(i) instanceof CollectionVariable ? (CollectionVariable) args.get(i) : null;
					}

					return kernel.apply(vars, pos);
				}), arguments);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> traverse(int axis, Producer<T> producer) {
		return new ReshapeProducer(axis, producer);
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

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, Producer<?> collection) {
		return enumerate(axis, len, len, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride, Producer<?> collection) {
		return enumerate(axis, len, stride, 1, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride, int repeat, Producer<?> collection) {
		CollectionProducerComputation<T> result = null;

		TraversalPolicy inputShape = shape(collection);

		for (int i = 0; i < repeat; i++) {
			TraversalPolicy shp = inputShape.traverse(axis).replaceDimension(len);
			TraversalPolicy st = inputShape.traverse(axis).stride(stride);
			result = enumerate(shp, st, result == null ? collection : result);
			inputShape = shape(result);
		}

		return result;
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape, Producer<?> collection) {
		return new PackedCollectionEnumerate<>(shape, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		return new PackedCollectionEnumerate<>(shape, stride, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(Producer<?> collection, Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducerComputation<?>> mapper) {
		return new PackedCollectionMap<>(collection, (Function) mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(TraversalPolicy itemShape, Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return new PackedCollectionMap<>(shape(collection).replace(itemShape), collection, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> reduce(Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return map(shape(1), collection, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> expand(int repeat, Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return map(shape(collection).item().prependDimension(repeat), collection, mapper);
	}

	default Random rand(int... dims) { return rand(shape(dims)); }
	default Random rand(TraversalPolicy shape) { return new Random(shape); }

	default Random randn(int... dims) { return randn(shape(dims)); }
	default Random randn(TraversalPolicy shape) { return new Random(shape, true); }

	default CollectionProducerComputation<PackedCollection<?>> integers(int from, int to) {
		int len = to - from;
		return new ExpressionComputation<>(shape(len).traverseEach(),
				List.of(np -> new Sum(new DoubleConstant((double) from), KernelSupport.index())));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> add(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		return add(shape.getSize(), (Supplier) a, (Supplier) b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> add(int depth,
																		 				Supplier<Evaluable<? extends PackedCollection<?>>> a,
																						Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		TraversalPolicy shape = shape(a);
		int size = shape(b).getSize();

		if (shape.getSize() != size) {
			if (shape.getSize() == 1) {
				return add(1, a, traverseEach((Producer) b));
			} else if (size == 1) {
				return add(1, traverseEach((Producer) a), b);
			}

			throw new IllegalArgumentException("Cannot add a collection of size " + shape.getSize() +
					" with a collection of size " + size);
		}

		TraversableExpressionComputation exp = new TraversableExpressionComputation<>(shape,
				args -> CollectionExpression.create(shape, index ->
						new Sum(args[1].getValueAt(index), args[2].getValueAt(index))),
				(Supplier) a, (Supplier) b);
		// exp.setShortCircuit(shortCircuit);
		return exp;
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeAdd(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expressions =
				IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>)
								np -> new Sum<>(np.get(1).getValueRelative(i), np.get(2).getValueRelative(i)))
						.collect(Collectors.toList());
		return new ExpressionComputation<>(expressions, (Supplier) a, (Supplier) b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> subtract(Producer<T> a, Producer<T> b) {
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

		return new TraversableExpressionComputation<>(shape,
				args -> CollectionExpression.create(shape, index -> {
					Expression<Double> difference = conditional(args[1].getValueAt(index).eq(args[2].getValueAt(index)),
							e(Hardware.getLocalHardware().getPrecision().epsilon()),
							new Difference(args[1].getValueAt(index), args[2].getValueAt(index)));
					return conditional(args[1].getValueAt(index).eq(e(0.0)), e(0.0), difference);
				}),
				(Supplier) a, (Supplier) b);
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeSubtract(Producer<T> a, Producer<T> b) {
		return relativeAdd(a, minus(b));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> multiply(
			Producer<T> a, Producer<T> b) {
		return multiply(a, b, null);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> multiply(
			Producer<T> a, Producer<T> b,
			Evaluable<T> shortCircuit) {
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

		TraversableExpressionComputation exp = new TraversableExpressionComputation<>(shape,
				args -> CollectionExpression.create(shape, index -> new Product(args[1].getValueAt(index), args[2].getValueAt(index))),
				(Supplier) a, (Supplier) b);
		exp.setShortCircuit(shortCircuit);
		return exp;
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
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expressions =
				IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>)
								np -> new Product<>(np.get(1).getValueRelative(i), np.get(2).getValueRelative(i)))
						.collect(Collectors.toList());
		ExpressionComputation<T> exp = new ExpressionComputation<>(shape, expressions, a, b);
		exp.setShortCircuit(shortCircuit);
		return exp;
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> divide(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(a);
		int size = shape(b).getSize();
		if (shape.getSize() != size) {
			throw new IllegalArgumentException("Cannot divide a collection of size " + shape.getSize() +
					" by a collection of size " + size);
		}

		if (shape(b).getCount() > shape(a).getCount()) shape = shape(b);

		return new TraversableExpressionComputation<>(shape,
				(args, index) -> new Quotient(args[1].getValueAt(index), args[2].getValueAt(index)),
				(Supplier) a, (Supplier) b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> minus(Producer<T> a) {
		return new TraversableExpressionComputation<>(shape(a),
				args -> CollectionExpression.create(shape(a), index -> new Minus(args[1].getValueAt(index))),
				(Supplier) a);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> pow(Producer<T> base, Producer<T> exp) {
		if (shape(base).getSize() != 1 && shape(exp).getSize() != 1 && shape(base).getSize() != shape(exp).getSize()) {
			throw new IllegalArgumentException("Cannot raise a collection of size " + shape(base).getSize() +
					" to a collection of size " + shape(exp).getSize());
		}

		TraversalPolicy shape = shape(base);
		if (shape.getSize() == 1 && shape(exp).getSize() > 1) shape = shape(exp);

		return new TraversableExpressionComputation<>(shape,
				(BiFunction<TraversableExpression[], Expression, Expression>) (args, index) ->
						new Exponent(args[1].getValueAt(index), args[2].getValueAt(index)),
				(Supplier) base, (Supplier) exp);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> exp(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return new TraversableExpressionComputation<>(
				shape(value), (args, index) -> new Exp(args[1].getValueAt(index)), (Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> expIgnoreZero(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return new TraversableExpressionComputation<>(
				shape(value), (args, index) ->
					conditional(args[1].getValueAt(index).eq(e(0.0)), e(0.0), new Exp(args[1].getValueAt(index))),
				(Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> floor(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return new TraversableExpressionComputation<>(
				shape(value), (args, index) -> new Floor(args[1].getValueAt(index)), (Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> min(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		return new TraversableExpressionComputation<>(shape,
				(args, index) -> new Min(args[1].getValueAt(index), args[2].getValueAt(index)),
				a, b);
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeMin(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				new Min(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> max(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		TraversalPolicy shape = shape(1);
		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		}

		return new TraversableExpressionComputation<>(shape,
				(args, index) -> new Max(args[1].getValueAt(index), args[2].getValueAt(index)),
				a, b);
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeMax(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				new Max(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	default <T extends PackedCollection<?>> ExpressionComputation<T> mod(Supplier<Evaluable<? extends PackedCollection<?>>> a, Supplier<Evaluable<? extends PackedCollection<?>>> b) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				new Mod(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));
		return new ExpressionComputation<>(List.of(expression), a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> bound(Supplier<Evaluable<? extends PackedCollection<?>>> a, double min, double max) {
		return min(max(a, c(min)), c(max));
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeBound(Supplier<Evaluable<? extends PackedCollection<?>>> a, double min, double max) {
		return relativeMin(relativeMax(a, c(min)), c(max));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> max(Producer<T> input) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		if (KernelPreferences.isEnableSubdivision()) {
			CollectionProducerComputationBase<T, T> sum = (CollectionProducerComputationBase<T, T>) subdivide(input, this::max);
			if (sum != null) return sum;
		}

		if (KernelPreferences.isPreferLoops()) {
			return new RepeatedCollectionProducerComputation<>(shape.replace(shape(1)),
					(args, index) ->
							e(Hardware.getLocalHardware().getPrecision().minValue()),
					(args, index) ->
							index.lessThan(e(size)),
					(args, index) ->
							new Max(args[0].getValueRelative(e(0)), (args[1].getValueRelative(index))),
					(Supplier) input);
		} else {
			return new TraversableExpressionComputation<>(shape.replace(shape(1)),
					(BiFunction<TraversableExpression[], Expression, Expression>) (args, index) ->
							Max.of(IntStream.range(0, size).mapToObj(i -> args[1].getValueRelative(e(i))).toArray(Expression[]::new)),
					(Supplier) input);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> sum(Producer<T> input) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		if (KernelPreferences.isEnableSubdivision()) {
			CollectionProducerComputationBase<T, T> sum = (CollectionProducerComputationBase<T, T>) subdivide(input, this::sum);
			if (sum != null) return sum;
		}

		if (KernelPreferences.isPreferLoops()) {
			return new RepeatedCollectionProducerComputation<>(shape.replace(shape(1)),
					(args, index) ->
							e(0.0),
					(args, index) ->
							index.lessThan(e(size)),
					(args, index) ->
							args[0].getValueRelative(e(0)).add(args[1].getValueRelative(index)),
					(Supplier) input);
		} else {
			return new TraversableExpressionComputation<>(shape.replace(shape(1)),
					(args, index) ->
							Sum.of(IntStream.range(0, size).mapToObj(i -> args[1].getValueRelative(e(i))).toArray(Expression[]::new)),
					(Supplier) input);
		}
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> sigmoid(Producer<T> input) {
		return divide(c(1.0), minus(input).exp().add(c(1.0)));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _greaterThan(Producer<T> a, Producer<T> b,
																			Producer<T> trueValue, Producer<T> falseValue) {
		return _greaterThan(a, b, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _greaterThan(Producer<?> a, Producer<?> b,
																			Producer<T> trueValue, Producer<T> falseValue,
																			boolean includeEqual) {
		return (CollectionProducer<T>) new GreaterThanCollection(a, b, trueValue, falseValue, includeEqual);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> greaterThanConditional(Producer<?> a, Producer<?> b,
																						 Producer<T> trueValue, Producer<T> falseValue) {
		return greaterThanConditional(a, b, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> greaterThanConditional(Producer<?> a, Producer<?> b,
																			   Producer<T> trueValue, Producer<T> falseValue,
																			   boolean includeEqual) {
		TraversalPolicy shape = shape(1);
//			if (shape(a).getSize() == shape(b).getSize()) {
//				shape = shape(a);
//			}

		return new TraversableExpressionComputation<>(shape,
				(args, index) -> new Conditional(
						greater(args[1].getValueAt(index), args[2].getValueAt(index), includeEqual),
						args[3].getValueAt(index), args[4].getValueAt(index)),
				(Supplier) a, (Supplier) b,
				(Supplier) trueValue, (Supplier) falseValue);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _lessThan(Producer<T> a, Producer<T> b,
																  Producer<T> trueValue, Producer<T> falseValue) {
		return _lessThan(a, b, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _lessThan(Producer<?> a, Producer<?> b,
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
