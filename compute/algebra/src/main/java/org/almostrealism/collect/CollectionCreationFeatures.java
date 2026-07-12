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
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author Michael Murray
 * @see CollectionProducer
 * @see PackedCollection
 * @see CollectionFeatures
 */
public interface CollectionCreationFeatures extends CollectionTraversalFeatures {

	/**
	 * Creates a {@link PackedCollection} from an array of double values.
	 * This is one of the primary methods for creating collections from raw data,
	 * automatically determining the shape based on the array length.
	 * 
	 * <p>Note: This method delegates to {@link PackedCollection#of(double...)}.
	 * 
	 * @param values the double values to pack into a collection
	 * @return a new {@link PackedCollection} containing the specified values
	 * 
	 *
	 * <pre>{@code
	 * // Create a collection from double array
	 * PackedCollection collection = pack(1.0, 2.0, 3.0, 4.0);
	 * // Result: PackedCollection with shape [4] containing [1.0, 2.0, 3.0, 4.0]
	 * 
	 * // Create a single-element collection
	 * PackedCollection single = pack(42.0);
	 * // Result: PackedCollection with shape [1] containing [42.0]
	 * }</pre>
	 */
	default PackedCollection pack(double... values) {
		return PackedCollection.of(values);
	}

	/**
	 * Creates a {@link PackedCollection} from a {@link List} of {@link Double} values.
	 * This is one of the primary methods for creating collections from raw data,
	 * automatically determining the shape based on the size of the list.
	 *
	 * <p>Note: This method delegates to {@link PackedCollection#of(double...)}.
	 *
	 * @param values the {@link Double} values to pack into a collection
	 * @return a new {@link PackedCollection} containing the specified values
	 *
	 *
	 * <pre>{@code
	 * // Create a collection from List
	 * PackedCollection collection = pack(List.of(1.0, 2.0, 3.0, 4.0));
	 * // Result: PackedCollection with shape [4] containing [1.0, 2.0, 3.0, 4.0]
	 *
	 * // Create a single-element collection
	 * PackedCollection single = pack(Collections.singletonList(42.0));
	 * // Result: PackedCollection with shape [1] containing [42.0]
	 * }</pre>
	 */
	default PackedCollection pack(List<Double> values) {
		return pack(values.stream().mapToDouble(d -> d).toArray());
	}

	/**
	 * Creates a {@link PackedCollection} from an array of float values.
	 * The float values are automatically converted to double precision
	 * before being stored in the collection.
	 * 
	 * @param values the float values to pack into a collection
	 * @return a new {@link PackedCollection} containing the converted double values
	 * 
	 *
	 * <pre>{@code
	 * // Create a collection from float array
	 * PackedCollection collection = pack(1.5f, 2.5f, 3.5f);
	 * // Result: PackedCollection with shape [3] containing [1.5, 2.5, 3.5] as doubles
	 * 
	 * // Single float value
	 * PackedCollection single = pack(3.14f);
	 * // Result: PackedCollection with shape [1] containing [3.14] as double
	 * }</pre>
	 */
	default PackedCollection pack(float... values) {
		return PackedCollection.of(IntStream.range(0, values.length).mapToDouble(i -> values[i]).toArray());
	}

	/**
	 * Creates a {@link PackedCollection} from double values with a specified shape.
	 * The values are arranged according to the provided shape, which must match
	 * the total number of elements.
	 *
	 * <pre>{@code
	 * // Create a 2x3 matrix from values
	 * PackedCollection matrix = pack(shape(2, 3), 1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
	 * // Result: 2x3 matrix [[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]]
	 *
	 * // Create a 2x2 matrix (shape must match value count)
	 * PackedCollection square = pack(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
	 * // Result: 2x2 matrix [[1.0, 2.0], [3.0, 4.0]]
	 * }</pre>
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
	 * The float values are converted to doubles and arranged according to the
	 * provided shape.
	 *
	 * <pre>{@code
	 * // Create a 2x2 matrix from float values
	 * PackedCollection matrix = pack(shape(2, 2), 1.5f, 2.5f, 3.5f, 4.5f);
	 * // Result: 2x2 matrix of doubles [[1.5, 2.5], [3.5, 4.5]]
	 * }</pre>
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
	 * The collection will have the correct dimensions but all values
	 * will be initialized to zero.
	 * 
	 * <p>Note: This method is equivalent to {@code new PackedCollection(shape)}.
	 * 
	 * @param shape the {@link TraversalPolicy} defining the collection's shape
	 * @return a new empty {@link PackedCollection} with the specified shape
	 * 
	 *
	 * <pre>{@code
	 * // Create empty 2D collection
	 * PackedCollection matrix = empty(shape(3, 4));
	 * // Result: 3x4 PackedCollection filled with zeros
	 * 
	 * // Create empty 1D collection
	 * PackedCollection vector = empty(shape(5));
	 * // Result: 1D PackedCollection with 5 zero elements
	 * }</pre>
	 */
	default PackedCollection empty(TraversalPolicy shape) {
		return new PackedCollection(shape);
	}

	/**
	 * Creates a {@link Producer} that provides a REFERENCE to the given value.
	 * This is a fundamental distinction from {@link #c(PackedCollection)} which
	 * captures the VALUE at compile time.
	 *
	 * <p><b>CRITICAL DISTINCTION:</b></p>
	 * <ul>
	 *   <li>{@code p(collection)} - Creates a dynamic reference. When the compiled
	 *       computation runs, it reads the CURRENT contents of the collection.
	 *       Use this for mutable data like KV caches.</li>
	 *   <li>{@code c(collection)} - Creates a fixed/static value. The collection's
	 *       contents are captured at compile time and embedded in the computation.
	 *       Use this for constant weights or immutable data.</li>
	 * </ul>
	 *
	 * <p><b>Example:</b></p>
	 * <pre>{@code
	 * PackedCollection cache = new PackedCollection(shape(10, 64));
	 *
	 * // WRONG: c(cache) captures the (empty) cache at compile time
	 * CollectionProducer badRead = c(cache).traverse(0).sum();
	 *
	 * // CORRECT: p(cache) creates a reference that reads at runtime
	 * CollectionProducer goodRead = c(p(cache)).traverse(0).sum();
	 * // or equivalently: cp(cache).traverse(0).sum();
	 *
	 * cache.setMem(0, new double[]{1, 2, 3, ...});  // Modify cache
	 * // badRead still returns 0 (captured empty cache)
	 * // goodRead returns sum of current cache contents
	 * }</pre>
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
	 * This is a fundamental method for creating computational producers
	 * from raw numeric data.
	 * 
	 * @param values the double values to include in the producer
	 * @return a {@link CollectionProducer} that generates the specified values
	 * 
	 *
	 * <pre>{@code
	 * // Create a producer for multiple values
	 * CollectionProducer producer = c(1.0, 2.0, 3.0);
	 * // Result: Producer that generates a collection [1.0, 2.0, 3.0]
	 * 
	 * // Create a single-value producer (becomes a constant)
	 * CollectionProducer constant = c(42.0);
	 * // Result: Constant producer that generates [42.0]
	 * 
	 * // Create from computed values
	 * double[] computed = {Math.PI, Math.E, Math.sqrt(2)};
	 * CollectionProducer mathConstants = c(computed);
	 * // Result: Producer with [3.14159..., 2.71828..., 1.41421...]
	 * }</pre>
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
	 * This method allows you to specify both the data and the desired shape,
	 * enabling creation of multi-dimensional collections.
	 * 
	 * produced
	 * @param shape the desired shape for the collection
	 * @param values the double values to include (must match shape's total size)
	 * @return a {@link CollectionProducer} with the specified shape and values
	 * @throws IllegalArgumentException if values.length doesn't match shape.getTotalSize()
	 * 
	 *
	 * <pre>{@code
	 * // Create a 2x3 matrix producer
	 * CollectionProducer matrix = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * // Result: 2x3 matrix producer with values [[1,2,3], [4,5,6]]
	 * 
	 * // Create a single constant with specific shape
	 * CollectionProducer constant = c(shape(1), 42.0);
	 * // Result: Constant producer with shape [1] containing [42.0]
	 * }</pre>
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
	 * This is useful for creating constant terms in mathematical expressions
	 * and operations.
	 * 
	 * produced
	 * @param value the constant value to produce
	 * @return a {@link CollectionProducer} that always generates the specified constant
	 * 
	 *
	 * <pre>{@code
	 * // Create a constant producer
	 * CollectionProducer pi = constant(Math.PI);
	 * // Result: Producer that always generates [3.14159...]
	 * 
	 * // Use in mathematical operations
	 * CollectionProducer zero = constant(0.0);
	 * CollectionProducer one = constant(1.0);
	 * // These can be used in {@link #add}, {@link #multiply}, etc.
	 * }</pre>
	 */
	default CollectionProducer constant(double value) {
		return constant(shape(1), value);
	}

	/**
	 * Creates a constant {@link CollectionProducer} with a specific shape.
	 * All elements in the produced collection will have the same constant value,
	 * but the collection will have the specified multi-dimensional shape.
	 * 
	 * <p>This method uses {@link SingleConstantComputation} for multi-element collections
	 * and {@link AtomicConstantComputation} for single-element collections to provide
	 * optimal performance for constant value operations.</p>
	 * 
	 * produced
	 * @param shape the desired shape for the constant collection
	 * @param value the constant value for all elements
	 * @return a {@link CollectionProducer} that generates a constant-filled collection
	 * 
	 * @see SingleConstantComputation
	 * @see AtomicConstantComputation
	 *
	 * <pre>{@code
	 * // Create a 2x3 matrix filled with ones
	 * CollectionProducer ones = constant(shape(2, 3), 1.0);
	 * // Result: Producer that generates a 2x3 matrix [[1,1,1], [1,1,1]]
	 * 
	 * // Create a 1D vector filled with zeros
	 * CollectionProducer zeros = constant(shape(5), 0.0);
	 * // Result: Producer that generates [0, 0, 0, 0, 0]
	 * 
	 * // Create a 3D tensor filled with pi
	 * CollectionProducer piTensor = constant(shape(2, 2, 2), Math.PI);
	 * // Result: 2x2x2 tensor where all 8 elements equal pi
	 * }</pre>
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
	 * captured at compile time. This is fundamentally different from {@link #p(Object)}
	 * which creates a reference.
	 *
	 * <p><b>CRITICAL DISTINCTION:</b></p>
	 * <ul>
	 *   <li>{@code c(collection)} - Captures the collection's contents at compile time.
	 *       The values are embedded into the generated computation as constants.
	 *       Use this for model weights, embeddings, and other immutable data.</li>
	 *   <li>{@code p(collection)} - Creates a dynamic reference that reads at runtime.
	 *       Use this for mutable data like KV caches.</li>
	 * </ul>
	 *
	 * <p><b>Implementation notes:</b></p>
	 * <ul>
	 *   <li>For single-element collections, returns an {@link AtomicConstantComputation}</li>
	 *   <li>For small collections, uses {@code DefaultTraversableExpressionComputation.fixed()}</li>
	 *   <li>For large collections, falls back to {@link #cp(PackedCollection)} to avoid
	 *       excessive conditional expressions</li>
	 * </ul>
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
			// DefaultTraversableExpressionComputation will inevitably leverage conditional
			// expressions, which should be avoided if there would be too many branches
			return DefaultTraversableExpressionComputation.fixed(value);
		} else {
			// For fixed values which are too large, it is better to simply use a copy
			// of the relevant data rather than try and represent all the values it
			// might take on as an Expression via DefaultTraversableExpressionComputation
			PackedCollection copy = new PackedCollection(value.getShape());
			copy.setFrom(0, value);
			return cp(copy);
		}
	}

	/**
	 * Creates a {@link CollectionProducer} from a {@link PackedCollection} reference.
	 * This is shorthand for {@code c(p(value))} - it wraps the collection in a
	 * {@link Producer} reference first, then creates a {@link CollectionProducer}.
	 *
	 * <p><b>When to use:</b></p>
	 * <ul>
	 *   <li>Use {@code cp(collection)} for mutable data that changes between
	 *       invocations, such as KV caches in attention mechanisms</li>
	 *   <li>The resulting computation will read the CURRENT contents of the
	 *       collection each time it runs</li>
	 * </ul>
	 *
	 * <p><b>Example:</b></p>
	 * <pre>{@code
	 * PackedCollection cache = new PackedCollection(shape(10, 64));
	 *
	 * // Create a producer that reads cache dynamically
	 * CollectionProducer cacheReader = cp(cache).traverse(0).sum();
	 *
	 * // Modify cache
	 * cache.setMem(0, new double[]{1, 2, 3, ...});
	 *
	 * // cacheReader will see the new values
	 * }</pre>
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
	 * This is one of the most basic building blocks for creating empty collections
	 * or initializing collections to a known state.
	 *
	 * @param shape the desired shape for the zero-filled collection
	 * @return a {@link CollectionProducerComputation} that generates zeros
	 * 
	 *
	 * <pre>{@code
	 * // Create a zero vector
	 * CollectionProducerComputation<PackedCollection> zeroVector = zeros(shape(5));
	 * // Result: Producer that generates [0.0, 0.0, 0.0, 0.0, 0.0]
	 * 
	 * // Create a zero matrix
	 * CollectionProducerComputation<PackedCollection> zeroMatrix = zeros(shape(2, 3));
	 * // Result: Producer that generates 2x3 matrix of all zeros
	 * 
	 * // Create a 3D tensor of zeros
	 * CollectionProducerComputation<PackedCollection> zeroTensor = zeros(shape(2, 2, 2));
	 * // Result: Producer that generates 2x2x2 tensor of all zeros
	 * }</pre>
	 */
	default CollectionProducerComputation zeros(TraversalPolicy shape) {
		return new CollectionZerosComputation(shape);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} with the simplest configuration.
	 * This is a convenience method for the most common use case of creating a collection
	 * with a static function and kernel-based computation.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @return A new DynamicCollectionProducer with kernel=true and fixedCount=true
	 * 
	 * @see DynamicCollectionProducer#DynamicCollectionProducer(TraversalPolicy, Function)
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection> function) {
		return new DynamicCollectionProducer(shape, function);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} with specified kernel usage.
	 * The kernel parameter controls the execution strategy: when true, the function runs once
	 * to produce the entire output; when false, the function runs multiple times (possibly in parallel)
	 * with each execution producing one element of the output collection.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @param kernel Whether to use kernel execution (single function call) vs element-wise execution (multiple calls)
	 * @return A new DynamicCollectionProducer with the specified kernel setting and fixedCount=true
	 * 
	 * @see DynamicCollectionProducer#DynamicCollectionProducer(TraversalPolicy, Function, boolean)
	 * @see org.almostrealism.hardware.DestinationEvaluable#evaluate(Object...) for execution mechanism details
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection> function, boolean kernel) {
		return new DynamicCollectionProducer(shape, function, kernel);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} with full control over kernel usage and count behavior.
	 * Use this when you need precise control over both execution mode and size determinism.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @param kernel Whether to use kernel execution (single function call) vs element-wise execution (multiple calls)
	 * @param fixedCount Whether this producer has a deterministic output size
	 * @return A new DynamicCollectionProducer with the specified settings
	 * 
	 * @see DynamicCollectionProducer#DynamicCollectionProducer(TraversalPolicy, Function, boolean, boolean)
	 * @see org.almostrealism.hardware.DestinationEvaluable#evaluate(Object...) for execution mechanism details
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection> function,
										   boolean kernel, boolean fixedCount) {
		return new DynamicCollectionProducer(shape, function, kernel, fixedCount);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} that depends on input collections from other producers.
	 * This method enables complex chained computations where the output depends on evaluating
	 * multiple input producers. Uses function-based (non-kernel) execution by default.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function A function that takes input collections and returns a function for output generation
	 * @param args Array of producers whose outputs will be used as inputs to the function
	 * @return A new DynamicCollectionProducer with kernel=false, fixedCount=true, and the specified inputs
	 * 
	 * @see DynamicCollectionProducer#DynamicCollectionProducer(TraversalPolicy, Function, boolean, boolean, Producer[])
	 */
	default DynamicCollectionProducer func(TraversalPolicy shape,
										   Function<PackedCollection[], Function<Object[], PackedCollection>> function,
										   Producer[] args) {
		return new DynamicCollectionProducer(shape, function, false, true, args);
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} that depends on input collections from other producers.
	 * This is a convenience overload that takes a primary argument and varargs for additional arguments.
	 * Uses function-based (non-kernel) execution by default.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions  
	 * @param function A function that takes input collections and returns a function for output generation
	 * @param argument The first producer argument to be evaluated as input
	 * @param args Additional producer arguments to be evaluated as inputs
	 * @return A new DynamicCollectionProducer with kernel=false, fixedCount=true, and the specified inputs
	 * 
	 * @see DynamicCollectionProducer#DynamicCollectionProducer(TraversalPolicy, Function, boolean, boolean, Producer, Producer...)
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
	 * Creates a producer that generates an arithmetic sequence of integers starting at zero.
	 * The sequence length is determined at evaluation time by the traversal context.
	 *
	 * @return a {@link CollectionProducerComputation} producing integers starting at 0
	 */
	default CollectionProducerComputation integers() {
		return new ArithmeticSequenceComputation(0);
	}

	/**
	 * Creates a producer that generates an arithmetic sequence of integers from {@code from}
	 * (inclusive) to {@code to} (exclusive). The result has shape {@code [to - from]} traversed
	 * element-by-element.
	 *
	 * @param from the first integer value in the sequence (inclusive)
	 * @param to   the upper bound of the sequence (exclusive)
	 * @return a {@link CollectionProducerComputation} producing integers in the given range
	 */
	default CollectionProducerComputation integers(int from, int to) {
		int len = to - from;
		TraversalPolicy shape = shape(len).traverseEach();
		return new ArithmeticSequenceComputation(shape, from);
	}

	/**
	 * Creates a producer that generates a linearly spaced sequence of {@code steps} values
	 * from {@code start} to {@code end} (inclusive). The spacing between consecutive values
	 * is {@code (end - start) / (steps - 1)}.
	 *
	 * @param start the first value in the sequence
	 * @param end   the last value in the sequence
	 * @param steps the number of evenly spaced values to produce; must be at least 2
	 * @return a {@link CollectionProducer} generating the linear sequence
	 * @throws IllegalArgumentException if {@code steps} is less than 2
	 */
	default CollectionProducer linear(double start, double end, int steps) {
		if (steps < 2) {
			throw new IllegalArgumentException();
		}

		double step = (end - start) / (steps - 1);
		return integers(0, steps).multiply(c(step));
	}
}
