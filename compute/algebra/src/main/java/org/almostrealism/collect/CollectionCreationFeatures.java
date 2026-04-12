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
import java.util.function.Supplier;
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
public interface CollectionCreationFeatures extends CollectionTraversalFeatures {

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

	/**
	 * Creates a {@link Provider} that wraps an existing supplier and applies a transformation function
	 * to its output. If the supplier is a {@link CollectionProvider}, the result is also a CollectionProvider.
	 *
	 * @param <T>  the return type of the provider
	 * @param <V>  the type produced by the source supplier
	 * @param ev   the source supplier
	 * @param func a transformation function applied to the supplier's output
	 * @return a Provider that applies {@code func} to the result of {@code ev}
	 */
	default <T, V> Provider<PackedCollection> p(Supplier<V> ev, Function<V, T> func) {
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

	/**
	 * Wraps an existing {@link Evaluable} as a {@link CollectionProducer} with the specified shape.
	 * The evaluable's {@code evaluate()} method will be called to produce the collection value.
	 *
	 * @param shape the shape describing the structure of the produced collection
	 * @param ev    the evaluable that produces the collection data
	 * @return a CollectionProducer with the given shape backed by the evaluable
	 */
	default CollectionProducer c(TraversalPolicy shape, Evaluable<PackedCollection> ev) {
		CollectionCreationFeatures self = this;
		return CollectionFeatures.getInstance().c(new CollectionProducerBase<PackedCollection, CollectionProducer>() {
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
		return CollectionFeatures.getInstance().c(p(value));
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

	/**
	 * Creates a producer for a collection of uniformly-distributed random values in [0, 1)
	 * with the specified dimensions.
	 *
	 * @param dims the dimensions of the random collection
	 * @return a Random producer with uniform distribution
	 */
	default Random rand(int... dims) { return rand(shape(dims)); }

	/**
	 * Creates a producer for a collection of uniformly-distributed random values in [0, 1)
	 * with the specified shape.
	 *
	 * @param shape the shape of the random collection
	 * @return a Random producer with uniform distribution
	 */
	default Random rand(TraversalPolicy shape) { return new Random(shape); }

	/**
	 * Creates a producer for uniformly-distributed random values using the given source.
	 *
	 * @param shape  the shape of the random collection
	 * @param source the Java Random source to use for reproducibility
	 * @return a Random producer with uniform distribution using the given source
	 */
	default Random rand(TraversalPolicy shape, java.util.Random source) {
		return new Random(shape, false, source);
	}

	/**
	 * Creates a producer for standard normal (mean=0, std=1) random values
	 * with the specified dimensions.
	 *
	 * @param dims the dimensions of the random collection
	 * @return a Random producer with standard normal distribution
	 */
	default Random randn(int... dims) { return randn(shape(dims)); }

	/**
	 * Creates a producer for standard normal (mean=0, std=1) random values
	 * with the specified shape.
	 *
	 * @param shape the shape of the random collection
	 * @return a Random producer with standard normal distribution
	 */
	default Random randn(TraversalPolicy shape) { return new Random(shape, true); }

	/**
	 * Creates a producer for standard normal random values using the given source.
	 *
	 * @param shape  the shape of the random collection
	 * @param source the Java Random source to use, or null for the default source
	 * @return a Random producer with standard normal distribution using the given source
	 */
	default Random randn(TraversalPolicy shape, java.util.Random source) {
		if (source != null) {
			return new Random(shape, true, source);
		}

		return new Random(shape, true);
	}

	/**
	 * Creates a producer for normally-distributed random values with the specified mean and
	 * standard deviation, using the default random source.
	 *
	 * @param shape the shape of the random collection
	 * @param mean  the mean of the normal distribution
	 * @param std   the standard deviation of the normal distribution
	 * @return a CollectionProducer for normally-distributed random values
	 */
	default CollectionProducer randn(TraversalPolicy shape, double mean, double std) {
		return randn(shape, mean, std, null);
	}

	/**
	 * Creates a producer for normally-distributed random values with the specified mean,
	 * standard deviation, and random source.
	 *
	 * @param shape  the shape of the random collection
	 * @param mean   the mean of the normal distribution
	 * @param std    the standard deviation of the normal distribution
	 * @param source the Java Random source to use, or null for the default source
	 * @return a CollectionProducer for normally-distributed random values
	 */
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

	/**
	 * Creates a producer for an ascending sequence of integers starting at 0,
	 * using the kernel index as the element value (element i = i).
	 *
	 * @return an arithmetic sequence producer starting at 0
	 */
	default CollectionProducerComputation integers() {
		return new ArithmeticSequenceComputation(0);
	}

	/**
	 * Creates a producer for an ascending sequence of integers from {@code from} (inclusive)
	 * to {@code to} (exclusive).
	 *
	 * @param from the starting integer value (inclusive)
	 * @param to   the ending integer value (exclusive)
	 * @return an arithmetic sequence producer containing integers from {@code from} to {@code to - 1}
	 */
	default CollectionProducerComputation integers(int from, int to) {
		int len = to - from;
		TraversalPolicy shape = shape(len).traverseEach();
		return new ArithmeticSequenceComputation(shape, from);
	}

	/**
	 * Creates a producer for a linearly spaced sequence of {@code steps} values
	 * from {@code start} (inclusive) to {@code end} (inclusive).
	 *
	 * @param start the first value in the sequence
	 * @param end   the last value in the sequence
	 * @param steps the total number of values in the sequence (must be at least 2)
	 * @return a CollectionProducer for the linear sequence
	 * @throws IllegalArgumentException if steps is less than 2
	 */
	default CollectionProducer linear(double start, double end, int steps) {
		if (steps < 2) {
			throw new IllegalArgumentException();
		}

		double step = (end - start) / (steps - 1);
		return integers(0, steps).multiply(c(step));
	}
}
