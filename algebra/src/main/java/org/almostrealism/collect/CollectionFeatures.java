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

package org.almostrealism.collect;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.ArithmeticSequenceExpression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.ComparisonExpression;
import io.almostrealism.collect.IndexOfPositionExpression;
import io.almostrealism.collect.RelativeTraversableExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.expression.Mod;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerFeatures;
import io.almostrealism.relation.ProducerSubstitution;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.ScalarMatrixComputation;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.bool.GreaterThanCollection;
import org.almostrealism.bool.LessThanCollection;
import org.almostrealism.collect.computations.AggregatedProducerComputation;
import org.almostrealism.collect.computations.AtomicConstantComputation;
import org.almostrealism.collect.computations.CollectionComparisonComputation;
import org.almostrealism.collect.computations.CollectionExponentComputation;
import org.almostrealism.collect.computations.CollectionExponentialComputation;
import org.almostrealism.collect.computations.CollectionLogarithmComputation;
import org.almostrealism.collect.computations.CollectionMinusComputation;
import org.almostrealism.collect.computations.CollectionPermute;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProductComputation;
import org.almostrealism.collect.computations.CollectionProvider;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.CollectionZerosComputation;
import org.almostrealism.collect.computations.ConstantRepeatedProducerComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.DynamicIndexProjectionProducerComputation;
import org.almostrealism.collect.computations.EpsilonConstantComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionMap;
import org.almostrealism.collect.computations.PackedCollectionPad;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.SingleConstantComputation;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface CollectionFeatures extends ExpressionFeatures, ProducerFeatures {
	boolean enableShapelessWarning = false;
	boolean enableVariableRepeat = false;
	boolean enableStrictAssignmentSize = true;

	// Should be removed
	boolean enableTraversableRepeated = true;
	boolean enableGradientMultiplyEach = true;

	// Should be flipped and removed
	boolean enableIndexProjectionDeltaAlt = true;
	boolean enableCollectionIndexSize = false;

	static boolean isEnableIndexProjectionDeltaAlt() {
		return enableIndexProjectionDeltaAlt;
	}

	Console console = Computation.console.child();

	/**
	 * Creates a new {@link TraversalPolicy} with the specified dimensions.
	 * This is one of the most fundamental methods for creating shapes that define
	 * how data is organized and accessed in collections.
	 * 
	 * @param dims the dimensions of the shape (e.g., width, height, depth)
	 * @return a new {@link TraversalPolicy} representing the specified shape
	 * 
	 *
	 * <pre>{@code
	 * // Create a 1D shape with 5 elements
	 * TraversalPolicy shape1D = shape(5);
	 * // Result: shape with dimensions [5], total size = 5
	 * 
	 * // Create a 2D shape (matrix) with 3 rows and 4 columns
	 * TraversalPolicy shape2D = shape(3, 4);
	 * // Result: shape with dimensions [3, 4], total size = 12
	 * 
	 * // Create a 3D shape (tensor) with dimensions 2x3x4
	 * TraversalPolicy shape3D = shape(2, 3, 4);
	 * // Result: shape with dimensions [2, 3, 4], total size = 24
	 * }</pre>
	 */
	default TraversalPolicy shape(int... dims) { return new TraversalPolicy(dims); }
	
	/**
	 * Creates a new {@link TraversalPolicy} with the specified dimensions using long values.
	 * This overload is useful when working with very large dimensions that exceed
	 * the range of int values.
	 * 
	 * @param dims the dimensions of the shape as long values
	 * @return a new {@link TraversalPolicy} representing the specified shape
	 * 
	 *
	 * <pre>{@code
	 * // Create a large 1D shape
	 * TraversalPolicy largShape = shape(1000000L);
	 * // Result: shape with dimensions [1000000], total size = 1000000
	 * 
	 * // Create a 2D shape with large dimensions
	 * TraversalPolicy shape2D = shape(10000L, 20000L);
	 * // Result: shape with dimensions [10000, 20000], total size = 200000000
	 * }</pre>
	 */
	default TraversalPolicy shape(long... dims) { return new TraversalPolicy(dims); }
	
	/**
	 * Creates a position {@link TraversalPolicy} with the specified dimensions.
	 * Unlike regular shapes, positions are used to specify coordinates or offsets
	 * within a larger collection structure.
	 * 
	 * @param dims the position coordinates
	 * @return a new {@link TraversalPolicy} representing the specified position
	 * 
	 *
	 * <pre>{@code
	 * // Create a position at coordinates (2, 3) in a 2D space
	 * TraversalPolicy pos = position(2, 3);
	 * // Result: position representing coordinates [2, 3]
	 * 
	 * // Create a position at coordinates (1, 2, 3) in a 3D space
	 * TraversalPolicy pos3D = position(1, 2, 3);
	 * // Result: position representing coordinates [1, 2, 3]
	 * }</pre>
	 */
	default TraversalPolicy position(int... dims) { return new TraversalPolicy(true, dims); }

	/**
	 * Extracts the {@link TraversalPolicy} shape from a {@link Supplier}.
	 * This method is useful for determining the shape of collections at runtime
	 * by examining the supplier object.
	 * 
	 * @param s the supplier to extract shape from
	 * @return the {@link TraversalPolicy} representing the supplier's shape, or {@link #shape(int...)} if no shape available
	 * 
	 *
	 * <pre>{@code
	 * // Extract shape from a {@link CollectionProducer} created with c()
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0);
	 * TraversalPolicy vectorShape = shape(vector);
	 * // Result: shape with dimensions [3]
	 * 
	 * // Extract shape from arithmetic operation results
	 * CollectionProducer<PackedCollection<?>> a = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * CollectionProducer<PackedCollection<?>> b = c(shape(2, 3), 2, 3, 4, 5, 6, 7);
	 * CollectionProducer<PackedCollection<?>> sum = add(a, b);
	 * TraversalPolicy resultShape = shape(sum);
	 * // Result: shape with dimensions [2, 3]
	 * 
	 * // Extract shape from reshaped {@link CollectionProducer}
	 * CollectionProducer<PackedCollection<?>> reshaped = vector.reshape(shape(1, 3));
	 * TraversalPolicy reshapedShape = shape(reshaped);
	 * // Result: shape with dimensions [1, 3]
	 * }</pre>
	 */
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

	/**
	 * Extracts the {@link TraversalPolicy} shape from a {@link TraversableExpression}.
	 * This method is used to determine the shape of expressions used in 
	 * computational graphs and operations.
	 * 
	 * @param t the {@link TraversableExpression} to extract shape from
	 * @return the {@link TraversalPolicy} representing the expression's shape, or {@link #shape(int...)} if no shape available
	 * 
	 *
	 * <pre>{@code
	 * // Create an expression with known shape
	 * TraversableExpression expr = new PackedCollectionMap(shape(2, 3), someProducer, mapper);
	 * TraversalPolicy extractedShape = shape(expr);
	 * // Result: shape with dimensions [2, 3]
	 * }</pre>
	 */
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

	/**
	 * Gets the number of elements that will be operated on by one thread of a kernel
	 * for the given {@link Supplier}. This is not the total number of elements that can be
	 * produced, but rather the number that will be processed by a single thread.
	 * The total number is the product of the size and the count.
	 * 
	 * @param s the supplier to examine
	 * @return the total number of elements, or -1 if the supplier is null
	 * 
	 *
	 * <pre>{@code
	 * // Get size of a {@link CollectionProducer} created with c()
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0);
	 * int vectorSize = size(vector);
	 * // Result: 3 (3 elements in the vector)
	 * 
	 * // Get size of arithmetic operation results
	 * CollectionProducer<PackedCollection<?>> a = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * CollectionProducer<PackedCollection<?>> b = c(shape(2, 3), 2, 3, 4, 5, 6, 7);
	 * CollectionProducer<PackedCollection<?>> sum = add(a, b);
	 * int matrixSize = size(sum);
	 * // Result: 6 (2 * 3 matrix elements)
	 * 
	 * // Get size of reshaped producers
	 * CollectionProducer<PackedCollection<?>> reshaped = vector.reshape(shape(1, 3));
	 * int reshapedSize = size(reshaped);
	 * // Result: 3 (same total elements, different shape)
	 * }</pre>
	 */
	default int size(Supplier s) {
		if (s == null) {
			return -1;
		} else if (s instanceof MemoryDataComputation) {
			return ((MemoryDataComputation) s).getMemLength();
		} else {
			return shape(s).getSize();
		}
	}

	/**
	 * Gets the number of elements that will be operated on by a single thread
	 * of a kernel for a {@link Shape}. This is a convenient method for getting
	 * the size directly from objects that implement the {@link Shape} interface.
	 * The total number of elements is the product of this size and the count.
	 * 
	 * @param s the {@link Shape} to examine
	 * @return the number of elements operated on by one thread
	 * 
	 *
	 * <pre>{@code
	 * // Get size of a shape object
	 * Shape<?> collection = new PackedCollection<>(shape(2, 3, 4));
	 * int totalElements = size(collection);
	 * // Result: 24 (2 * 3 * 4 elements)
	 * }</pre>
	 */
	default int size(Shape s) {
		return s.getShape().getSize();
	}

	// TODO  Move to TraversalPolicy
	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions of length 1.
	 * This utility method adds dimensions to a shape until it reaches the target
	 * number of dimensions, useful for making shapes compatible for operations.
	 * 
	 * @param shape the original shape to pad
	 * @param target the desired number of dimensions
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
	 * 
	 *
	 * <pre>{@code
	 * // Pad a 1D shape to 3D
	 * TraversalPolicy original = shape(5); // [5]
	 * TraversalPolicy padded = padDimensions(original, 3);
	 * // Result: shape [1, 1, 5] (padded at the beginning)
	 * 
	 * // Pad a 2D shape to 4D
	 * TraversalPolicy matrix = shape(3, 4); // [3, 4]
	 * TraversalPolicy tensor = padDimensions(matrix, 4);
	 * // Result: shape [1, 1, 3, 4]
	 * }</pre>
	 */
	default TraversalPolicy padDimensions(TraversalPolicy shape, int target) {
		return padDimensions(shape, 1, target);
	}

	// TODO  Move to TraversalPolicy
	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, but only if it has at least min dimensions.
	 * This overload provides more control by specifying a minimum number of dimensions
	 * that must be present before padding occurs.
	 * 
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @return a new {@link TraversalPolicy} with the target number of dimensions (if min is met)
	 * 
	 *
	 * <pre>{@code
	 * // Only pad if shape has at least 2 dimensions
	 * TraversalPolicy small = shape(5); // [5] - only 1 dimension
	 * TraversalPolicy unchanged = padDimensions(small, 2, 4);
	 * // Result: [5] (unchanged because it has less than 2 dimensions)
	 * 
	 * TraversalPolicy matrix = shape(3, 4); // [3, 4] - 2 dimensions
	 * TraversalPolicy expanded = padDimensions(matrix, 2, 4);
	 * // Result: [1, 1, 3, 4] (padded because it has >= 2 dimensions)
	 * }</pre>
	 */
	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target) {
		return padDimensions(shape, min, target, false);
	}

	// TODO  Move to TraversalPolicy
	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, with control over padding direction.
	 * This is the most flexible padding method, allowing you to specify whether
	 * padding dimensions should be added at the beginning (false) or end (true) of the shape.
	 * 
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @param post whether to append dimensions at the end (true) or prepend at the beginning (false)
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
	 * 
	 *
	 * <pre>{@code
	 * // Pad at the beginning (default behavior)
	 * TraversalPolicy original = shape(3, 4); // [3, 4]
	 * TraversalPolicy frontPadded = padDimensions(original, 1, 4, false);
	 * // Result: [1, 1, 3, 4] (padded at front)
	 * 
	 * // Pad at the end
	 * TraversalPolicy backPadded = padDimensions(original, 1, 4, true);
	 * // Result: [3, 4, 1, 1] (padded at back)
	 * 
	 * // Practical use: making tensor shapes compatible
	 * TraversalPolicy vector = shape(100); // [100]
	 * TraversalPolicy batchVector = padDimensions(vector, 1, 2, false);
	 * // Result: [1, 100] (adds batch dimension at front)
	 * }</pre>
	 */
	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target, boolean post) {
		if (shape.getDimensions() < min) {
			return shape;
		}

		while (shape.getDimensions() < target) {
			shape = post ? shape.appendDimension(1) : shape.prependDimension(1);
		}

		return shape;
	}

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
	 * PackedCollection<?> collection = pack(1.0, 2.0, 3.0, 4.0);
	 * // Result: PackedCollection with shape [4] containing [1.0, 2.0, 3.0, 4.0]
	 * 
	 * // Create a single-element collection
	 * PackedCollection<?> single = pack(42.0);
	 * // Result: PackedCollection with shape [1] containing [42.0]
	 * }</pre>
	 */
	default PackedCollection<?> pack(double... values) {
		return PackedCollection.of(values);
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
	 * PackedCollection<?> collection = pack(1.5f, 2.5f, 3.5f);
	 * // Result: PackedCollection with shape [3] containing [1.5, 2.5, 3.5] as doubles
	 * 
	 * // Single float value
	 * PackedCollection<?> single = pack(3.14f);
	 * // Result: PackedCollection with shape [1] containing [3.14] as double
	 * }</pre>
	 */
	default PackedCollection<?> pack(float... values) {
		return PackedCollection.of(IntStream.range(0, values.length).mapToDouble(i -> values[i]).toArray());
	}

	/**
	 * Creates an empty {@link PackedCollection} with the specified shape.
	 * The collection will have the correct dimensions but all values
	 * will be initialized to zero.
	 * 
	 * <p>Note: This method is equivalent to {@code new PackedCollection<>(shape)}.
	 * 
	 * @param shape the {@link TraversalPolicy} defining the collection's shape
	 * @return a new empty {@link PackedCollection} with the specified shape
	 * 
	 *
	 * <pre>{@code
	 * // Create empty 2D collection
	 * PackedCollection<?> matrix = empty(shape(3, 4));
	 * // Result: 3x4 PackedCollection filled with zeros
	 * 
	 * // Create empty 1D collection
	 * PackedCollection<?> vector = empty(shape(5));
	 * // Result: 1D PackedCollection with 5 zero elements
	 * }</pre>
	 */
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
	default <T> Producer<?> delegate(Producer<T> original, Producer<T> actual) {
		if (actual == null) return null;

		TraversalPolicy shape = null;
		boolean fixedCount = Countable.isFixedCount(actual);

		if (actual instanceof Shape && ((Shape) actual).getShape().getSize() == 1) {
			shape = new TraversalPolicy(1);
			fixedCount = false;
		} else if (!(actual instanceof CollectionProducer) && original instanceof Shape) {
			shape = ((Shape) original).getShape();
			fixedCount = Countable.isFixedCount(original);
		}

		if (shape != null) {
			Evaluable ev = actual.get();
			actual = new DynamicCollectionProducer(new TraversalPolicy(1),
					args -> ev.evaluate((Object[]) args), false, fixedCount);
		}

		return new DelegatedCollectionProducer<>(c(actual), false, false);
	}

	@Override
	default <T> ProducerSubstitution<T> substitute(Producer<T> original, Producer<T> replacement) {
		return new CollectionProducerSubstitution(original, replacement);
	}

	/**
	 * Creates a {@link CollectionProducer} from a sequence of double values.
	 * This is a fundamental method for creating computational producers
	 * from raw numeric data.
	 * 
	 * @param <T> the type of {@link PackedCollection} produced
	 * @param values the double values to include in the producer
	 * @return a {@link CollectionProducer} that generates the specified values
	 * 
	 *
	 * <pre>{@code
	 * // Create a producer for multiple values
	 * CollectionProducer<PackedCollection<?>> producer = c(1.0, 2.0, 3.0);
	 * // Result: Producer that generates a collection [1.0, 2.0, 3.0]
	 * 
	 * // Create a single-value producer (becomes a constant)
	 * CollectionProducer<PackedCollection<?>> constant = c(42.0);
	 * // Result: Constant producer that generates [42.0]
	 * 
	 * // Create from computed values
	 * double[] computed = {Math.PI, Math.E, Math.sqrt(2)};
	 * CollectionProducer<PackedCollection<?>> mathConstants = c(computed);
	 * // Result: Producer with [3.14159..., 2.71828..., 1.41421...]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> c(double... values) {
		if (values.length == 1) {
			return constant(values[0]);
		}

		PackedCollection<?> c = PackedCollection.factory().apply(values.length);
		c.setMem(0, values);
		return (CollectionProducer<T>) c(c);
	}

	/**
	 * Creates a {@link CollectionProducer} with a specific shape from double values.
	 * This method allows you to specify both the data and the desired shape,
	 * enabling creation of multi-dimensional collections.
	 * 
	 * @param <T> the type of {@link PackedCollection} produced
	 * @param shape the desired shape for the collection
	 * @param values the double values to include (must match shape's total size)
	 * @return a {@link CollectionProducer} with the specified shape and values
	 * @throws IllegalArgumentException if values.length doesn't match shape.getTotalSize()
	 * 
	 *
	 * <pre>{@code
	 * // Create a 2x3 matrix producer
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * // Result: 2x3 matrix producer with values [[1,2,3], [4,5,6]]
	 * 
	 * // Create a single constant with specific shape
	 * CollectionProducer<PackedCollection<?>> constant = c(shape(1), 42.0);
	 * // Result: Constant producer with shape [1] containing [42.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> c(TraversalPolicy shape, double... values) {
		if (values.length != shape.getTotalSize()) {
			throw new IllegalArgumentException("Wrong number of values for shape");
		} else if (values.length == 1) {
			return constant(shape, values[0]);
		}

		PackedCollection<T> c = new PackedCollection<>(shape);
		c.setMem(0, values);
		return (CollectionProducer<T>) c(c);
	}

	/**
	 * Creates a constant {@link CollectionProducer} that always produces the same scalar value.
	 * This is useful for creating constant terms in mathematical expressions
	 * and operations.
	 * 
	 * @param <T> the type of {@link PackedCollection} produced
	 * @param value the constant value to produce
	 * @return a {@link CollectionProducer} that always generates the specified constant
	 * 
	 *
	 * <pre>{@code
	 * // Create a constant producer
	 * CollectionProducer<PackedCollection<?>> pi = constant(Math.PI);
	 * // Result: Producer that always generates [3.14159...]
	 * 
	 * // Use in mathematical operations
	 * CollectionProducer<PackedCollection<?>> zero = constant(0.0);
	 * CollectionProducer<PackedCollection<?>> one = constant(1.0);
	 * // These can be used in {@link #add}, {@link #multiply}, etc.
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> constant(double value) {
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
	 * @param <T> the type of {@link PackedCollection} produced
	 * @param shape the desired shape for the constant collection
	 * @param value the constant value for all elements
	 * @return a {@link CollectionProducer} that generates a constant-filled collection
	 * 
	 * @see SingleConstantComputation
	 * @see AtomicConstantComputation
	 *
	 * <pre>{@code
	 * // Create a 2x3 matrix filled with ones
	 * CollectionProducer<PackedCollection<?>> ones = constant(shape(2, 3), 1.0);
	 * // Result: Producer that generates a 2x3 matrix [[1,1,1], [1,1,1]]
	 * 
	 * // Create a 1D vector filled with zeros
	 * CollectionProducer<PackedCollection<?>> zeros = constant(shape(5), 0.0);
	 * // Result: Producer that generates [0, 0, 0, 0, 0]
	 * 
	 * // Create a 3D tensor filled with pi
	 * CollectionProducer<PackedCollection<?>> piTensor = constant(shape(2, 2, 2), Math.PI);
	 * // Result: 2x2x2 tensor where all 8 elements equal π
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> constant(TraversalPolicy shape, double value) {
		if (shape.getTotalSizeLong() == 1) {
			return new AtomicConstantComputation<T>(value).reshape(shape);
		} else {
			return new SingleConstantComputation<>(shape, value);
		}
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
		if (value.getShape().getTotalSizeLong() == 1) {
			return new AtomicConstantComputation<>(value.toDouble());
		} else if (value.getShape().getTotalSizeLong() < ScopeSettings.maxConditionSize) {
			// DefaultTraversableExpressionComputation will inevitably leverage conditional
			// expressions, which should be avoided if there would be too many branches
			return DefaultTraversableExpressionComputation.fixed(value);
		} else {
			// For fixed values which are too large, it is better to simply use a copy
			// of the relevant data rather than try and represent all the values it
			// might take on as an Expression via DefaultTraversableExpressionComputation
			PackedCollection<?> copy = new PackedCollection<>(value.getShape());
			copy.setMem(0, value);
			return (CollectionProducer<T>) cp(copy);
		}
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> cp(V value) {
		return c(p(value));
	}

	/**
	 * Creates a {@link CollectionProducerComputation} that generates a collection filled with zeros.
	 * This is one of the most basic building blocks for creating empty collections
	 * or initializing collections to a known state.
	 * 
	 * @param <V> the type of {@link PackedCollection} produced
	 * @param shape the desired shape for the zero-filled collection
	 * @return a {@link CollectionProducerComputation} that generates zeros
	 * 
	 *
	 * <pre>{@code
	 * // Create a zero vector
	 * CollectionProducerComputation<PackedCollection<?>> zeroVector = zeros(shape(5));
	 * // Result: Producer that generates [0.0, 0.0, 0.0, 0.0, 0.0]
	 * 
	 * // Create a zero matrix
	 * CollectionProducerComputation<PackedCollection<?>> zeroMatrix = zeros(shape(2, 3));
	 * // Result: Producer that generates 2x3 matrix of all zeros
	 * 
	 * // Create a 3D tensor of zeros
	 * CollectionProducerComputation<PackedCollection<?>> zeroTensor = zeros(shape(2, 2, 2));
	 * // Result: Producer that generates 2x2x2 tensor of all zeros
	 * }</pre>
	 */
	default <V extends PackedCollection<?>> CollectionProducerComputation<V> zeros(TraversalPolicy shape) {
		return new CollectionZerosComputation<>(shape);
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

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> concat(Producer<PackedCollection<?>>... producers) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expressions[] = IntStream.range(0, producers.length)
				.mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>) args -> args.get(i + 1).getValueRelative(0))
				.toArray(Function[]::new);
		return new ExpressionComputation(shape(producers.length, 1), List.of(expressions), producers);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> concat(int axis, Producer<PackedCollection<?>>... producers) {
		List<TraversalPolicy> shapes = Stream.of(producers)
				.map(this::shape)
				.filter(s -> s.getDimensions() == shape(producers[0]).getDimensions())
				.collect(Collectors.toList());
		if (shapes.size() != producers.length) {
			throw new IllegalArgumentException("All inputs must have the same number of dimensions");
		}

		long dims[] = new long[shapes.get(0).getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			if (i == axis) {
				dims[i] = shapes.stream().mapToLong(s -> s.length(axis)).sum();
			} else {
				dims[i] = shapes.get(0).length(i);
			}
		}

		return concat(new TraversalPolicy(dims), producers);
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
		} else if (producer != null) {
			throw new UnsupportedOperationException(producer.getClass() + " cannot be converted to a CollectionProducer");
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
		if (shape.getTotalSize() == 1 && Countable.isFixedCount(index)) {
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
																			   Producer<PackedCollection<?>>... pos) {
		return c(collection, shape(collection), pos);
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

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> sizeOf(Producer<PackedCollection<?>> collection) {
		return new DefaultTraversableExpressionComputation<>("sizeOf", shape(1),
				(args) -> CollectionExpression.create(shape(1),
						index -> {
							TraversableExpression value = ((RelativeTraversableExpression) args[1]).getExpression();
							return ((ArrayVariable) value).length();
						}), collection);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection<?>> function) {
		return new DynamicCollectionProducer<>(shape, function);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection<?>> function, boolean kernel) {
		return new DynamicCollectionProducer<>(shape, function, kernel);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape, Function<Object[], PackedCollection<?>> function,
										   boolean kernel, boolean fixedCount) {
		return new DynamicCollectionProducer<>(shape, function, kernel, fixedCount);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape,
										   Function<PackedCollection<?>[], Function<Object[], PackedCollection<?>>> function,
										   Producer[] args) {
		return new DynamicCollectionProducer(shape, function, false, true, args);
	}

	default DynamicCollectionProducer func(TraversalPolicy shape,
										   Function<PackedCollection<?>[], Function<Object[], PackedCollection<?>>> function,
										   Producer<?> argument, Producer<?>... args) {
		return new DynamicCollectionProducer(shape, function, false, true, argument, args);
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

	/**
	 * Changes the traversal axis of a collection producer.
	 * This operation modifies how the collection is traversed during computation
	 * without changing the underlying data layout.
	 * 
	 * @param <T> the type of PackedCollection
	 * @param axis the new traversal axis (0-based index)
	 * @param producer the collection producer to modify
	 * @return a CollectionProducer with the specified traversal axis
	 * 
	 *
	 * <pre>{@code
	 * // Create a 2D collection and change traversal axis
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(3, 4), 
	 *     1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
	 * 
	 * // Traverse along axis 0 (rows)
	 * CollectionProducer<PackedCollection<?>> rowTraversal = traverse(0, matrix);
	 * // Changes how iteration occurs over the matrix
	 * 
	 * // Traverse along axis 1 (columns)
	 * CollectionProducer<PackedCollection<?>> colTraversal = traverse(1, matrix);
	 * // Different traversal pattern for the same data
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> traverse(int axis, Producer<T> producer) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).traverse(axis);
		} else if (producer instanceof CollectionProducerComputation) {
			return ((CollectionProducerComputation<T>) producer).traverse(axis);
		}

		return new ReshapeProducer(axis, producer);
	}

	/**
	 * Alias for {@link #traverseEach} - sets up the producer to traverse each element.
	 * This is a convenience method that makes collection operations more readable.
	 * 
	 * @param <T> the type of PackedCollection
	 * @param producer the collection producer to modify
	 * @return a Producer configured to traverse each element
	 * 
	 *
	 * <pre>{@code
	 * // Set up element-wise traversal
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0);
	 * Producer eachElement = each(vector);
	 * // Result: Producer configured for element-wise operations
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> Producer each(Producer<T> producer) {
		return traverseEach(producer);
	}

	/**
	 * Configures a producer to traverse each individual element.
	 * This sets up the traversal policy to process every element independently,
	 * which is useful for element-wise operations and transformations.
	 * 
	 * @param <T> the type of PackedCollection
	 * @param producer the collection producer to configure
	 * @return a Producer configured for element-wise traversal
	 * 
	 *
	 * <pre>{@code
	 * // Configure for element-wise processing
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * Producer elementWise = traverseEach(matrix);
	 * // Result: Producer that can process each of the 6 elements individually
	 * 
	 * // Useful for applying functions to each element
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 4.0, 9.0);
	 * Producer sqrt = traverseEach(vector).sqrt(); // hypothetical sqrt operation
	 * // Would apply sqrt to each element: [1.0, 2.0, 3.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> Producer traverseEach(Producer<T> producer) {
		return new ReshapeProducer(((Shape) producer).getShape().traverseEach(), producer);
	}

	/**
	 * Reshapes a collection producer to have a new shape.
	 * This operation changes the dimensional structure of the collection
	 * while preserving the total number of elements.
	 * 
	 * @param <T> the type of Shape
	 * @param shape the new shape for the collection
	 * @param producer the collection producer to reshape
	 * @return a Producer with the new shape
	 * @throws IllegalArgumentException if the new shape has a different total size
	 * 
	 *
	 * <pre>{@code
	 * // Reshape a 1D vector to a 2D matrix
	 * CollectionProducer<PackedCollection<?>> vector = c(1, 2, 3, 4, 5, 6);
	 * Producer<PackedCollection<?>> matrix = reshape(shape(2, 3), vector);
	 * // Result: 2x3 matrix [[1,2,3], [4,5,6]]
	 * 
	 * // Reshape a matrix to a different matrix
	 * CollectionProducer<PackedCollection<?>> matrix2x3 = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * Producer<PackedCollection<?>> matrix3x2 = reshape(shape(3, 2), matrix2x3);
	 * // Result: 3x2 matrix [[1,2], [3,4], [5,6]]
	 * 
	 * // Flatten a multi-dimensional array
	 * CollectionProducer<PackedCollection<?>> tensor = c(shape(2, 2, 2), 1, 2, 3, 4, 5, 6, 7, 8);
	 * Producer<PackedCollection<?>> flattened = reshape(shape(8), tensor);
	 * // Result: 1D vector [1, 2, 3, 4, 5, 6, 7, 8]
	 * }</pre>
	 */
	default <T extends Shape<T>> Producer reshape(TraversalPolicy shape, Producer producer) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).reshape(shape);
		} else if (producer instanceof CollectionProducerComputation) {
			return ((CollectionProducerComputation) producer).reshape(shape);
		}

		return new ReshapeProducer<>(shape, producer);
	}

	/**
	 * Creates a subset computation that extracts a sub-collection from a larger collection
	 * using static integer positions. This is the most commonly used subset method for
	 * extracting fixed-size windows or slices from multi-dimensional data.
	 *
	 * <p>The subset operation is fundamental for many tensor operations including:</p>
	 * <ul>
	 *   <li>Image patch extraction for convolutions</li>
	 *   <li>Time series windowing</li>
	 *   <li>Matrix block operations</li>
	 *   <li>Data sampling and cropping</li>
	 * </ul>
	 *
	 * <p><strong>Example - 2D image patch extraction:</strong></p>
	 * <pre>{@code
	 * // Extract a 5x5 patch from a 256x256 image starting at (100, 150)
	 * PackedCollection<?> image = new PackedCollection<>(shape(256, 256));
	 * image.fill(Math::random);
	 * 
	 * CollectionProducer<PackedCollection<?>> patch = 
	 *     subset(shape(5, 5), p(image), 100, 150);
	 * PackedCollection<?> result = patch.get().evaluate();
	 * 
	 * // result now contains image[100:105, 150:155]
	 * }</pre>
	 *
	 * <p><strong>Example - 3D volume extraction:</strong></p>
	 * <pre>{@code
	 * // Extract a 10x10x5 sub-volume from a 100x100x50 volume
	 * PackedCollection<?> volume = new PackedCollection<>(shape(100, 100, 50));
	 * volume.fill(pos -> pos[0] + pos[1] + pos[2]); // example fill
	 * 
	 * CollectionProducer<PackedCollection<?>> subVolume = 
	 *     subset(shape(10, 10, 5), p(volume), 20, 30, 15);
	 * PackedCollection<?> result = subVolume.get().evaluate();
	 * }</pre>
	 *
	 * @param <T> The type of PackedCollection being subset
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position The starting position coordinates (one integer per dimension)
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 * @throws IllegalArgumentException if position array length doesn't match collection dimensions
	 * @throws IllegalArgumentException if resulting subset would extend beyond collection bounds
	 * 
	 * @see PackedCollectionSubset
	 * @see TraversalPolicy
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subset(TraversalPolicy shape, Producer<?> collection, int... position) {
		return new PackedCollectionSubset<>(shape, collection, position);
	}

	/**
	 * Creates a subset computation that extracts a sub-collection using expression-based positions.
	 * This method allows for more complex position calculations that may involve runtime expressions,
	 * mathematical operations, or computed offsets.
	 *
	 * <p>This variant is useful when subset positions need to be calculated based on other values
	 * or when implementing adaptive algorithms where positions are determined dynamically.</p>
	 *
	 * <p><strong>Example - Computed position subset:</strong></p>
	 * <pre>{@code
	 * PackedCollection<?> data = new PackedCollection<>(shape(50, 50));
	 * data.fill(Math::random);
	 * 
	 * // Calculate positions using expressions
	 * Expression centerX = e(25);
	 * Expression centerY = e(25);
	 * Expression offset = e(5);
	 * 
	 * Expression startX = centerX.subtract(offset);
	 * Expression startY = centerY.subtract(offset);
	 * 
	 * // Extract 10x10 subset around center
	 * CollectionProducer<PackedCollection<?>> centeredSubset = 
	 *     subset(shape(10, 10), p(data), startX, startY);
	 * }</pre>
	 *
	 * @param <T> The type of PackedCollection being subset
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position The starting position coordinates as expressions (one per dimension)
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 * 
	 * @see PackedCollectionSubset
	 * @see Expression
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subset(TraversalPolicy shape, Producer<?> collection, Expression... position) {
		return new PackedCollectionSubset<>(shape, collection, position);
	}

	/**
	 * Creates a subset computation with fully dynamic positions provided by another Producer.
	 * This is the most flexible subset method, allowing positions to be computed at runtime
	 * and potentially changed between evaluations.
	 *
	 * <p>This method is particularly useful for:</p>
	 * <ul>
	 *   <li>Implementing sliding window operations</li>
	 *   <li>Dynamic region-of-interest extraction</li>
	 *   <li>Adaptive sampling based on runtime conditions</li>
	 *   <li>Batch processing with varying positions</li>
	 * </ul>
	 *
	 * <p><strong>Example - Dynamic sliding window:</strong></p>
	 * <pre>{@code
	 * PackedCollection<?> timeSeries = new PackedCollection<>(shape(1000));
	 * timeSeries.fill(pos -> Math.sin(pos[0] * 0.1)); // example signal
	 * 
	 * // Position determined at runtime
	 * PackedCollection<?> windowStart = new PackedCollection<>(1);
	 * 
	 * // Extract different windows by changing the position
	 * for (int i = 0; i < 950; i += 10) {
	 *     windowStart.set(0, (double) i);
	 *     
	 *     CollectionProducer<PackedCollection<?>> window = 
	 *         subset(shape(50), p(timeSeries), p(windowStart));
	 *     PackedCollection<?> result = window.get().evaluate();
	 *     
	 *     // Process this window...
	 * }
	 * }</pre>
	 *
	 * <p><strong>Example - 2D dynamic region extraction:</strong></p>
	 * <pre>{@code
	 * PackedCollection<?> image = new PackedCollection<>(shape(640, 480));
	 * image.fill(Math::random);
	 * 
	 * // Dynamic position based on some computed region of interest
	 * PackedCollection<?> roiPosition = new PackedCollection<>(2);
	 * roiPosition.set(0, detectedObjectX);
	 * roiPosition.set(1, detectedObjectY);
	 * 
	 * // Extract region around detected object
	 * CollectionProducer<PackedCollection<?>> objectRegion = 
	 *     subset(shape(64, 64), p(image), p(roiPosition));
	 * PackedCollection<?> objectPatch = objectRegion.get().evaluate();
	 * }</pre>
	 *
	 * @param <T> The type of PackedCollection being subset
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position A Producer that generates position coordinates at runtime
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 * @throws IllegalArgumentException if position producer shape doesn't match collection dimensions
	 * 
	 * @see PackedCollectionSubset
	 * @see Producer
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subset(TraversalPolicy shape, Producer<?> collection, Producer<?> position) {
		return new PackedCollectionSubset<>(shape, collection, position);
	}

	/**
	 * Creates a computation that repeats a collection a specified number of times.
	 * 
	 * <p>This is the primary method for creating repeat operations. It handles
	 * optimization for constant collections by avoiding the full computation
	 * pipeline when possible, and delegates to {@link PackedCollectionRepeat}
	 * for general cases.</p>
	 * 
	 * <pre>{@code
	 * // Repeat a collection 5 times along a new leading dimension
	 * CollectionProducer<?> input = cp(someCollection);
	 * CollectionProducer<?> repeated = repeat(5, input);
	 * 
	 * // Can be chained with other operations
	 * CollectionProducer<?> result = repeat(3, input)
	 *     .multiply(otherCollection)
	 *     .traverse(1).sum();
	 * }</pre>
	 * 
	 * <h4>Optimization Notes:</h4>
	 * <ul>
	 * <li>Constant collections ({@link SingleConstantComputation}) are optimized using reshape operations</li>
	 * <li>Regular collections use the full {@link PackedCollectionRepeat} implementation</li>
	 * <li>The output shape is automatically computed based on the input</li>
	 * </ul>
	 * 
	 * @param <T> the type of PackedCollection being repeated
	 * @param repeat the number of times to repeat the collection (must be positive)
	 * @param collection the source collection to repeat
	 * @return a computation that produces the repeated collection
	 * 
	 * @see PackedCollectionRepeat
	 * @see PackedCollectionRepeat#shape(int, TraversalPolicy)
	 * @see SingleConstantComputation#reshape(TraversalPolicy)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> repeat(int repeat, Producer<?> collection) {
		if (collection instanceof SingleConstantComputation) {
			return ((SingleConstantComputation) collection)
					.reshape(PackedCollectionRepeat.shape(repeat, shape(collection)));
		}

		return new PackedCollectionRepeat<>(repeat, collection);
	}

	/**
	 * Creates a computation that repeats a collection along a specific axis.
	 * 
	 * <p>This method provides axis-specific repetition by first traversing to
	 * the specified axis and then performing the repetition. This is useful
	 * for repeating along specific dimensions rather than adding a new leading
	 * dimension.</p>
	 * 
	 * <pre>{@code
	 * // Repeat along axis 1 (the second dimension) 3 times
	 * CollectionProducer<?> input = cp(someCollection);  // Shape: (5, 7, 9)
	 * CollectionProducer<?> repeated = repeat(1, 3, input);
	 * // Result: axis 1 is used as repetition context
	 * }</pre>
	 * 
	 * <h4>Implementation:</h4>
	 * <p>This method is equivalent to:</p>
	 * <pre>{@code
	 * repeat(repeat, traverse(axis, collection))
	 * }</pre>
	 * 
	 * @param <T> the type of PackedCollection being repeated
	 * @param axis the axis along which to perform repetition
	 * @param repeat the number of times to repeat
	 * @param collection the source collection to repeat
	 * @return a computation that produces the repeated collection
	 * 
	 * @see #repeat(int, Producer)
	 * @see #traverse(int, Producer)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> repeat(int axis, int repeat, Producer<?> collection) {
		return repeat(repeat, traverse(axis, (Producer) collection));
	}

	/**
	 * Creates an enumeration of a collection along a specific axis with specified length.
	 * This operation extracts consecutive elements along the specified axis, creating
	 * sliding windows or sequential patterns. The stride defaults to the sequence length,
	 * creating non-overlapping sequences.
	 * 
	 * <p>This method delegates to {@link PackedCollectionEnumerate} to perform the
	 * enumeration computation, which transforms the input shape by adding a new dimension
	 * for the enumerated sequences.</p>
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param axis the axis along which to enumerate (0-based)
	 * @param len the length of each enumerated sequence
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <p><strong>1D Vector Enumeration:</strong></p>
	 * <pre>{@code
	 * // Input: [1, 2, 3, 4, 5, 6] (shape: [6])
	 * CollectionProducer<PackedCollection<?>> vector = c(1, 2, 3, 4, 5, 6);
	 * CollectionProducerComputation<PackedCollection<?>> enumerated = enumerate(0, 3, vector);
	 * // Output: [[1,2,3], [4,5,6]] (shape: [2, 3])
	 * // Creates 2 non-overlapping sequences of length 3
	 * }</pre>
	 * 
	 *
	 * <p><strong>2D Matrix Column Enumeration:</strong></p>
	 * <pre>{@code
	 * // Input: 3x6 matrix (shape: [3, 6])
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(3, 6), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
	 * CollectionProducerComputation<PackedCollection<?>> colEnum = enumerate(1, 2, matrix);
	 * // Output: shape [3, 3, 2] - extracts 3 pairs from each row
	 * // Each row [1,2,3,4,5,6] becomes [[1,2], [3,4], [5,6]]
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 * @see #enumerate(int, int, int, io.almostrealism.relation.Producer)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, Producer<?> collection) {
		return enumerate(axis, len, len, collection);
	}

	/**
	 * Creates an enumeration with custom stride between elements.
	 * This allows for more flexible enumeration patterns by specifying
	 * how far apart the starting positions of consecutive sequences should be.
	 * When stride is less than length, sequences will overlap.
	 * 
	 * <p>This method delegates to {@link PackedCollectionEnumerate} to perform the
	 * strided enumeration computation.</p>
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param axis the axis along which to enumerate
	 * @param len the length of each enumerated sequence
	 * @param stride the step size between consecutive sequence starts
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <p><strong>Overlapping Sliding Windows:</strong></p>
	 * <pre>{@code
	 * // Input: [1, 2, 3, 4, 5, 6, 7, 8] (shape: [8])
	 * CollectionProducer<PackedCollection<?>> vector = c(1, 2, 3, 4, 5, 6, 7, 8);
	 * CollectionProducerComputation<PackedCollection<?>> sliding = enumerate(0, 3, 1, vector);
	 * // Output: [[1,2,3], [2,3,4], [3,4,5], [4,5,6], [5,6,7], [6,7,8]] (shape: [6, 3])
	 * // Stride of 1 creates overlapping windows
	 * }</pre>
	 * 
	 *
	 * <p><strong>Strided Convolution Pattern:</strong></p>
	 * <pre>{@code
	 * // Input: 8x10 matrix for 2D stride enumeration
	 * CollectionProducer<PackedCollection<?>> input = c(shape(8, 10), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection<?>> strided = enumerate(1, 2, 1, input);
	 * // Output: shape [8, 9, 2] - sliding window of size 2 with stride 1 along axis 1
	 * // Each row [a,b,c,d,e,f,g,h,i,j] becomes [[a,b], [b,c], [c,d], ..., [i,j]]
	 * }</pre>
	 * 
	 *
	 * <p><strong>Non-overlapping Blocks:</strong></p>
	 * <pre>{@code
	 * // Input: [1, 2, 3, 4, 5, 6, 7, 8] (shape: [8])
	 * CollectionProducerComputation<PackedCollection<?>> blocks = enumerate(0, 2, 2, vector);
	 * // Output: [[1,2], [3,4], [5,6], [7,8]] (shape: [4, 2])
	 * // Stride equals length, creating non-overlapping blocks
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 * @see #enumerate(int, int, io.almostrealism.relation.Producer)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride, Producer<?> collection) {
		return enumerate(axis, len, stride, 1, collection);
	}

	/**
	 * Creates multiple levels of enumeration with repetition.
	 * This advanced enumeration applies the enumeration operation multiple times,
	 * creating nested patterns useful for complex data transformations and
	 * multi-dimensional convolution operations.
	 * 
	 * <p>Each repetition applies enumeration to the result of the previous enumeration,
	 * progressively building more complex structural patterns in the data.</p>
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param axis the axis along which to enumerate
	 * @param len the length of each enumerated sequence
	 * @param stride the step size between consecutive sequence starts
	 * @param repeat the number of times to repeat the enumeration process
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the multi-level enumerated sequences
	 * 
	 *
	 * <p><strong>Double Enumeration for 2D Patches:</strong></p>
	 * <pre>{@code
	 * // Input: 4x4 matrix 
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(4, 4), 
	 *     1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
	 * 
	 * // First enumerate along axis 1, then along axis 0
	 * CollectionProducerComputation<PackedCollection<?>> patches = 
	 *     enumerate(1, 2, 2, 2, matrix); // 2 repetitions of (len=2, stride=2)
	 * // Output: shape [2, 2, 2, 2] - creates 2x2 grid of 2x2 patches
	 * // Result: 4 non-overlapping 2x2 patches from the input matrix
	 * }</pre>
	 * 
	 *
	 * <p><strong>Multi-dimensional Convolution Pattern:</strong></p>
	 * <pre>{@code
	 * // Input: 4D tensor (batch, channels, height, width)
	 * CollectionProducer<PackedCollection<?>> input = c(shape(2, 5, 10, 6), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection<?>> conv = 
	 *     cp(input).traverse(2).enumerate(3, 3, 1, 2); // Extract 3x3 patches
	 * // Applies enumerate twice along spatial dimensions
	 * // Useful for 2D convolution operations
	 * }</pre>
	 * 
	 *
	 * <p><strong>Attention Window Creation:</strong></p>
	 * <pre>{@code
	 * // Input: sequence of length 8
	 * CollectionProducer<PackedCollection<?>> sequence = c(1, 2, 3, 4, 5, 6, 7, 8);
	 * CollectionProducerComputation<PackedCollection<?>> windows = 
	 *     enumerate(0, 3, 1, 3, sequence); // 3 levels of enumeration
	 * // Creates progressively nested window structures
	 * // Useful for hierarchical attention mechanisms
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 * @see #enumerate(int, int, int, io.almostrealism.relation.Producer)
	 */
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

	/**
	 * Creates an enumeration using explicit {@link TraversalPolicy} shapes.
	 * This low-level method provides direct control over the enumeration shape
	 * and automatically computes an appropriate stride pattern.
	 * 
	 * <p>Note: This method delegates to {@link PackedCollectionEnumerate#of}.</p>
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <pre>{@code
	 * // Enumerate 2D patches from a matrix
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(10, 10), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection<?>> patches = 
	 *     enumerate(shape(10, 2), matrix);
	 * // Output: shape [5, 10, 2] - 5 slices of 10x2 from the input
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape,
																					   Producer<?> collection) {
		PackedCollectionEnumerate enumerate = new PackedCollectionEnumerate<>(shape, collection);

		if (Algebraic.isZero(enumerate)) {
			return zeros(enumerate.getShape());
		}

		return enumerate;
	}

	/**
	 * Creates an enumeration using explicit {@link TraversalPolicy} shapes for both
	 * subset and stride patterns. This provides full control over the enumeration
	 * operation for advanced use cases.
	 * 
	 * <p>Note: This method delegates to {@link PackedCollectionEnumerate#of}.</p>
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param stride the {@link TraversalPolicy} defining the stride pattern
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <pre>{@code
	 * // Custom stride enumeration for complex patterns
	 * CollectionProducer<PackedCollection<?>> data = c(shape(8, 6), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection<?>> custom = 
	 *     enumerate(shape(2, 3), shape(1, 1), data);
	 * // Creates overlapping 2x3 patches with stride 1 in both dimensions
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 */

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(TraversalPolicy shape,
																					   TraversalPolicy stride,
																					   Producer<?> collection) {
		PackedCollectionEnumerate enumerate = new PackedCollectionEnumerate<>(shape, stride, collection);

		if (Algebraic.isZero(enumerate)) {
			return zeros(enumerate.getShape());
		}

		return enumerate;
	}

	/**
	 * Creates a computation that permutes (reorders) the dimensions of a collection.
	 * This method provides a high-level interface to {@link CollectionPermute} for
	 * dimension transposition and reordering operations.
	 * 
	 * <p>Dimension permutation allows you to rearrange the axes of a multi-dimensional
	 * collection without changing the actual data values. This is commonly used for:</p>
	 * <ul>
	 *   <li>Matrix/tensor transposition</li>
	 *   <li>Changing data layout for optimal access patterns</li>
	 *   <li>Preparing data for operations that expect specific dimension orders</li>
	 *   <li>Converting between different tensor format conventions (e.g., NCHW ↔ NHWC)</li>
	 * </ul>
	 * 
	 * <p><strong>Usage Examples:</strong></p>
	 * <pre>{@code
	 * // Matrix transpose (2D)
	 * Producer<?> matrix = ...; // Shape: (rows, cols)
	 * Producer<?> transposed = permute(matrix, 1, 0); // Shape: (cols, rows)
	 * 
	 * // 3D tensor reordering  
	 * Producer<?> tensor = ...; // Shape: (batch, height, width)
	 * Producer<?> reordered = permute(tensor, 2, 0, 1); // Shape: (width, batch, height)
	 * 
	 * // 4D convolution format conversion
	 * Producer<?> nchw = ...; // Shape: (N, C, H, W)
	 * Producer<?> nhwc = permute(nchw, 0, 2, 3, 1); // Shape: (N, H, W, C)
	 * }</pre>
	 * 
	 * <p><strong>Optimization:</strong> If the input collection is zero (as determined by
	 * {@link Algebraic#isZero(Object)}), this method returns an optimized zero collection
	 * with the permuted shape instead of creating a full computation.</p>
	 * 
	 * @param <T> The type of collection being permuted
	 * @param collection The input collection to permute. Must implement {@link io.almostrealism.collect.Shape}
	 *                  to provide dimensional information.
	 * @param order The dimension permutation order. Each element specifies which input
	 *              dimension should appear at that position in the output. Must be a
	 *              valid permutation of dimension indices (0 to numDims-1).
	 * @return A computation that produces the permuted collection
	 * 
	 * @throws IllegalArgumentException if the collection doesn't implement Shape
	 * @throws IllegalArgumentException if the order array is not a valid permutation
	 *
	 * @see CollectionPermute
	 * @see io.almostrealism.collect.TraversalPolicy#permute(int...)
	 * @see #zeros(io.almostrealism.collect.TraversalPolicy)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> permute(Producer<?> collection, int... order) {
		if (Algebraic.isZero(collection)) {
			return zeros(shape(collection).permute(order).extentShape());
		}

		return new CollectionPermute<>(collection, order);
	}

	/**
	 * Pads a collection along specified axes with a uniform depth.
	 * This convenience method applies symmetric padding (same amount on all sides) to selected dimensions.
	 * 
	 * @param axes Array of axis indices to pad (0-based)
	 * @param depth Amount of padding to add on each side of the specified axes
	 * @param collection The input collection to pad
	 * @param <T> The type of PackedCollection
	 * @return A CollectionProducerComputation that produces the padded collection
	 * @throws UnsupportedOperationException if the input collection has a non-null traversal order
	 * 
	 * @see #pad(Producer, int...)
	 */
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

	/**
	 * Pads a collection with specified depths for each dimension.
	 * This method applies symmetric padding where each dimension gets the specified amount
	 * of padding on both sides (before and after the original data).
	 * 
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * // Pad a 2x3 collection with 1 unit on all sides of both dimensions
	 * PackedCollection<?> input = new PackedCollection<>(2, 3);
	 * CollectionProducer<?> padded = pad(input, 1, 1); // Results in 4x5 collection
	 * }</pre>
	 * 
	 * @param collection The input collection to pad
	 * @param depths Padding depth for each dimension. depths[i] specifies how much padding
	 *               to add before and after the data in dimension i
	 * @param <T> The type of PackedCollection
	 * @return A CollectionProducerComputation that produces the padded collection
	 * 
	 * @see PackedCollectionPad
	 * @see #pad(TraversalPolicy, TraversalPolicy, Producer)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> pad(Producer<?> collection, int... depths) {
		TraversalPolicy shape = shape(collection);

		int dims[] = new int[shape.getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			dims[i] = shape.length(i) + 2 * depths[i];
		}

		shape = new TraversalPolicy(dims).traverse(shape.getTraversalAxis());
		return pad(shape, new TraversalPolicy(true, depths), collection);
	}

	/**
	 * Pads a collection to a specific output shape with specified positioning.
	 * This method provides fine-grained control over where the input data is placed
	 * within the output shape.
	 * 
	 * <p><strong>Examples:</strong></p>
	 * <pre>{@code
	 * // Place a 2x3 input at position (1,1) within a 4x5 output
	 * TraversalPolicy outputShape = new TraversalPolicy(4, 5);
	 * TraversalPolicy position = new TraversalPolicy(1, 1);
	 * CollectionProducer<?> padded = pad(outputShape, input, 1, 1);
	 * 
	 * // Asymmetric padding: 2 units before, 1 unit after in first dimension
	 * CollectionProducer<?> asymmetric = pad(outputShape, input, 2, 0);
	 * }</pre>
	 * 
	 * @param shape The desired output shape after padding
	 * @param collection The input collection to pad  
	 * @param pos Position offsets for placing the input within the output shape.
	 *            pos[i] specifies how many zeros to add before the input data in dimension i
	 * @param <T> The type of PackedCollection
	 * @return A CollectionProducer that produces the padded collection
	 * 
	 * @see PackedCollectionPad
	 */
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

	/**
	 * Creates a PackedCollectionPad computation with explicit shape and position policies.
	 * This is the most flexible padding method, allowing complete control over the output shape
	 * and input positioning through TraversalPolicy objects.
	 * 
	 * <p>This method directly instantiates a {@link PackedCollectionPad} computation that implements
	 * the padding logic. If the input collection is known to be zero (via {@link Algebraic#isZero}),
	 * this method optimizes by returning a zeros collection instead.</p>
	 * 
	 * <p><strong>Usage in Neural Networks:</strong></p>
	 * <p>Padding is commonly used in convolutional neural networks to:</p>
	 * <ul>
	 * <li>Preserve spatial dimensions after convolution</li>
	 * <li>Handle boundary conditions in image processing</li>
	 * <li>Implement specific architectural patterns (e.g., "same" padding)</li>
	 * </ul>
	 * 
	 * @param shape The complete output shape specification
	 * @param position The positioning policy specifying where input data is placed
	 * @param collection The input collection producer
	 * @param <T> The type of PackedCollection  
	 * @return A CollectionProducerComputation that implements the padding operation,
	 *         or a zeros collection if the input is zero
	 * 
	 * @see PackedCollectionPad
	 * @see TraversalPolicy
	 * @see Algebraic#isZero(Producer)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> pad(TraversalPolicy shape,
																				 TraversalPolicy position,
																				 Producer<?> collection) {
		if (Algebraic.isZero(collection)) {
			return zeros(shape);
		}

		return new PackedCollectionPad<>(shape, position, collection);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(Producer<?> collection, Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return new PackedCollectionMap<>(collection, (Function) mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(TraversalPolicy itemShape, Producer<?> collection,
																				 Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return new PackedCollectionMap<>(shape(collection).replace(itemShape), collection, (Function) mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> reduce(Producer<?> collection,
																					Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return map(shape(1), collection, (Function) mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> expand(int repeat, Producer<?> collection,
																					Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return map(shape(collection).item().prependDimension(repeat), collection, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> cumulativeProduct(Producer<T> input, boolean pad) {
		return func(shape(input), inputs -> args -> {
			PackedCollection<?> in = inputs[0];
			PackedCollection<?> result = new PackedCollection<>(in.getShape());

			double r = 1.0;
			int offset = 0;

			if (pad) {
				result.setMem(0, r);
				offset = 1;
			}

			for (int i = offset; i < in.getMemLength(); i++) {
				r *= in.toDouble(i - offset);
				result.setMem(i, r);
			}

			return result;
		}, input);
	}

	default Random rand(int... dims) { return rand(shape(dims)); }
	default Random rand(TraversalPolicy shape) { return new Random(shape); }

	default Random randn(int... dims) { return randn(shape(dims)); }
	default Random randn(TraversalPolicy shape) { return new Random(shape, true); }

	default DefaultTraversableExpressionComputation<PackedCollection<?>> compute(String name, CollectionExpression expression) {
		return new DefaultTraversableExpressionComputation<>(name, expression.getShape(), expression);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> compute(
			String name, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Producer<T>... arguments) {
		return compute(name, DeltaFeatures.MultiTermDeltaStrategy.NONE, expression, arguments);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> compute(
			String name, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Function<List<String>, String> description,
			Producer<T>... arguments) {
		return compute(name, DeltaFeatures.MultiTermDeltaStrategy.NONE, expression, description, arguments);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> compute(
			String name, DeltaFeatures.MultiTermDeltaStrategy deltaStrategy,
			Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Producer<T>... arguments) {
		return compute(name, deltaStrategy, expression, null, arguments);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> compute(
			String name, DeltaFeatures.MultiTermDeltaStrategy deltaStrategy,
			Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Function<List<String>, String> description, Producer<T>... arguments) {
		return compute((shape, args) -> new DefaultTraversableExpressionComputation(
				name, largestTotalSize(args), deltaStrategy, expression.apply(shape),
				args.toArray(Supplier[]::new)), description, arguments);
	}

	default <T extends PackedCollection<?>, P extends Producer<T>> CollectionProducer<T> compute(
				BiFunction<TraversalPolicy, List<Producer<T>>, P> processor,
				Function<List<String>, String> description,
				Producer<T>... arguments) {
		Producer<T> c = alignTraversalAxes(List.of(arguments), processor);

		if (c instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase<T, T>) c).setDescription(description);
		}

		// TODO  This should use outputShape, so that the calculation isn't
		// TODO  implemented in two separate places
		long count = highestCount(List.of(arguments));

		if (c instanceof Shape) {
			Shape<?> s = (Shape<?>) c;

			if (s.getShape().getCountLong() != count) {
				for (int i = 0; i <= s.getShape().getDimensions(); i++) {
					if (s.getShape().traverse(i).getCountLong() == count) {
						return c((Producer) s.traverse(i));
					}
				}
			}
		}

		return c(c);
	}

	default <T> TraversalPolicy outputShape(Producer<T>... producers) {
		TraversalPolicy result = largestTotalSize(List.of(producers));

		long count = highestCount(List.of(producers));

		if (count != result.getCountLong()) {
			for (int i = 0; i <= result.getDimensions(); i++) {
				if (result.traverse(i).getCountLong() == count) {
					return result.traverse(i);
				}
			}
		}

		return result;
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

	default <T extends PackedCollection<?>> CollectionProducer<T> linear(double start, double end, int steps) {
		if (steps < 2) {
			throw new IllegalArgumentException();
		}

		double step = (end - start) / (steps - 1);
		return integers(0, steps).multiply(c(step));
	}

	/**
	 * Performs element-wise addition of two collections.
	 * This is one of the fundamental arithmetic operations for collections,
	 * adding corresponding elements from each input collection.
	 * 
	 * @param <T> the type of PackedCollection
	 * @param a the first collection to add
	 * @param b the second collection to add
	 * @return a CollectionProducer that generates the element-wise sum
	 * 
	 *
	 * <pre>{@code
	 * // Add two vectors element-wise
	 * CollectionProducer<PackedCollection<?>> vec1 = c(1.0, 2.0, 3.0);
	 * CollectionProducer<PackedCollection<?>> vec2 = c(4.0, 5.0, 6.0);
	 * CollectionProducer<PackedCollection<?>> sum = add(vec1, vec2);
	 * // Result: Producer that generates [5.0, 7.0, 9.0]
	 * 
	 * // Add a constant to a vector
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer<PackedCollection<?>> constant = constant(1.0);
	 * CollectionProducer<PackedCollection<?>> result = add(vector, constant);
	 * // Result: Producer that generates [2.0, 3.0, 4.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> add(Producer<T> a, Producer<T> b) {
		return add(List.of(a, b));
	}

	/**
	 * Performs element-wise addition of multiple collections.
	 * This method can add any number of collections together by summing
	 * corresponding elements across all input collections.
	 * 
	 * <p>This method includes optimizations for constant operations:
	 * when all operands are {@link SingleConstantComputation} instances,
	 * the method computes the sum directly and returns a new constant
	 * computation, avoiding the overhead of the full computation pipeline.</p>
	 * 
	 * @param <T> the type of PackedCollection
	 * @param operands the list of collections to add together
	 * @return a CollectionProducer that generates the element-wise sum
	 * @throws IllegalArgumentException if any operand is null
	 * 
	 * @see SingleConstantComputation
	 *
	 * <pre>{@code
	 * // Add three vectors together
	 * CollectionProducer<PackedCollection<?>> vec1 = c(1.0, 2.0);
	 * CollectionProducer<PackedCollection<?>> vec2 = c(3.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> vec3 = c(5.0, 6.0);
	 * CollectionProducer<PackedCollection<?>> sum = add(List.of(vec1, vec2, vec3));
	 * // Result: Producer that generates [9.0, 12.0] (1+3+5, 2+4+6)
	 * 
	 * // Add multiple constants (optimized)
	 * List<Producer<?>> constants = List.of(
	 *     constant(1.0), constant(2.0), constant(3.0)
	 * );
	 * CollectionProducer<PackedCollection<?>> total = add(constants);
	 * // Result: Producer that generates [6.0] (computed at construction time)
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> add(List<Producer<?>> operands) {
		if (operands.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException();
		}

		if (operands.stream().allMatch(o -> o instanceof SingleConstantComputation)) {
			double value = operands.stream().mapToDouble(o -> ((SingleConstantComputation) o).getConstantValue()).sum();

			return compute((shape, args) -> new SingleConstantComputation<>(shape, value),
					args -> String.join(" + ", applyParentheses(args)),
					operands.toArray(new Producer[0]));
		}

		return compute((shape, args) -> {
					Producer p[] = args.stream().filter(Predicate.not(Algebraic::isZero)).toArray(Producer[]::new);

					if (p.length == 0) {
						return zeros(shape);
					} else if (p.length == 1) {
						return c(reshape(shape, p[0]));
					}

					return new CollectionSumComputation<>(shape, p);
				},
				args -> String.join(" + ", applyParentheses(args)),
				operands.toArray(new Producer[0]));
	}

	/**
	 * Performs element-wise subtraction of two collections.
	 * This operation subtracts corresponding elements of the second collection
	 * from the first collection, equivalent to {@link #add add(a, minus(b))}.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param a the collection to subtract from (minuend)
	 * @param b the collection to subtract (subtrahend)
	 * @return a {@link CollectionProducer} that generates the element-wise difference
	 * 
	 *
	 * <pre>{@code
	 * // Subtract two vectors element-wise
	 * CollectionProducer<PackedCollection<?>> vec1 = c(5.0, 8.0, 12.0);
	 * CollectionProducer<PackedCollection<?>> vec2 = c(2.0, 3.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> difference = subtract(vec1, vec2);
	 * // Result: Producer that generates [3.0, 5.0, 8.0] (5-2, 8-3, 12-4)
	 * 
	 * // Subtract a constant from a vector
	 * CollectionProducer<PackedCollection<?>> vector = c(10.0, 20.0, 30.0);
	 * CollectionProducer<PackedCollection<?>> constant = constant(5.0);
	 * CollectionProducer<PackedCollection<?>> result = subtract(vector, constant);
	 * // Result: Producer that generates [5.0, 15.0, 25.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> subtract(Producer<T> a, Producer<T> b) {
		return add(a, minus(b));
	}

	/**
	 * Performs element-wise subtraction while ignoring operations that would result in zero.
	 * This method uses epsilon-based floating-point comparison to determine when the operands
	 * are effectively equal, avoiding unnecessary computation in those cases.
	 * 
	 * <p>The implementation uses {@link EpsilonConstantComputation} to create a tolerance threshold
	 * for floating-point equality comparison. When two values are equal within epsilon tolerance,
	 * the subtraction is skipped and the original value is preserved rather than computing a
	 * potentially inaccurate zero result.</p>
	 * 
	 * <p>This is particularly useful in numerical computations where:</p>
	 * <ul>
	 *   <li>Floating-point precision errors might cause (a - a) to not equal exactly 0.0</li>
	 *   <li>Avoiding unnecessary computation when operands are effectively equal</li>
	 *   <li>Maintaining numerical stability in iterative algorithms</li>
	 * </ul>
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param a the minuend (value to subtract from)
	 * @param b the subtrahend (value to subtract)
	 * @return a {@link CollectionProducerComputation} that performs epsilon-aware subtraction
	 * 
	 * @see EpsilonConstantComputation
	 * @see #equals(Producer, Producer, Producer, Producer)
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subtractIgnoreZero(Producer<T> a, Producer<T> b) {
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

		CollectionProducer difference = equals(a, b, new EpsilonConstantComputation(shape), add(a, minus(b)));
		return (CollectionProducerComputation) equals(a, c(0.0), zeros(shape), difference);
	}

	/**
	 * Performs element-wise multiplication of two collections.
	 * This is a fundamental arithmetic operation that multiplies corresponding
	 * elements from each input collection.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 * 
	 *
	 * <pre>{@code
	 * // Multiply two vectors element-wise
	 * CollectionProducer<PackedCollection<?>> vec1 = c(2.0, 3.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> vec2 = c(5.0, 6.0, 7.0);
	 * CollectionProducer<PackedCollection<?>> product = multiply(vec1, vec2);
	 * // Result: Producer that generates [10.0, 18.0, 28.0]
	 * 
	 * // Scale a vector by a constant
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer<PackedCollection<?>> scale = constant(2.0);
	 * CollectionProducer<PackedCollection<?>> scaled = multiply(vector, scale);
	 * // Result: Producer that generates [2.0, 4.0, 6.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> multiply(
			Producer<T> a, Producer<T> b) {
		return multiply(a, b, null);
	}

	/**
	 * Performs element-wise multiplication with an optional short-circuit evaluation.
	 * This overload allows for optimization by providing a pre-computed result
	 * that can be used instead of performing the actual computation.
	 * 
	 * <p>This method includes several optimizations:</p>
	 * <ul>
	 *   <li>Identity element detection (multiplication by 1.0)</li>
	 *   <li>Constant multiplication optimization using {@link SingleConstantComputation}</li>
	 *   <li>When both operands are constants, computes the result directly</li>
	 *   <li>Scalar constant multiplication optimization</li>
	 * </ul>
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @param shortCircuit optional pre-computed result for optimization
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 * 
	 * @see SingleConstantComputation
	 *
	 * <pre>{@code
	 * // Multiply with potential optimization
	 * CollectionProducer<PackedCollection<?>> vec1 = c(2.0, 3.0);
	 * CollectionProducer<PackedCollection<?>> vec2 = c(1.0, 1.0);
	 * 
	 * // Pre-compute result for optimization
	 * Evaluable<PackedCollection<?>> precomputed = () -> pack(2.0, 3.0);
	 * CollectionProducer<PackedCollection<?>> result = multiply(vec1, vec2, precomputed);
	 * // May use precomputed result if beneficial
	 * 
	 * // Constant multiplication (optimized)
	 * CollectionProducer<PackedCollection<?>> constant1 = constant(2.0);
	 * CollectionProducer<PackedCollection<?>> constant2 = constant(3.0);
	 * CollectionProducer<PackedCollection<?>> product = multiply(constant1, constant2);
	 * // Result: constant(6.0) computed directly without full pipeline
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> multiply(
			Producer<T> a, Producer<T> b,
			Evaluable<T> shortCircuit) {
		if (checkComputable(a) && checkComputable(b)) {
			if (shape(a).getTotalSizeLong() == 1 && Algebraic.isIdentity(1, a)) {
				return withShortCircuit(c(b), shortCircuit);
			} else if (shape(b).getTotalSizeLong() == 1 && Algebraic.isIdentity(1, b)) {
				return withShortCircuit(c(a), shortCircuit);
			} else if (a instanceof SingleConstantComputation && b instanceof SingleConstantComputation) {
				double value = ((SingleConstantComputation) a).getConstantValue() * ((SingleConstantComputation) b).getConstantValue();
				return constant(outputShape(a, b), value);
			}

			if (a instanceof SingleConstantComputation) {
				CollectionProducer<T> result = multiply(((SingleConstantComputation) a).getConstantValue(), b);
				if (result != null) return withShortCircuit(result, shortCircuit);
			}

			if (b instanceof SingleConstantComputation) {
				CollectionProducer<T> result = multiply(((SingleConstantComputation) b).getConstantValue(), a);
				if (result != null) return withShortCircuit(result, shortCircuit);
			}
		}

		return withShortCircuit(compute((shape, args) -> {
					if (args.stream().anyMatch(Algebraic::isZero)) {
						// Mathematical optimization: anything * 0 = 0
						// Returns CollectionZerosComputation to avoid unnecessary computation
						return zeros(shape);
					}

					return new CollectionProductComputation<>(shape, args.toArray(new Producer[0]));
				},
				args -> String.join(" * ", applyParentheses(args)), a, b), shortCircuit);
	}

	/**
	 * Multiplies a collection by a scalar value.
	 * This is an optimized operation for scaling all elements of a collection
	 * by the same constant factor.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param scale the scalar value to multiply by
	 * @param a the collection to scale
	 * @return a {@link CollectionProducer} that generates the scaled collection, or null if no optimization available
	 * 
	 *
	 * <pre>{@code
	 * // Scale a vector by 2
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer<PackedCollection<?>> doubled = multiply(2.0, vector);
	 * // Result: Producer that generates [2.0, 4.0, 6.0]
	 * 
	 * // Scale by zero to create zero vector
	 * CollectionProducer<PackedCollection<?>> zeros = multiply(0.0, vector);
	 * // Result: Producer that generates [0.0, 0.0, 0.0]
	 * 
	 * // Scale by -1 to negate
	 * CollectionProducer<PackedCollection<?>> negated = multiply(-1.0, vector);
	 * // Result: Producer that generates [-1.0, -2.0, -3.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> multiply(double scale, Producer<T> a) {
		if (scale == 0) {
			// Mathematical optimization: 0 * anything = 0
			// Returns CollectionZerosComputation with same shape as input
			return zeros(shape(a));
		} else if (scale == 1.0) {
			return c(a);
		} else if (scale == -1.0) {
			return minus(a);
		} else if (a.isConstant()) {
			return multiply(shape(a), scale, a.get());
		} else {
			return null;
		}
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> multiply(TraversalPolicy shape, double scale, Evaluable<T> a) {
		return c(shape, a.evaluate().doubleStream().parallel().map(d -> d * scale).toArray());
	}

	/**
	 * Performs element-wise division of two collections.
	 * This operation divides corresponding elements of the first collection
	 * by the corresponding elements of the second collection.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param a the dividend collection (numerator)
	 * @param b the divisor collection (denominator)
	 * @return a {@link CollectionProducer} that generates the element-wise quotient
	 * @throws UnsupportedOperationException if attempting to divide by zero
	 * 
	 *
	 * <pre>{@code
	 * // Divide two vectors element-wise
	 * CollectionProducer<PackedCollection<?>> numerator = c(12.0, 15.0, 20.0);
	 * CollectionProducer<PackedCollection<?>> denominator = c(3.0, 5.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> quotient = divide(numerator, denominator);
	 * // Result: Producer that generates [4.0, 3.0, 5.0] (12/3, 15/5, 20/4)
	 * 
	 * // Divide by a constant (scalar division)
	 * CollectionProducer<PackedCollection<?>> vector = c(10.0, 20.0, 30.0);
	 * CollectionProducer<PackedCollection<?>> divisor = constant(2.0);
	 * CollectionProducer<PackedCollection<?>> halved = divide(vector, divisor);
	 * // Result: Producer that generates [5.0, 10.0, 15.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> divide(Producer<T> a, Producer<T> b) {
		if (Algebraic.isZero(b)) {
			throw new UnsupportedOperationException();
		} else if (Algebraic.isZero(a)) {
			// Mathematical optimization: 0 / anything = 0
			// Returns CollectionZerosComputation for efficiency
			return zeros(outputShape(a, b));
		}

		CollectionProducer<T> p = compute("divide",
				shape -> (args) ->
						quotient(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				(List<String> args) -> String.join(" / ", applyParentheses(args)), a, b);

		CollectionProducerComputationBase c;

		if (p instanceof ReshapeProducer) {
			c = (CollectionProducerComputationBase) ((ReshapeProducer) p).getComputation();
		} else {
			c = (CollectionProducerComputationBase) p;
		}

		c.setDeltaAlternate(multiply(a, pow(b, c(-1.0))));
		return p;
	}

	/**
	 * Negates all elements in a collection (unary minus operation).
	 * This operation multiplies every element by -1, effectively flipping
	 * the sign of all values in the collection.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param a the collection to negate
	 * @return a {@link CollectionProducerComputationBase} that generates the negated collection
	 * 
	 *
	 * <pre>{@code
	 * // Negate a vector
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, -2.0, 3.0, -4.0);
	 * CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> negated = minus(vector);
	 * // Result: Producer that generates [-1.0, 2.0, -3.0, 4.0]
	 * 
	 * // Negate a constant (optimized case)
	 * CollectionProducer<PackedCollection<?>> constant = constant(5.0);
	 * CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> negatedConstant = minus(constant);
	 * // Result: Producer that generates [-5.0]
	 * 
	 * // Negate a matrix
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
	 * CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> negatedMatrix = minus(matrix);
	 * // Result: Producer that generates 2x2 matrix [[-1,-2], [-3,-4]]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> minus(Producer<T> a) {
		TraversalPolicy shape = shape(a);
		int w = shape.length(0);

		if (shape.getTotalSizeLong() == 1 && a.isConstant() && Countable.isFixedCount(a)) {
			return new AtomicConstantComputation<>(-a.get().evaluate().toDouble());
		} else if (Algebraic.isIdentity(w, a)) {
			return (CollectionProducerComputationBase)
					new ScalarMatrixComputation<>(shape, c(-1))
						.setDescription(args -> "-" + DescribableParent.description(a));
		}

		return (CollectionProducerComputationBase)
				new CollectionMinusComputation<>(shape, a)
						.setDescription(args -> "-" + args.get(0));
	}

	/**
	 * Computes the square root of each element in a collection.
	 * This is a convenience method that raises each element to the power of 0.5,
	 * providing a more readable way to compute square roots.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param value the collection containing values to compute square roots for
	 * @return a {@link CollectionProducer} that generates the element-wise square roots
	 * 
	 *
	 * <pre>{@code
	 * // Compute square roots of elements
	 * CollectionProducer<PackedCollection<?>> values = c(4.0, 9.0, 16.0, 25.0);
	 * CollectionProducer<PackedCollection<?>> roots = sqrt(values);
	 * // Result: Producer that generates [2.0, 3.0, 4.0, 5.0]
	 * 
	 * // Square root of a single value
	 * CollectionProducer<PackedCollection<?>> number = c(64.0);
	 * CollectionProducer<PackedCollection<?>> root = sqrt(number);
	 * // Result: Producer that generates [8.0]
	 * 
	 * // Square root in mathematical expressions
	 * CollectionProducer<PackedCollection<?>> squares = c(1.0, 4.0, 9.0);
	 * CollectionProducer<PackedCollection<?>> magnitude = sqrt(sum(squares));
	 * // Result: sqrt(1+4+9) = sqrt(14) ≈ 3.74
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> sqrt(Producer<T> value) {
		T half = (T) new PackedCollection<>(1);
		half.setMem(0.5);
		return pow(value, c(half));
	}

	/**
	 * Raises elements of the base collection to the power of corresponding elements in the exponent collection.
	 * This operation performs element-wise exponentiation, computing base[i]^exp[i] for each element.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param base the base collection (values to be raised to powers)
	 * @param exp the exponent collection (power values)
	 * @return a {@link CollectionProducer} that generates the element-wise power results
	 * 
	 *
	 * <pre>{@code
	 * // Raise elements to specified powers
	 * CollectionProducer<PackedCollection<?>> base = c(2.0, 3.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> exponent = c(2.0, 3.0, 0.5);
	 * CollectionProducer<PackedCollection<?>> powers = pow(base, exponent);
	 * // Result: Producer that generates [4.0, 27.0, 2.0] (2^2, 3^3, 4^0.5)
	 * 
	 * // Square all elements (power of 2)
	 * CollectionProducer<PackedCollection<?>> values = c(1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> two = constant(2.0);
	 * CollectionProducer<PackedCollection<?>> squares = pow(values, two);
	 * // Result: Producer that generates [1.0, 4.0, 9.0, 16.0]
	 * 
	 * // Square root (power of 0.5)
	 * CollectionProducer<PackedCollection<?>> numbers = c(4.0, 9.0, 16.0, 25.0);
	 * CollectionProducer<PackedCollection<?>> half = constant(0.5);
	 * CollectionProducer<PackedCollection<?>> roots = pow(numbers, half);
	 * // Result: Producer that generates [2.0, 3.0, 4.0, 5.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> pow(Producer<T> base, Producer<T> exp) {
		if (Algebraic.isIdentity(1, base)) {
			TraversalPolicy shape = shape(exp);

			if (shape.getTotalSizeLong() == 1) {
				return (CollectionProducer<T>) base;
			} else {
				return (CollectionProducer<T>) repeat(shape.getTotalSize(), base).reshape(shape);
			}
		} else if (base.isConstant() && exp.isConstant()) {
			if (shape(base).getTotalSizeLong() == 1 && shape(exp).getTotalSizeLong() == 1) {
				return c(Math.pow(base.get().evaluate().toDouble(), exp.get().evaluate().toDouble()));
			}

			console.warn("Computing power of constants");
		}

		return compute((shape, args) ->
						new CollectionExponentComputation<>(largestTotalSize(args), args.get(0), args.get(1)),
				args -> applyParentheses(args.get(0)) + " ^ " + applyParentheses(args.get(1)),
				base, exp);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> exp(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return new CollectionExponentialComputation<>(shape(value), false, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> expIgnoreZero(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return new CollectionExponentialComputation<>(shape(value), true, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> log(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return new CollectionLogarithmComputation<>(shape(value), value);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> sq(Producer<T> value) {
		return multiply(value, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> floor(
			Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation<>(
				"floor", shape,
				args -> new UniformCollectionExpression("floor", shape, in -> Floor.of(in[0]), args[1]),
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
				args -> new UniformCollectionExpression("min", shape,
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
				args -> new UniformCollectionExpression("max", shape,
								in -> Max.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> rectify(Producer<T> a) {
		// TODO  Add short-circuit
		return compute("rectify", shape -> args ->
						rectify(shape, args[1]), a);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> mod(Producer<T> a, Producer<T> b) {
		// TODO  Add short-circuit
		return compute("mod", shape -> args ->
						mod(shape, args[1], args[2]), a, b);
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

	/**
	 * Computes the absolute value of each element in a collection.
	 * This operation converts all negative values to positive while
	 * leaving positive values unchanged.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param value the collection containing values to compute absolute values for
	 * @return a {@link CollectionProducer} that generates the element-wise absolute values
	 * 
	 *
	 * <pre>{@code
	 * // Compute absolute values
	 * CollectionProducer<PackedCollection<?>> values = c(-3.0, -1.0, 0.0, 2.0, -5.0);
	 * CollectionProducer<PackedCollection<?>> absolutes = abs(values);
	 * // Result: Producer that generates [3.0, 1.0, 0.0, 2.0, 5.0]
	 * 
	 * // Absolute value of differences
	 * CollectionProducer<PackedCollection<?>> a = c(10.0, 5.0, 8.0);
	 * CollectionProducer<PackedCollection<?>> b = c(7.0, 9.0, 3.0);
	 * CollectionProducer<PackedCollection<?>> distance = abs(subtract(a, b));
	 * // Result: Producer that generates [3.0, 4.0, 5.0] (absolute differences)
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> abs(Producer<T> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation<>(
				"abs", shape,
				args -> new UniformCollectionExpression("abs", shape, in -> new Absolute(in[0]), args[1]),
				(Supplier) value);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> magnitude(Producer<T> vector) {
		if (shape(vector).getSize() == 1) {
			return abs(vector);
		} else {
			return sq(vector).sum().sqrt();
		}
	}

	/**
	 * Finds the maximum element in a collection.
	 * This reduction operation scans through all elements and returns
	 * the largest value as a single-element collection.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param input the collection to find the maximum element in
	 * @return a {@link CollectionProducerComputationBase} that generates the maximum value
	 * 
	 *
	 * <pre>{@code
	 * // Find maximum in a vector
	 * CollectionProducer<PackedCollection<?>> values = c(3.0, 7.0, 2.0, 9.0, 5.0);
	 * CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> maximum = max(values);
	 * // Result: Producer that generates [9.0]
	 * 
	 * // Find maximum in a matrix (flattened)
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(2, 3), 1.0, 8.0, 3.0, 4.0, 2.0, 6.0);
	 * CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> matrixMax = max(matrix);
	 * // Result: Producer that generates [8.0] (maximum across all elements)
	 * 
	 * // Maximum of negative numbers
	 * CollectionProducer<PackedCollection<?>> negatives = c(-5.0, -2.0, -8.0, -1.0);
	 * CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> negMax = max(negatives);
	 * // Result: Producer that generates [-1.0] (least negative = maximum)
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> max(Producer<T> input) {
		DynamicIndexProjectionProducerComputation<T> projection =
				new DynamicIndexProjectionProducerComputation<>("projectMax", shape(input).replace(shape(1)),
						(args, idx) -> args[2].getValueAt(idx).toInt(),
						true, input, indexOfMax(input));

		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		AggregatedProducerComputation c = new AggregatedProducerComputation<>("max", shape.replace(shape(1)), size,
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
			return new TraversableRepeatedProducerComputation<>("indexOfMax", shape.replace(shape(1)), size,
					(args, index) -> e(0),
					(args, currentIndex) -> index ->
							conditional(args[1].getValueRelative(index)
											.greaterThan(args[1].getValueRelative(currentIndex)),
									index, currentIndex),
					(Supplier) input);
		} else {
			return new ConstantRepeatedProducerComputation<>("indexOfMax", shape.replace(shape(1)), size,
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

	/**
	 * Computes the sum of all elements in a collection.
	 * This reduction operation adds up all elements to produce a single scalar result.
	 * It's one of the most common aggregation operations in numerical computing.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param input the collection to sum
	 * @return a {@link CollectionProducerComputation} that generates a single-element collection containing the sum
	 * 
	 *
	 * <pre>{@code
	 * // Sum all elements in a vector
	 * CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> total = sum(vector);
	 * // Result: Producer that generates [10.0] (1+2+3+4)
	 * 
	 * // Sum elements in a matrix (flattened)
	 * CollectionProducer<PackedCollection<?>> matrix = c(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer<PackedCollection<?>> matrixSum = sum(matrix);
	 * // Result: Producer that generates [10.0] (1+2+3+4)
	 * 
	 * // Sum of zeros returns zero
	 * CollectionProducer<PackedCollection<?>> zeros = zeros(shape(5));
	 * CollectionProducer<PackedCollection<?>> zeroSum = sum(zeros);
	 * // Result: Producer that generates [0.0]
	 * }</pre>
	 */
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> sum(Producer<T> input) {
		if (Algebraic.isZero(input)) {
			// Mathematical optimization: sum(zeros) = 0
			// Returns scalar zero using CollectionZerosComputation
			return zeros(shape(input).replace(shape(1)));
		}

		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		AggregatedProducerComputation<T> sum = new AggregatedProducerComputation<>("sum", shape.replace(shape(1)), size,
				(args, index) -> e(0.0),
				(out, arg) -> out.add(arg),
				(Supplier) input);
		sum.setReplaceLoop(true);
		return sum;
	}

	/**
	 * Computes the arithmetic mean (average) of all elements in a collection.
	 * This is calculated as the sum of all elements divided by the number of elements.
	 * 
	 * @param <T> the type of {@link PackedCollection}
	 * @param input the collection to compute the mean for
	 * @return a {@link CollectionProducer} that generates a single-element collection containing the mean
	 * 
	 *
	 * <pre>{@code
	 * // Calculate mean of a vector
	 * CollectionProducer<PackedCollection<?>> vector = c(2.0, 4.0, 6.0, 8.0);
	 * CollectionProducer<PackedCollection<?>> average = mean(vector);
	 * // Result: Producer that generates [5.0] ((2+4+6+8)/4)
	 * 
	 * // Mean of a single element
	 * CollectionProducer<PackedCollection<?>> single = c(42.0);
	 * CollectionProducer<PackedCollection<?>> singleMean = mean(single);
	 * // Result: Producer that generates [42.0] (42/1)
	 * 
	 * // Mean of mixed positive/negative values
	 * CollectionProducer<PackedCollection<?>> mixed = c(-2.0, 0.0, 2.0);
	 * CollectionProducer<PackedCollection<?>> mixedMean = mean(mixed);
	 * // Result: Producer that generates [0.0] ((-2+0+2)/3)
	 * }</pre>
	 */
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

	/**
	 * Performs element-wise equality comparison between two collections with custom return values.
	 * This method compares corresponding elements and returns specified values based on the comparison result.
	 * 
	 * @param <T> the type of {@link PackedCollection} to produce
	 * @param a the first collection to compare
	 * @param b the second collection to compare  
	 * @param trueValue the value to return when elements are equal
	 * @param falseValue the value to return when elements are not equal
	 * @return a {@link CollectionProducer} that generates comparison results
	 * 
	 * @see org.almostrealism.collect.computations.CollectionComparisonComputation
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> equals(Producer<?> a, Producer<?> b,
																		Producer<T> trueValue, Producer<T> falseValue) {
		return compute((shape, args) ->
						new CollectionComparisonComputation("equals", shape,
								args.get(0), args.get(1), args.get(2), args.get(3)),
				null,
				(Producer) a, (Producer) b,
				(Producer) trueValue, (Producer) falseValue);
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
				args -> new ComparisonExpression("greaterThan", shape,
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

	default <T extends Shape<?>> CollectionProducer<T> delta(Producer<T> producer, Producer<?> target) {
		CollectionProducer<T> result = MatrixFeatures.getInstance().attemptDelta(producer, target);
		if (result != null) return result;

		return (CollectionProducer) c(producer).delta(target);
	}

	default <T extends PackedCollection<?>> CollectionProducer<PackedCollection<?>> combineGradient(
			CollectionProducer<T> func,
			Producer<T> input, Producer<T> gradient) {
		int inSize = shape(input).getTotalSize();
		int outSize = shape(gradient).getTotalSize();
		return multiplyGradient(func.delta(input).reshape(outSize, inSize)
				.traverse(1), gradient, inSize)
				.traverse(0)
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(inSize))
				.each();
	}

	default <T extends PackedCollection<?>> CollectionProducer<PackedCollection<?>> multiplyGradient(
			CollectionProducer<T> p, Producer<T> gradient, int inSize) {
		int outSize = shape(gradient).getTotalSize();

		if (enableGradientMultiplyEach) {
			return p.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize));
		} else {
			return p.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize).traverse(1));
		}
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

	default <T, P extends Producer<T>> P withShortCircuit(P producer, Evaluable<T> shortCircuit) {
		if (producer instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) producer).setShortCircuit(shortCircuit);
		}

		return producer;
	}

	default List<String> applyParentheses(List<String> args) {
		return args.stream().map(this::applyParentheses).collect(Collectors.toList());
	}

	default String applyParentheses(String value) {
		if (value.contains(" ")) {
			return "(" + value + ")";
		} else {
			return value;
		}
	}

	static boolean checkComputable(Producer<?> p) {
		if (p instanceof CollectionProducerComputation) {
			return true;
		} else if (p instanceof ReshapeProducer) {
			return checkComputable(((ReshapeProducer) p).getComputation());
		} else {
			return false;
		}
	}

	static CollectionFeatures getInstance() {
		return new CollectionFeatures() { };
	}
}
