/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.AtomicConstantComputation;
import org.almostrealism.collect.computations.CollectionProvider;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.CollectionZerosComputation;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.SingleConstantComputation;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Factory interface for creating {@link CollectionProducer} instances and {@link PackedCollection} objects.
 * This interface provides methods for creating collections from raw values, constants, random generators,
 * and producer wrappers.
 *
 * <h2>Key Methods</h2>
 * <ul>
 *   <li>{@code pack(double...)} - Create a PackedCollection from values</li>
 *   <li>{@code c(double...)} - Create a constant CollectionProducer</li>
 *   <li>{@code p(T)} - Create a Producer reference</li>
 *   <li>{@code zeros(TraversalPolicy)} - Create a zero-filled producer</li>
 *   <li>{@code rand(TraversalPolicy)} - Create a random producer</li>
 * </ul>
 *
 * @author Michael Murray
 * @see CollectionProducer
 * @see PackedCollection
 * @see CollectionFeatures
 */
public interface CollectionCreationFeatures extends ShapeFeatures {

	/**
	 * Creates a {@link PackedCollection} from an array of double values.
	 *
	 * @param values the double values to pack into a collection
	 * @return a new {@link PackedCollection} containing the specified values
	 */
	default PackedCollection pack(double... values) {
		return PackedCollection.of(values);
	}

	/**
	 * Creates a {@link PackedCollection} from a {@link List} of {@link Double} values.
	 *
	 * @param values the {@link Double} values to pack into a collection
	 * @return a new {@link PackedCollection} containing the specified values
	 */
	default PackedCollection pack(List<Double> values) {
		return pack(values.stream().mapToDouble(d -> d).toArray());
	}

	/**
	 * Creates a {@link PackedCollection} from an array of float values.
	 *
	 * @param values the float values to pack into a collection
	 * @return a new {@link PackedCollection} containing the converted double values
	 */
	default PackedCollection pack(float... values) {
		return PackedCollection.of(IntStream.range(0, values.length).mapToDouble(i -> values[i]).toArray());
	}

	/**
	 * Creates a {@link PackedCollection} from double values with a specified shape.
	 *
	 * @param shape the {@link TraversalPolicy} defining the collection's dimensions
	 * @param values the double values to pack into the shaped collection
	 * @return a new {@link PackedCollection} with the specified shape and values
	 * @throws IllegalArgumentException if values.length doesn't match shape's total size
	 */
	default PackedCollection pack(TraversalPolicy shape, double... values) {
		if (values.length != shape.getTotalSize()) {
			throw new IllegalArgumentException("Wrong number of values for shape");
		}

		return pack(values).reshape(shape);
	}

	/**
	 * Creates a {@link PackedCollection} from float values with a specified shape.
	 *
	 * @param shape  the {@link TraversalPolicy} defining the collection's dimensions
	 * @param values the float values to convert and pack into the shaped collection
	 * @return a new {@link PackedCollection} with the specified shape and converted values
	 * @throws IllegalArgumentException if values.length doesn't match shape's total size
	 */
	default PackedCollection pack(TraversalPolicy shape, float... values) {
		if (values.length != shape.getTotalSize()) {
			throw new IllegalArgumentException("Wrong number of values for shape");
		}

		return pack(values).reshape(shape);
	}

	/**
	 * Creates an empty {@link PackedCollection} with the specified shape.
	 *
	 * @param shape the {@link TraversalPolicy} defining the collection's shape
	 * @return a new empty {@link PackedCollection} with the specified shape
	 */
	default PackedCollection empty(TraversalPolicy shape) {
		return new PackedCollection(shape);
	}

	/**
	 * Creates a {@link Producer} that provides a REFERENCE to the given value.
	 * This is a fundamental distinction from {@link #c(PackedCollection)} which
	 * captures the VALUE at compile time.
	 *
	 * @param value the value to wrap in a Producer reference
	 * @param <T> the type of value
	 * @return a {@link Producer} that provides the value by reference
	 * @see #c(PackedCollection)
	 * @see #cp(PackedCollection)
	 */
	default <T> Producer<T> p(T value) {
		if (value instanceof Producer) {
			throw new IllegalArgumentException();
		} else if (value instanceof Shape) {
			return new CollectionProviderProducer((Shape) value);
		} else if (value != null) {
			return () -> new Provider<>(value);
		} else {
			return null;
		}
	}

	default <T, V> Provider<PackedCollection> p(java.util.function.Supplier<V> ev, Function<V, T> func) {
		if (ev instanceof CollectionProvider) {
			return new CollectionProvider(null) {
				@Override
				public T get() {
					return func.apply((V) ((CollectionProvider) ev).get());
				}
			};
		} else {
			return new Provider(null) {
				@Override
				public T get() {
					return func.apply((V) ((Provider) ev).get());
				}
			};
		}
	}

	/**
	 * Creates a {@link CollectionProducer} from a sequence of double values.
	 *
	 * @param values the double values to include in the producer
	 * @return a {@link CollectionProducer} that generates the specified values
	 */
	default CollectionProducer c(double... values) {
		if (values.length == 1) {
			return constant(values[0]);
		}

		PackedCollection c = PackedCollection.factory().apply(values.length);
		c.setMem(0, values);
		return c(c);
	}

	/**
	 * Creates a {@link CollectionProducer} with a specific shape from double values.
	 *
	 * @param shape the desired shape for the collection
	 * @param values the double values to include (must match shape's total size)
	 * @return a {@link CollectionProducer} with the specified shape and values
	 * @throws IllegalArgumentException if values.length doesn't match shape.getTotalSize()
	 */
	default CollectionProducer c(TraversalPolicy shape, double... values) {
		if (values.length != shape.getTotalSize()) {
			throw new IllegalArgumentException("Wrong number of values for shape");
		} else if (values.length == 1) {
			return constant(shape, values[0]);
		}

		PackedCollection c = new PackedCollection(shape);
		c.setMem(0, values);
		return c(c);
	}

	/**
	 * Creates a constant {@link CollectionProducer} that always produces the same scalar value.
	 *
	 * @param value the constant value to produce
	 * @return a {@link CollectionProducer} that always generates the specified constant
	 */
	default CollectionProducer constant(double value) {
		return constant(shape(1), value);
	}

	/**
	 * Creates a constant {@link CollectionProducer} with a specific shape.
	 * All elements in the produced collection will have the same constant value.
	 *
	 * @param shape the desired shape for the constant collection
	 * @param value the constant value for all elements
	 * @return a {@link CollectionProducer} that generates a constant-filled collection
	 */
	default CollectionProducer constant(TraversalPolicy shape, double value) {
		if (shape.getTotalSizeLong() == 1) {
			return new AtomicConstantComputation(value).reshape(shape);
		} else {
			return new SingleConstantComputation(shape, value);
		}
	}

	default CollectionProducer c(TraversalPolicy shape, Evaluable<PackedCollection> ev) {
		CollectionCreationFeatures self = this;
		return c(new CollectionProducerBase<PackedCollection, CollectionProducer>() {
			@Override
			public Evaluable<PackedCollection> get() { return ev; }

			@Override
			public TraversalPolicy getShape() {
				return shape;
			}

			@Override
			public CollectionProducer traverse(int axis) {
				return self.traverse(axis, this);
			}

			@Override
			public CollectionProducer reshape(TraversalPolicy shape) {
				return (CollectionProducer) self.reshape(shape, this);
			}
		});
	}

	/**
	 * Creates a {@link CollectionProducer} that represents the VALUE of a collection,
	 * captured at compile time.
	 *
	 * @param value the collection whose VALUE to capture
	 * @return a {@link CollectionProducer} that generates the fixed values
	 * @see #p(Object)
	 * @see #cp(PackedCollection)
	 */
	default CollectionProducer c(PackedCollection value) {
		if (value.getShape().getTotalSizeLong() == 1) {
			return new AtomicConstantComputation(value.toDouble());
		} else if (value.getShape().getTotalSizeLong() < ScopeSettings.maxConditionSize) {
			return DefaultTraversableExpressionComputation.fixed(value);
		} else {
			PackedCollection copy = new PackedCollection(value.getShape());
			copy.setMem(0, value);
			return cp(copy);
		}
	}

	/**
	 * Creates a {@link CollectionProducer} from a {@link PackedCollection} reference.
	 * This is shorthand for {@code c(p(value))}.
	 *
	 * @param value the collection to reference
	 * @return a {@link CollectionProducer} that reads the collection by reference
	 * @see #c(PackedCollection)
	 * @see #p(Object)
	 */
	default CollectionProducer cp(PackedCollection value) {
		return c(p(value));
	}

	/**
	 * Creates a {@link CollectionProducerComputation} that generates a collection filled with zeros.
	 *
	 * @param shape the desired shape for the zero-filled collection
	 * @return a {@link CollectionProducerComputation} that generates zeros
	 */
	default CollectionProducerComputation zeros(TraversalPolicy shape) {
		return new CollectionZerosComputation(shape);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} with the simplest configuration.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @return A new DynamicCollectionProducer with kernel=true and fixedCount=true
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection> function) {
		return new DynamicCollectionProducer(shape, function);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} with specified kernel usage.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @param kernel Whether to use kernel execution (single function call) vs element-wise execution
	 * @return A new DynamicCollectionProducer with the specified kernel setting
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection> function, boolean kernel) {
		return new DynamicCollectionProducer(shape, function, kernel);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} with full control over kernel usage and count behavior.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @param kernel Whether to use kernel execution
	 * @param fixedCount Whether this producer has a deterministic output size
	 * @return A new DynamicCollectionProducer with the specified settings
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection> function,
										   boolean kernel, boolean fixedCount) {
		return new DynamicCollectionProducer(shape, function, kernel, fixedCount);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} that depends on input collections from other producers.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function A function that takes input collections and returns a function for output generation
	 * @param args Array of producers whose outputs will be used as inputs to the function
	 * @return A new DynamicCollectionProducer with the specified inputs
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape,
										   Function<PackedCollection[], Function<Object[], PackedCollection>> function,
										   Producer[] args) {
		return new DynamicCollectionProducer(shape, function, false, true, args);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} that depends on input collections from other producers.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function A function that takes input collections and returns a function for output generation
	 * @param argument The first producer argument to be evaluated as input
	 * @param args Additional producer arguments to be evaluated as inputs
	 * @return A new DynamicCollectionProducer with the specified inputs
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape,
										   Function<PackedCollection[], Function<Object[], PackedCollection>> function,
										   Producer<?> argument, Producer<?>... args) {
		return new DynamicCollectionProducer(shape, function, false, true, argument, args);
	}

	default Random rand(int... dims) { return rand(shape(dims)); }
	default Random rand(TraversalPolicy shape) { return new Random(shape); }
	default Random rand(TraversalPolicy shape, java.util.Random source) {
		return new Random(shape, false, source);
	}

	default Random randn(int... dims) { return randn(shape(dims)); }
	default Random randn(TraversalPolicy shape) { return new Random(shape, true); }
	default Random randn(TraversalPolicy shape, java.util.Random source) {
		if (source != null) {
			return new Random(shape, true, source);
		}

		return new Random(shape, true);
	}

	default CollectionProducer randn(TraversalPolicy shape, double mean, double std) {
		return randn(shape, mean, std, null);
	}

	default CollectionProducer randn(TraversalPolicy shape, double mean, double std, java.util.Random source) {
		if (mean == 0.0 && std == 1.0) {
			return randn(shape, source);
		} else if (mean == 0.0) {
			return c(std).multiply(randn(shape, source));
		} else if (std == 1.0) {
			return c(mean).add(randn(shape, source));
		} else {
			return c(mean).add(c(std).multiply(randn(shape, source)));
		}
	}

	default CollectionProducerComputation integers() {
		return new ArithmeticSequenceComputation(0);
	}

	default CollectionProducerComputation integers(int from, int to) {
		int len = to - from;
		TraversalPolicy shape = shape(len).traverseEach();
		return new ArithmeticSequenceComputation(shape, from);
	}

	default CollectionProducer linear(double start, double end, int steps) {
		if (steps < 2) {
			throw new IllegalArgumentException();
		}

		double step = (end - start) / (steps - 1);
		return integers(0, steps).multiply(c(step));
	}

	// Required for internal use, to be overridden by CollectionFeatures
	CollectionProducer traverse(int axis, Producer<PackedCollection> producer);
	Producer reshape(TraversalPolicy shape, Producer producer);
	CollectionProducer c(Producer producer);
}
