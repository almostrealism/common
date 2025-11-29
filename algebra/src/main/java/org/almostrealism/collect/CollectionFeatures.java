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
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.ComparisonExpression;
import io.almostrealism.collect.IndexOfPositionExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.ScalarMatrixComputation;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.computations.AggregatedProducerComputation;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.AtomicConstantComputation;
import org.almostrealism.collect.computations.CollectionAddComputation;
import org.almostrealism.collect.computations.CollectionComparisonComputation;
import org.almostrealism.collect.computations.CollectionConjunctionComputation;
import org.almostrealism.collect.computations.CollectionExponentComputation;
import org.almostrealism.collect.computations.CollectionExponentialComputation;
import org.almostrealism.collect.computations.CollectionLogarithmComputation;
import org.almostrealism.collect.computations.CollectionMaxComputation;
import org.almostrealism.collect.computations.CollectionMinusComputation;
import org.almostrealism.collect.computations.CollectionPermute;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProductComputation;
import org.almostrealism.collect.computations.CollectionProvider;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.CollectionZerosComputation;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.DynamicIndexProjectionProducerComputation;
import org.almostrealism.collect.computations.EpsilonConstantComputation;
import org.almostrealism.collect.computations.GreaterThanCollection;
import org.almostrealism.collect.computations.LessThanCollection;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionMap;
import org.almostrealism.collect.computations.PackedCollectionPad;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.SingleConstantComputation;
import org.almostrealism.collect.computations.TraversableRepeatedProducerComputation;
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
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Comprehensive factory interface for creating {@link CollectionProducer} computations.
 *
 * <p>
 * {@link CollectionFeatures} is the primary API for building computational graphs in the Almost Realism
 * framework. It provides over 200 factory methods organized into functional categories, enabling users
 * to construct complex hardware-accelerated operations through simple method calls.
 * </p>
 *
 * <h2>Design Pattern</h2>
 * <p>
 * This interface is designed as a mixin that classes can implement to gain access to all factory methods:
 * </p>
 * <pre>{@code
 * public class MyComputation implements CollectionFeatures {
 *     public void example() {
 *         // All factory methods available directly
 *         CollectionProducer<?> data = c(10.0);
 *         CollectionProducer<?> result = add(data, c(5.0));
 *     }
 * }
 * }</pre>
 *
 * <h2>Method Categories</h2>
 *
 * <h3>1. Shape Factory Methods</h3>
 * <ul>
 *   <li>{@code shape(int...)} - Create traversal policies</li>
 *   <li>{@code shape(Producer<?>)} - Extract shape from producers</li>
 *   <li>{@code traverse(int, Producer)} - Traverse along axis</li>
 *   <li>{@code reshape(TraversalPolicy, Producer)} - Reshape producers</li>
 * </ul>
 *
 * <h3>2. Producer Creation</h3>
 * <ul>
 *   <li>{@code v(Class)} - Create variable producers</li>
 *   <li>{@code c(double)} - Create constant scalar producers</li>
 *   <li>{@code c(double...)} - Create constant collection producers</li>
 *   <li>{@code p(PackedCollection)} - Wrap collections as producers</li>
 * </ul>
 *
 * <h3>3. Arithmetic Operations</h3>
 * <ul>
 *   <li>{@code add(Producer, Producer)} - Element-wise addition</li>
 *   <li>{@code subtract(Producer, Producer)} - Element-wise subtraction</li>
 *   <li>{@code multiply(Producer, Producer)} - Element-wise multiplication</li>
 *   <li>{@code divide(Producer, Producer)} - Element-wise division</li>
 *   <li>{@code pow(Producer, Producer)} - Element-wise exponentiation</li>
 *   <li>{@code sqrt(Producer)} - Square root</li>
 *   <li>{@code minus(Producer)} - Negation</li>
 * </ul>
 *
 * <h3>4. Mathematical Functions</h3>
 * <ul>
 *   <li>{@code exp(Producer)} - Exponential (e^x)</li>
 *   <li>{@code log(Producer)} - Natural logarithm</li>
 *   <li>{@code abs(Producer)} - Absolute value</li>
 *   <li>{@code sq(Producer)} - Square (x^2)</li>
 *   <li>{@code sigmoid(Producer)} - Sigmoid activation</li>
 *   <li>{@code mod(Producer, Producer)} - Modulo operation</li>
 * </ul>
 *
 * <h3>5. Statistical Operations</h3>
 * <ul>
 *   <li>{@code sum(Producer)} - Sum all elements</li>
 *   <li>{@code mean(Producer)} - Mean of all elements</li>
 *   <li>{@code variance(Producer)} - Variance</li>
 *   <li>{@code max(Producer)} - Maximum value</li>
 *   <li>{@code indexOfMax(Producer)} - Index of maximum</li>
 *   <li>{@code magnitude(Producer)} - L2 norm</li>
 *   <li>{@code subtractMean(Producer)} - Center data by subtracting mean</li>
 * </ul>
 *
 * <h3>6. Comparison and Logical Operations</h3>
 * <ul>
 *   <li>{@code greaterThan(Producer, Producer)} - Element-wise &gt; comparison</li>
 *   <li>{@code lessThan(Producer, Producer)} - Element-wise &lt; comparison</li>
 *   <li>{@code greaterThanOrEqual(Producer, Producer)} - Element-wise &gt;= comparison</li>
 *   <li>{@code lessThanOrEqual(Producer, Producer)} - Element-wise &lt;= comparison</li>
 *   <li>{@code and(Producer, Producer)} - Logical AND</li>
 *   <li>{@code conditional selection via greaterThan/lessThan overloads}</li>
 * </ul>
 *
 * <h3>7. Transformation Operations</h3>
 * <ul>
 *   <li>{@code repeat(int, Producer)} - Repeat along axis</li>
 *   <li>{@code enumerate(TraversalPolicy, Producer)} - Extract indexed elements</li>
 *   <li>{@code subset(TraversalPolicy, Producer, int...)} - Extract subset</li>
 *   <li>{@code permute(Producer, int...)} - Permute dimensions</li>
 *   <li>{@code pad(Producer, int...)} - Add zero padding</li>
 *   <li>{@code map(Producer, Function)} - Map function over elements</li>
 *   <li>{@code reduce(Producer, Function)} - Reduce operation</li>
 * </ul>
 *
 * <h3>8. Constant Generators</h3>
 * <ul>
 *   <li>{@code zeros(TraversalPolicy)} - Create zero-filled collection</li>
 *   <li>{@code epsilon()} - Machine epsilon constant</li>
 *   <li>{@code random(TraversalPolicy)} - Uniform random values</li>
 *   <li>{@code integers(int, int)} - Integer sequence</li>
 * </ul>
 *
 * <h3>9. Advanced Operations</h3>
 * <ul>
 *   <li>{@code enumerate()} - Index enumeration for advanced indexing</li>
 *   <li>{@code traverseEach(Producer)} - Traverse all dimensions</li>
 *   <li>{@code combineGradient()} - Gradient combination for backpropagation</li>
 *   <li>{@code aggregated(Supplier)} - Aggregated producer for batching</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Building a Computation Graph</h3>
 * <pre>{@code
 * CollectionProducer<?> x = v(PackedCollection.class);
 * CollectionProducer<?> weights = c(1.0, 2.0, 3.0);
 *
 * // Build graph: y = sigmoid(x * weights + 0.5)
 * CollectionProducer<?> y = sigmoid(
 *     add(
 *         multiply(x, weights),
 *         c(0.5)
 *     )
 * );
 * }</pre>
 *
 * <h3>Statistical Analysis</h3>
 * <pre>{@code
 * CollectionProducer<?> data = p(myData);
 *
 * // Normalize: (x - mean) / sqrt(variance)
 * CollectionProducer<?> normalized = divide(
 *     subtractMean(data),
 *     sqrt(variance(data))
 * );
 * }</pre>
 *
 * <h3>Conditional Logic</h3>
 * <pre>{@code
 * CollectionProducer<?> x = ...;
 * CollectionProducer<?> y = ...;
 *
 * // ReLU: max(0, x)
 * CollectionProducer<?> relu = greaterThan(x, c(0.0), x, c(0.0));
 *
 * // Clamp: clip values to [min, max]
 * CollectionProducer<?> clamped = lessThan(x, c(min),
 *     c(min),
 *     greaterThan(x, c(max), c(max), x)
 * );
 * }</pre>
 *
 * <h3>Tensor Transformations</h3>
 * <pre>{@code
 * CollectionProducer<?> tensor = p(myTensor); // shape (10, 20, 30)
 *
 * tensor.reshape(shape(200, 30))      // Reshape to (200, 30)
 * tensor.traverse(1)                  // Traverse along axis 1
 * tensor.subset(shape(5, 10), 0, 5)   // Extract (5, 10) subset
 * tensor.permute(2, 0, 1)             // Permute to (30, 10, 20)
 * tensor.pad(1, 2, 3)                 // Add padding
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 *   <li><b>Lazy Evaluation:</b> All methods return producers that build a computation graph;
 *       actual computation happens only when {@code get().evaluate()} is called</li>
 *   <li><b>Hardware Acceleration:</b> Computations are compiled to optimized kernels automatically</li>
 *   <li><b>Shape Inference:</b> Output shapes are inferred from input shapes where possible</li>
 *   <li><b>Broadcasting:</b> Binary operations support NumPy-style broadcasting</li>
 *   <li><b>Type Safety:</b> Generic types help catch shape/type errors at compile time</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li><b>enableShapelessWarning:</b> Warns when creating shapeless producers (default: false)</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see CollectionProducer
 * @see PackedCollection
 * @see TraversalPolicy
 * @see MatrixFeatures
 * @see DeltaFeatures
 */
public interface CollectionFeatures extends ExpressionFeatures {
	boolean enableShapelessWarning = false;
	boolean enableVariableRepeat = false;

	// Should be flipped and removed
	boolean enableIndexProjectionDeltaAlt = true;
	boolean enableCollectionIndexSize = false;

	// Possible future feature
	boolean enableUnaryWeightedSum = false;
	boolean enableSubdivide = enableUnaryWeightedSum;

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
	default TraversalPolicy shape(int... dims) {
		if (dims[0] == -1) {
			if (dims.length == 1) {
				return new TraversalPolicy(false, false, 1);
			}

			return new TraversalPolicy(false, false, IntStream.of(dims).skip(1).toArray());
		}

		return new TraversalPolicy(dims);
	}
	
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
	default TraversalPolicy shape(long... dims) {
		if (dims[0] == -1) {
			if (dims.length == 1) {
				return new TraversalPolicy(false, false, 1);
			}

			return new TraversalPolicy(false, false, LongStream.of(dims).skip(1).toArray());
		}

		return new TraversalPolicy(dims);
	}
	
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
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * TraversalPolicy vectorShape = shape(vector);
	 * // Result: shape with dimensions [3]
	 * 
	 * // Extract shape from arithmetic operation results
	 * CollectionProducer a = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * CollectionProducer b = c(shape(2, 3), 2, 3, 4, 5, 6, 7);
	 * CollectionProducer sum = add(a, b);
	 * TraversalPolicy resultShape = shape(sum);
	 * // Result: shape with dimensions [2, 3]
	 * 
	 * // Extract shape from reshaped {@link CollectionProducer}
	 * CollectionProducer reshaped = vector.reshape(shape(1, 3));
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
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * int vectorSize = size(vector);
	 * // Result: 3 (3 elements in the vector)
	 * 
	 * // Get size of arithmetic operation results
	 * CollectionProducer a = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * CollectionProducer b = c(shape(2, 3), 2, 3, 4, 5, 6, 7);
	 * CollectionProducer sum = add(a, b);
	 * int matrixSize = size(sum);
	 * // Result: 6 (2 * 3 matrix elements)
	 * 
	 * // Get size of reshaped producers
	 * CollectionProducer reshaped = vector.reshape(shape(1, 3));
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
	 * Shape<?> collection = new PackedCollection(shape(2, 3, 4));
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

	default CollectionProducer c(TraversalPolicy shape, Evaluable<PackedCollection> ev) {
		return c(new CollectionProducerBase<PackedCollection, CollectionProducer>() {
			@Override
			public Evaluable<PackedCollection> get() { return ev; }

			@Override
			public TraversalPolicy getShape() {
				return shape;
			}

			@Override
			public CollectionProducer traverse(int axis) {
				return CollectionFeatures.this.traverse(axis, this);
			}

			@Override
			public CollectionProducer reshape(TraversalPolicy shape) {
				return (CollectionProducer) CollectionFeatures.this.reshape(shape, this);
			}
		});
	}

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
			copy.setMem(0, value);
			return cp(copy);
		}
	}

	default CollectionProducer cp(PackedCollection value) {
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

	default Assignment<PackedCollection> a(String shortDescription, Producer<PackedCollection> result, Producer<PackedCollection> value) {
		Assignment<PackedCollection> a = a(result, value);
		a.getMetadata().setShortDescription(shortDescription);
		return a;
	}

	default Assignment<PackedCollection> a(Producer<PackedCollection> result, Producer<PackedCollection> value) {
		TraversalPolicy resultShape = shape(result);
		TraversalPolicy valueShape = shape(value);

		if (resultShape.getSize() != valueShape.getSize()) {
			int axis = TraversalPolicy.compatibleAxis(resultShape, valueShape, true);
			if (axis == -1) {
				throw new IllegalArgumentException();
			} else if (axis < resultShape.getTraversalAxis()) {
				console.warn("Assignment destination (" + resultShape.getCountLong() +
						") adjusted to match source (" + valueShape.getCountLong() + ")");
			}

			return a(traverse(axis, result), value);
		}

		// TODO  Value should be repeated to ensure it is compatible with result
		return new Assignment<>(shape(result).getSize(), result, value);
	}

	default CollectionProducer concat(Producer<PackedCollection>... producers) {
		return concat(0, producers);
	}

	default CollectionProducer concat(int axis, Producer<PackedCollection>... producers) {
		List<TraversalPolicy> shapes = Stream.of(producers)
				.map(this::shape)
				.filter(s -> s.getDimensions() == shape(producers[0]).getDimensions())
				.collect(Collectors.toList());
		if (shapes.size() != producers.length) {
			throw new IllegalArgumentException("All inputs must have the same number of dimensions");
		}

		long[] dims = new long[shapes.get(0).getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			if (i == axis) {
				dims[i] = shapes.stream().mapToLong(s -> s.length(axis)).sum();
			} else {
				dims[i] = shapes.get(0).length(i);
			}
		}

		return concat(new TraversalPolicy(dims), producers);
	}

	default CollectionProducer concat(TraversalPolicy shape, Producer<PackedCollection>... producers) {
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

		if (axis < 0) {
			throw new UnsupportedOperationException();
		}

		int total = 0;
		List<TraversalPolicy> positions = new ArrayList<>();

		for (TraversalPolicy s : shapes) {
			if (total >= shape.length(axis)) {
				throw new IllegalArgumentException("The result is not large enough to concatenate all inputs");
			}

			int[] pos = new int[s.getDimensions()];
			pos[axis] = total;
			total += s.length(axis);
			positions.add(new TraversalPolicy(true, pos));
		}

		return add(IntStream.range(0, shapes.size())
				.mapToObj(i -> pad(shape, positions.get(i), producers[i]))
				.collect(Collectors.toList()));
	}

	default CollectionProducer c(Producer producer) {
		if (producer instanceof CollectionProducer) {
			return (CollectionProducer) producer;
		} else if (producer instanceof Shape) {
			return new ReshapeProducer(((Shape) producer).getShape().getTraversalAxis(), producer);
		} else if (producer != null) {
			throw new UnsupportedOperationException(producer.getClass() + " cannot be converted to a CollectionProducer");
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default CollectionProducerComputation c(Producer supplier, int index) {
		TraversalPolicy shape = shape(1);
		long size = shape(supplier).getSizeLong();
		return new DefaultTraversableExpressionComputation("valueAtIndexRelative", shape,
				args -> {
					if (args[1] == null) {
						throw new UnsupportedOperationException();
					}

					return CollectionExpression.create(shape, idx ->
							args[1].getValueAt(idx.multiply(size).add(index)));
				},
				supplier);
	}

	default CollectionProducerComputation c(Producer<PackedCollection> collection,
											Producer<PackedCollection> index) {
		if (enableCollectionIndexSize) {
			return c(shape(index), collection, index);
		} else {
			return c(shape(collection), collection, index);
		}
	}

	default CollectionProducerComputation c(TraversalPolicy shape,
											Producer<PackedCollection> collection,
											Producer<PackedCollection> index) {
		DefaultTraversableExpressionComputation exp = new DefaultTraversableExpressionComputation("valueAtIndex", shape,
				args -> CollectionExpression.create(shape, idx -> args[1].getValueAt(args[2].getValueAt(idx))),
				collection, index);
		if (shape.getTotalSize() == 1 && Countable.isFixedCount(index)) {
			exp.setShortCircuit(args -> {
				Evaluable<? extends PackedCollection> out = ag -> new PackedCollection(1);
				Evaluable<? extends PackedCollection> c = collection.get();
				Evaluable<? extends PackedCollection> i = index.get();

				PackedCollection col = c.evaluate(args);
				PackedCollection idx = i.evaluate(args);
				PackedCollection dest = out.evaluate(args);
				dest.setMem(col.toDouble((int) idx.toDouble(0)));
				return dest;
			});
		}

		return exp;
	}

	default CollectionProducerComputation c(Producer<PackedCollection> collection,
											Producer<PackedCollection>... pos) {
		return c(collection, shape(collection), pos);
	}

	default CollectionProducerComputation c(Producer<PackedCollection> collection,
											TraversalPolicy collectionShape,
											Producer<PackedCollection>... pos) {
		return c(shape(pos[0]), collection, collectionShape, pos);
	}

	default CollectionProducerComputation c(TraversalPolicy outputShape,
											Producer<PackedCollection> collection,
											TraversalPolicy collectionShape,
											Producer<PackedCollection>... pos) {
		return c(outputShape, collection, index(collectionShape, pos));
	}

	default CollectionProducerComputation index(TraversalPolicy shapeOf,
												Producer<PackedCollection>... pos) {
		return index(shape(pos[0]), shapeOf, pos);
	}

	default CollectionProducerComputation index(TraversalPolicy shape,
												TraversalPolicy shapeOf,
												Producer<PackedCollection>... pos) {
		return new DefaultTraversableExpressionComputation("index", shape,
						(args) ->
								new IndexOfPositionExpression(shape, shapeOf,
										Stream.of(args).skip(1).toArray(TraversableExpression[]::new)), pos);
	}

	default CollectionProducerComputation sizeOf(Producer<PackedCollection> collection) {
		return new DefaultTraversableExpressionComputation("sizeOf", shape(1),
				(args) -> CollectionExpression.create(shape(1),
						index -> ((ArrayVariable) args[1]).length()), collection);
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

	default <T, P extends Producer<PackedCollection>> Producer<PackedCollection> alignTraversalAxes(
			List<Producer<PackedCollection>> producers, BiFunction<TraversalPolicy, List<Producer<PackedCollection>>, P> processor) {
		return TraversalPolicy
				.alignTraversalAxes(
						producers.stream().map(this::shape).collect(Collectors.toList()),
						producers,
						(i, p) -> traverse(i, p),
						(i, p) -> {
							if (enableVariableRepeat || Countable.isFixedCount(p)) {
								return repeat(i, p);
							} else {
								return p;
							}
						},
						processor);
	}

	default <PackedCollection> TraversalPolicy largestTotalSize(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).max(Comparator.comparing(TraversalPolicy::getTotalSizeLong)).get();
	}

	default <PackedCollection> long lowestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).min().getAsLong();
	}

	default <PackedCollection> long highestCount(List<Producer<PackedCollection>> producers) {
		return producers.stream().map(this::shape).mapToLong(TraversalPolicy::getCountLong).max().getAsLong();
	}

	/**
	 * Changes the traversal axis of a collection producer.
	 * This operation modifies how the collection is traversed during computation
	 * without changing the underlying data layout.
	 * 
	 * @param <PackedCollection> the type of PackedCollection
	 * @param axis the new traversal axis (0-based index)
	 * @param producer the collection producer to modify
	 * @return a CollectionProducer with the specified traversal axis
	 * 
	 *
	 * <pre>{@code
	 * // Create a 2D collection and change traversal axis
	 * CollectionProducer matrix = c(shape(3, 4),
	 *     1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
	 * 
	 * // Traverse along axis 0 (rows)
	 * CollectionProducer rowTraversal = traverse(0, matrix);
	 * // Changes how iteration occurs over the matrix
	 * 
	 * // Traverse along axis 1 (columns)
	 * CollectionProducer colTraversal = traverse(1, matrix);
	 * // Different traversal pattern for the same data
	 * }</pre>
	 */
	default CollectionProducer traverse(int axis, Producer<PackedCollection> producer) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).traverse(axis);
		} else if (producer instanceof CollectionProducerComputation) {
			return ((CollectionProducerComputation) producer).traverse(axis);
		}

		return new ReshapeProducer(axis, producer);
	}

	/**
	 * Alias for {@link #traverseEach} - sets up the producer to traverse each element.
	 * This is a convenience method that makes collection operations more readable.
	 * 
	 * @param <PackedCollection> the type of PackedCollection
	 * @param producer the collection producer to modify
	 * @return a Producer configured to traverse each element
	 * 
	 *
	 * <pre>{@code
	 * // Set up element-wise traversal
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * Producer eachElement = each(vector);
	 * // Result: Producer configured for element-wise operations
	 * }</pre>
	 */
	default Producer<PackedCollection> each(Producer<PackedCollection> producer) {
		return traverseEach(producer);
	}

	/**
	 * Configures a producer to traverse each individual element.
	 * This sets up the traversal policy to process every element independently,
	 * which is useful for element-wise operations and transformations.
	 * 
	 * @param <PackedCollection> the type of PackedCollection
	 * @param producer the collection producer to configure
	 * @return a Producer configured for element-wise traversal
	 * 
	 *
	 * <pre>{@code
	 * // Configure for element-wise processing
	 * CollectionProducer matrix = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * Producer elementWise = traverseEach(matrix);
	 * // Result: Producer that can process each of the 6 elements individually
	 * 
	 * // Useful for applying functions to each element
	 * CollectionProducer vector = c(1.0, 4.0, 9.0);
	 * Producer sqrt = traverseEach(vector).sqrt(); // hypothetical sqrt operation
	 * // Would apply sqrt to each element: [1.0, 2.0, 3.0]
	 * }</pre>
	 */
	default Producer traverseEach(Producer<PackedCollection> producer) {
		return new ReshapeProducer(((Shape) producer).getShape().traverseEach(), producer);
	}

	/**
	 * Reshapes a collection producer to have a new shape.
	 * This operation changes the dimensional structure of the collection
	 * while preserving the total number of elements.
	 * 
	 * @param <PackedCollection> the type of Shape
	 * @param shape the new shape for the collection
	 * @param producer the collection producer to reshape
	 * @return a Producer with the new shape
	 * @throws IllegalArgumentException if the new shape has a different total size
	 * 
	 *
	 * <pre>{@code
	 * // Reshape a 1D vector to a 2D matrix
	 * CollectionProducer vector = c(1, 2, 3, 4, 5, 6);
	 * Producer<PackedCollection> matrix = reshape(shape(2, 3), vector);
	 * // Result: 2x3 matrix [[1,2,3], [4,5,6]]
	 * 
	 * // Reshape a matrix to a different matrix
	 * CollectionProducer matrix2x3 = c(shape(2, 3), 1, 2, 3, 4, 5, 6);
	 * Producer<PackedCollection> matrix3x2 = reshape(shape(3, 2), matrix2x3);
	 * // Result: 3x2 matrix [[1,2], [3,4], [5,6]]
	 * 
	 * // Flatten a multi-dimensional array
	 * CollectionProducer tensor = c(shape(2, 2, 2), 1, 2, 3, 4, 5, 6, 7, 8);
	 * Producer<PackedCollection> flattened = reshape(shape(8), tensor);
	 * // Result: 1D vector [1, 2, 3, 4, 5, 6, 7, 8]
	 * }</pre>
	 */
	default Producer reshape(TraversalPolicy shape, Producer producer) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).reshape(shape);
		} else if (producer instanceof CollectionProducerComputation) {
			return ((CollectionProducerComputation) producer).reshape(shape);
		}

		return new ReshapeProducer(shape, producer);
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
	 * PackedCollection image = new PackedCollection(shape(256, 256));
	 * image.fill(Math::random);
	 * 
	 * CollectionProducer patch =
	 *     subset(shape(5, 5), p(image), 100, 150);
	 * PackedCollection result = patch.get().evaluate();
	 * 
	 * // result now contains image[100:105, 150:155]
	 * }</pre>
	 *
	 * <p><strong>Example - 3D volume extraction:</strong></p>
	 * <pre>{@code
	 * // Extract a 10x10x5 sub-volume from a 100x100x50 volume
	 * PackedCollection volume = new PackedCollection(shape(100, 100, 50));
	 * volume.fill(pos -> pos[0] + pos[1] + pos[2]); // example fill
	 * 
	 * CollectionProducer subVolume =
	 *     subset(shape(10, 10, 5), p(volume), 20, 30, 15);
	 * PackedCollection result = subVolume.get().evaluate();
	 * }</pre>
	 *
	 * @param <PackedCollection> The type of PackedCollection being subset
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
	default CollectionProducerComputation subset(TraversalPolicy shape, Producer<?> collection, int... position) {
		return new PackedCollectionSubset(shape, collection, position);
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
	 * PackedCollection data = new PackedCollection(shape(50, 50));
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
	 * CollectionProducer centeredSubset =
	 *     subset(shape(10, 10), p(data), startX, startY);
	 * }</pre>
	 *
	 * @param <PackedCollection> The type of PackedCollection being subset
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position The starting position coordinates as expressions (one per dimension)
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 * 
	 * @see PackedCollectionSubset
	 * @see Expression
	 */
	default CollectionProducerComputation subset(TraversalPolicy shape, Producer<?> collection, Expression... position) {
		return new PackedCollectionSubset(shape, collection, position);
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
	 * PackedCollection timeSeries = new PackedCollection(shape(1000));
	 * timeSeries.fill(pos -> Math.sin(pos[0] * 0.1)); // example signal
	 * 
	 * // Position determined at runtime
	 * PackedCollection windowStart = new PackedCollection(1);
	 * 
	 * // Extract different windows by changing the position
	 * for (int i = 0; i < 950; i += 10) {
	 *     windowStart.set(0, (double) i);
	 *     
	 *     CollectionProducer window =
	 *         subset(shape(50), p(timeSeries), p(windowStart));
	 *     PackedCollection result = window.get().evaluate();
	 *     
	 *     // Process this window...
	 * }
	 * }</pre>
	 *
	 * <p><strong>Example - 2D dynamic region extraction:</strong></p>
	 * <pre>{@code
	 * PackedCollection image = new PackedCollection(shape(640, 480));
	 * image.fill(Math::random);
	 * 
	 * // Dynamic position based on some computed region of interest
	 * PackedCollection roiPosition = new PackedCollection(2);
	 * roiPosition.set(0, detectedObjectX);
	 * roiPosition.set(1, detectedObjectY);
	 * 
	 * // Extract region around detected object
	 * CollectionProducer objectRegion =
	 *     subset(shape(64, 64), p(image), p(roiPosition));
	 * PackedCollection objectPatch = objectRegion.get().evaluate();
	 * }</pre>
	 *
	 * @param <PackedCollection> The type of PackedCollection being subset
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position A Producer that generates position coordinates at runtime
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 * @throws IllegalArgumentException if position producer shape doesn't match collection dimensions
	 * 
	 * @see PackedCollectionSubset
	 * @see Producer
	 */
	default CollectionProducerComputation subset(TraversalPolicy shape, Producer<?> collection, Producer<?> position) {
		return new PackedCollectionSubset(shape, collection, position);
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
	 * @param <PackedCollection> the type of PackedCollection being repeated
	 * @param repeat the number of times to repeat the collection (must be positive)
	 * @param collection the source collection to repeat
	 * @return a computation that produces the repeated collection
	 * 
	 * @see PackedCollectionRepeat
	 * @see PackedCollectionRepeat#shape(int, TraversalPolicy)
	 * @see SingleConstantComputation#reshape(TraversalPolicy)
	 */
	default CollectionProducerComputation repeat(int repeat, Producer<?> collection) {
		if (collection instanceof SingleConstantComputation) {
			return ((SingleConstantComputation) collection)
					.reshape(PackedCollectionRepeat.shape(repeat, shape(collection)));
		}

		return new PackedCollectionRepeat(repeat, collection);
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
	 * @param <PackedCollection> the type of PackedCollection being repeated
	 * @param axis the axis along which to perform repetition
	 * @param repeat the number of times to repeat
	 * @param collection the source collection to repeat
	 * @return a computation that produces the repeated collection
	 * 
	 * @see #repeat(int, Producer)
	 * @see #traverse(int, Producer)
	 */
	default CollectionProducerComputation repeat(int axis, int repeat, Producer<PackedCollection> collection) {
		return repeat(repeat, traverse(axis, collection));
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
	 *
	 * @param axis the axis along which to enumerate (0-based)
	 * @param len the length of each enumerated sequence
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <p><strong>1D Vector Enumeration:</strong></p>
	 * <pre>{@code
	 * // Input: [1, 2, 3, 4, 5, 6] (shape: [6])
	 * CollectionProducer vector = c(1, 2, 3, 4, 5, 6);
	 * CollectionProducerComputation<PackedCollection> enumerated = enumerate(0, 3, vector);
	 * // Output: [[1,2,3], [4,5,6]] (shape: [2, 3])
	 * // Creates 2 non-overlapping sequences of length 3
	 * }</pre>
	 * 
	 *
	 * <p><strong>2D Matrix Column Enumeration:</strong></p>
	 * <pre>{@code
	 * // Input: 3x6 matrix (shape: [3, 6])
	 * CollectionProducer matrix = c(shape(3, 6), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
	 * CollectionProducerComputation<PackedCollection> colEnum = enumerate(1, 2, matrix);
	 * // Output: shape [3, 3, 2] - extracts 3 pairs from each row
	 * // Each row [1,2,3,4,5,6] becomes [[1,2], [3,4], [5,6]]
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 * @see #enumerate(int, int, int, io.almostrealism.relation.Producer)
	 */
	default CollectionProducerComputation enumerate(int axis, int len, Producer<?> collection) {
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
	 *
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
	 * CollectionProducer vector = c(1, 2, 3, 4, 5, 6, 7, 8);
	 * CollectionProducerComputation<PackedCollection> sliding = enumerate(0, 3, 1, vector);
	 * // Output: [[1,2,3], [2,3,4], [3,4,5], [4,5,6], [5,6,7], [6,7,8]] (shape: [6, 3])
	 * // Stride of 1 creates overlapping windows
	 * }</pre>
	 * 
	 *
	 * <p><strong>Strided Convolution Pattern:</strong></p>
	 * <pre>{@code
	 * // Input: 8x10 matrix for 2D stride enumeration
	 * CollectionProducer input = c(shape(8, 10), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection> strided = enumerate(1, 2, 1, input);
	 * // Output: shape [8, 9, 2] - sliding window of size 2 with stride 1 along axis 1
	 * // Each row [a,b,c,d,e,f,g,h,i,j] becomes [[a,b], [b,c], [c,d], ..., [i,j]]
	 * }</pre>
	 * 
	 *
	 * <p><strong>Non-overlapping Blocks:</strong></p>
	 * <pre>{@code
	 * // Input: [1, 2, 3, 4, 5, 6, 7, 8] (shape: [8])
	 * CollectionProducerComputation<PackedCollection> blocks = enumerate(0, 2, 2, vector);
	 * // Output: [[1,2], [3,4], [5,6], [7,8]] (shape: [4, 2])
	 * // Stride equals length, creating non-overlapping blocks
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 * @see #enumerate(int, int, io.almostrealism.relation.Producer)
	 */
	default CollectionProducerComputation enumerate(int axis, int len, int stride, Producer<?> collection) {
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
	 *
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
	 * CollectionProducer matrix = c(shape(4, 4),
	 *     1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
	 * 
	 * // First enumerate along axis 1, then along axis 0
	 * CollectionProducerComputation<PackedCollection> patches =
	 *     enumerate(1, 2, 2, 2, matrix); // 2 repetitions of (len=2, stride=2)
	 * // Output: shape [2, 2, 2, 2] - creates 2x2 grid of 2x2 patches
	 * // Result: 4 non-overlapping 2x2 patches from the input matrix
	 * }</pre>
	 * 
	 *
	 * <p><strong>Multi-dimensional Convolution Pattern:</strong></p>
	 * <pre>{@code
	 * // Input: 4D tensor (batch, channels, height, width)
	 * CollectionProducer input = c(shape(2, 5, 10, 6), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection> conv =
	 *     cp(input).traverse(2).enumerate(3, 3, 1, 2); // Extract 3x3 patches
	 * // Applies enumerate twice along spatial dimensions
	 * // Useful for 2D convolution operations
	 * }</pre>
	 * 
	 *
	 * <p><strong>Attention Window Creation:</strong></p>
	 * <pre>{@code
	 * // Input: sequence of length 8
	 * CollectionProducer sequence = c(1, 2, 3, 4, 5, 6, 7, 8);
	 * CollectionProducerComputation<PackedCollection> windows =
	 *     enumerate(0, 3, 1, 3, sequence); // 3 levels of enumeration
	 * // Creates progressively nested window structures
	 * // Useful for hierarchical attention mechanisms
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 * @see #enumerate(int, int, int, io.almostrealism.relation.Producer)
	 */
	default CollectionProducerComputation enumerate(int axis, int len, int stride, int repeat, Producer<?> collection) {
		CollectionProducerComputation result = null;

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
	 *
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <pre>{@code
	 * // Enumerate 2D patches from a matrix
	 * CollectionProducer matrix = c(shape(10, 10), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection> patches =
	 *     enumerate(shape(10, 2), matrix);
	 * // Output: shape [5, 10, 2] - 5 slices of 10x2 from the input
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 */
	default CollectionProducerComputation enumerate(TraversalPolicy shape,
													Producer<?> collection) {
		PackedCollectionEnumerate enumerate = new PackedCollectionEnumerate(shape, collection);

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
	 *
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param stride the {@link TraversalPolicy} defining the stride pattern
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <pre>{@code
	 * // Custom stride enumeration for complex patterns
	 * CollectionProducer data = c(shape(8, 6), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection> custom =
	 *     enumerate(shape(2, 3), shape(1, 1), data);
	 * // Creates overlapping 2x3 patches with stride 1 in both dimensions
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 */

	default CollectionProducerComputation enumerate(TraversalPolicy shape,
													TraversalPolicy stride,
													Producer<?> collection) {
		PackedCollectionEnumerate enumerate = new PackedCollectionEnumerate(shape, stride, collection);

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
	 *   <li>Converting between different tensor format conventions (e.g., NCHW &lt;-&gt; NHWC)</li>
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
	 * @param <PackedCollection> The type of collection being permuted
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
	default CollectionProducerComputation permute(Producer<?> collection, int... order) {
		if (Algebraic.isZero(collection)) {
			return zeros(shape(collection).permute(order).extentShape());
		}

		return new CollectionPermute(collection, order);
	}

	/**
	 * Pads a collection along specified axes with a uniform depth.
	 * This convenience method applies symmetric padding (same amount on all sides) to selected dimensions.
	 * 
	 * @param axes Array of axis indices to pad (0-based)
	 * @param depth Amount of padding to add on each side of the specified axes
	 * @param collection The input collection to pad
	 * @param <PackedCollection> The type of PackedCollection
	 * @return A CollectionProducerComputation that produces the padded collection
	 * @throws UnsupportedOperationException if the input collection has a non-null traversal order
	 * 
	 * @see #pad(Producer, int...)
	 */
	default CollectionProducerComputation pad(int[] axes, int depth, Producer<?> collection) {
		TraversalPolicy shape = shape(collection);
		if (shape.getOrder() != null) {
			throw new UnsupportedOperationException();
		}

		int[] depths = new int[shape.getDimensions()];
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
	 * PackedCollection input = new PackedCollection(2, 3);
	 * CollectionProducer<?> padded = pad(input, 1, 1); // Results in 4x5 collection
	 * }</pre>
	 * 
	 * @param collection The input collection to pad
	 * @param depths Padding depth for each dimension. depths[i] specifies how much padding
	 *               to add before and after the data in dimension i
	 * @param <PackedCollection> The type of PackedCollection
	 * @return A CollectionProducerComputation that produces the padded collection
	 * 
	 * @see PackedCollectionPad
	 * @see #pad(TraversalPolicy, TraversalPolicy, Producer)
	 */
	default CollectionProducerComputation pad(Producer<?> collection, int... depths) {
		TraversalPolicy shape = shape(collection);

		int[] dims = new int[shape.getDimensions()];
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
	 * @param <PackedCollection> The type of PackedCollection
	 * @return A CollectionProducer that produces the padded collection
	 * 
	 * @see PackedCollectionPad
	 */
	default CollectionProducer pad(TraversalPolicy shape,
																Producer<?> collection,
																int... pos) {
		int traversalAxis = shape.getTraversalAxis();

		if (traversalAxis == shape.getDimensions()) {
			return pad(shape, new TraversalPolicy(true, pos), collection);
		} else {
			return pad(shape.traverseEach(), new TraversalPolicy(true, pos), collection)
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
	 * @param <PackedCollection> The type of PackedCollection  
	 * @return A CollectionProducerComputation that implements the padding operation,
	 *         or a zeros collection if the input is zero
	 * 
	 * @see PackedCollectionPad
	 * @see TraversalPolicy
	 * @see Algebraic#isZero(Object) 
	 */
	default CollectionProducerComputation pad(TraversalPolicy shape,
											  TraversalPolicy position,
											  Producer<?> collection) {
		if (Algebraic.isZero(collection)) {
			return zeros(shape);
		}

		return new PackedCollectionPad(shape, position, collection);
	}

	default CollectionProducerComputation map(
			Producer<?> collection,
			Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return new PackedCollectionMap(collection, mapper);
	}

	default CollectionProducerComputation map(
			TraversalPolicy itemShape, Producer<?> collection,
			Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return new PackedCollectionMap(shape(collection).replace(itemShape), collection, mapper);
	}

	default CollectionProducerComputation reduce(
			Producer<?> collection,
			Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return map(shape(1), collection, mapper);
	}

	default CollectionProducer cumulativeProduct(Producer<PackedCollection> input, boolean pad) {
		return func(shape(input), inputs -> args -> {
			PackedCollection in = inputs[0];
			PackedCollection result = new PackedCollection(in.getShape());

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

	default CollectionProducer randn(TraversalPolicy shape,
									 double mean, double std) {
		return randn(shape, mean, std, null);
	}

	default CollectionProducer randn(TraversalPolicy shape,
									 double mean, double std,
									 java.util.Random source) {
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

	default DefaultTraversableExpressionComputation compute(String name, CollectionExpression expression) {
		return new DefaultTraversableExpressionComputation(name, expression.getShape(), expression);
	}

	default CollectionProducer compute(
			String name, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Producer<PackedCollection>... arguments) {
		return compute(name, DeltaFeatures.MultiTermDeltaStrategy.NONE, expression, arguments);
	}

	default CollectionProducer compute(
			String name, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Function<List<String>, String> description,
			Producer<PackedCollection>... arguments) {
		return compute(name, DeltaFeatures.MultiTermDeltaStrategy.NONE, expression, description, arguments);
	}

	default CollectionProducer compute(
			String name, DeltaFeatures.MultiTermDeltaStrategy deltaStrategy,
			Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Producer<PackedCollection>... arguments) {
		return compute(name, deltaStrategy, expression, null, arguments);
	}

	default CollectionProducer compute(
			String name, DeltaFeatures.MultiTermDeltaStrategy deltaStrategy,
			Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Function<List<String>, String> description, Producer<PackedCollection>... arguments) {
		return compute((shape, args) -> (Producer<PackedCollection>) new DefaultTraversableExpressionComputation(
				name, largestTotalSize(args), deltaStrategy, true, expression.apply(shape),
				args.toArray(Producer[]::new)), description, arguments);
	}

	default <P extends Producer<PackedCollection>> CollectionProducer compute(
				BiFunction<TraversalPolicy, List<Producer<PackedCollection>>, P> processor,
				Function<List<String>, String> description,
				Producer<PackedCollection>... arguments) {
		Producer<PackedCollection> c = alignTraversalAxes(List.of(arguments), processor);

		if (c instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) c).setDescription(description);
		}

		// TODO  This should use outputShape, so that the calculation isn't
		// TODO  implemented in two separate places
		long count = highestCount(List.of(arguments));

		if (c instanceof Shape) {
			Shape<?> s = (Shape<?>) c;

			if (s.getShape().getCountLong() != count) {
				for (int i = 0; i <= s.getShape().getDimensions(); i++) {
					if (s.getShape().traverse(i).getCountLong() == count) {
						return c((Producer<PackedCollection>) s.traverse(i));
					}
				}
			}
		}

		return c(c);
	}

	default <PackedCollection> TraversalPolicy outputShape(Producer<PackedCollection>... producers) {
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

	/**
	 * Performs element-wise addition of two collections.
	 * This is one of the fundamental arithmetic operations for collections,
	 * adding corresponding elements from each input collection.
	 *
	 * @param a the first collection to add
	 * @param b the second collection to add
	 * @return a CollectionProducer that generates the element-wise sum
	 * 
	 *
	 * <pre>{@code
	 * // Add two vectors element-wise
	 * CollectionProducer vec1 = c(1.0, 2.0, 3.0);
	 * CollectionProducer vec2 = c(4.0, 5.0, 6.0);
	 * CollectionProducer sum = add(vec1, vec2);
	 * // Result: Producer that generates [5.0, 7.0, 9.0]
	 * 
	 * // Add a constant to a vector
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer constant = constant(1.0);
	 * CollectionProducer result = add(vector, constant);
	 * // Result: Producer that generates [2.0, 3.0, 4.0]
	 * }</pre>
	 */
	default <A extends PackedCollection, B extends PackedCollection> CollectionProducer add(Producer<A> a, Producer<B> b) {
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
	 * @param <PackedCollection> the type of PackedCollection
	 * @param operands the list of collections to add together
	 * @return a CollectionProducer that generates the element-wise sum
	 * @throws IllegalArgumentException if any operand is null
	 * 
	 * @see SingleConstantComputation
	 *
	 * <pre>{@code
	 * // Add three vectors together
	 * CollectionProducer vec1 = c(1.0, 2.0);
	 * CollectionProducer vec2 = c(3.0, 4.0);
	 * CollectionProducer vec3 = c(5.0, 6.0);
	 * CollectionProducer sum = add(List.of(vec1, vec2, vec3));
	 * // Result: Producer that generates [9.0, 12.0] (1+3+5, 2+4+6)
	 * 
	 * // Add multiple constants (optimized)
	 * List<Producer<?>> constants = List.of(
	 *     constant(1.0), constant(2.0), constant(3.0)
	 * );
	 * CollectionProducer total = add(constants);
	 * // Result: Producer that generates [6.0] (computed at construction time)
	 * }</pre>
	 */
	default CollectionProducer add(List<Producer<?>> operands) {
		if (operands.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException();
		}

		if (operands.stream().allMatch(o -> o instanceof SingleConstantComputation)) {
			double value = operands.stream().mapToDouble(o -> ((SingleConstantComputation) o).getConstantValue()).sum();

			return compute((shape, args) -> new SingleConstantComputation(shape, value),
					args -> String.join(" + ", applyParentheses(args)),
					operands.toArray(new Producer[0]));
		}

		return compute((shape, args) -> {
					Producer[] p = args.stream().filter(Predicate.not(Algebraic::isZero)).toArray(Producer[]::new);

					if (p.length == 0) {
						return zeros(shape);
					} else if (p.length == 1) {
						return c(reshape(shape, p[0]));
					}

					return new CollectionAddComputation(shape, p);
				},
				args -> String.join(" + ", applyParentheses(args)),
				operands.toArray(new Producer[0]));
	}

	/**
	 * Performs element-wise subtraction of two collections.
	 * This operation subtracts corresponding elements of the second collection
	 * from the first collection, equivalent to {@link #add add(a, minus(b))}.
	 *
	 * @param a the collection to subtract from (minuend)
	 * @param b the collection to subtract (subtrahend)
	 * @return a {@link CollectionProducer} that generates the element-wise difference
	 * 
	 *
	 * <pre>{@code
	 * // Subtract two vectors element-wise
	 * CollectionProducer vec1 = c(5.0, 8.0, 12.0);
	 * CollectionProducer vec2 = c(2.0, 3.0, 4.0);
	 * CollectionProducer difference = subtract(vec1, vec2);
	 * // Result: Producer that generates [3.0, 5.0, 8.0] (5-2, 8-3, 12-4)
	 * 
	 * // Subtract a constant from a vector
	 * CollectionProducer vector = c(10.0, 20.0, 30.0);
	 * CollectionProducer constant = constant(5.0);
	 * CollectionProducer result = subtract(vector, constant);
	 * // Result: Producer that generates [5.0, 15.0, 25.0]
	 * }</pre>
	 */
	default CollectionProducer subtract(Producer<PackedCollection> a, Producer<PackedCollection> b) {
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
	 *
	 * @param a the minuend (value to subtract from)
	 * @param b the subtrahend (value to subtract)
	 * @return a {@link CollectionProducerComputation} that performs epsilon-aware subtraction
	 * 
	 * @see EpsilonConstantComputation
	 * @see #equals(Producer, Producer, Producer, Producer)
	 */
	default CollectionProducerComputation subtractIgnoreZero(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape = shape(a);
		int size = shape(b).getSize();

		if (shape.getSize() != size) {
			if (shape.getSize() == 1) {
				return subtractIgnoreZero(a, traverseEach(b));
			} else if (size == 1) {
				return subtractIgnoreZero(traverseEach(a), b);
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
	 *
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 * 
	 *
	 * <pre>{@code
	 * // Multiply two vectors element-wise
	 * CollectionProducer vec1 = c(2.0, 3.0, 4.0);
	 * CollectionProducer vec2 = c(5.0, 6.0, 7.0);
	 * CollectionProducer product = multiply(vec1, vec2);
	 * // Result: Producer that generates [10.0, 18.0, 28.0]
	 * 
	 * // Scale a vector by a constant
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer scale = constant(2.0);
	 * CollectionProducer scaled = multiply(vector, scale);
	 * // Result: Producer that generates [2.0, 4.0, 6.0]
	 * }</pre>
	 */
	default CollectionProducer multiply(
			Producer<PackedCollection> a, Producer<PackedCollection> b) {
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
	 *
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @param shortCircuit optional pre-computed result for optimization
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 * 
	 * @see SingleConstantComputation
	 *
	 * <pre>{@code
	 * // Multiply with potential optimization
	 * CollectionProducer vec1 = c(2.0, 3.0);
	 * CollectionProducer vec2 = c(1.0, 1.0);
	 * 
	 * // Pre-compute result for optimization
	 * Evaluable<PackedCollection> precomputed = () -> pack(2.0, 3.0);
	 * CollectionProducer result = multiply(vec1, vec2, precomputed);
	 * // May use precomputed result if beneficial
	 * 
	 * // Constant multiplication (optimized)
	 * CollectionProducer constant1 = constant(2.0);
	 * CollectionProducer constant2 = constant(3.0);
	 * CollectionProducer product = multiply(constant1, constant2);
	 * // Result: constant(6.0) computed directly without full pipeline
	 * }</pre>
	 */
	default CollectionProducer multiply(
			Producer<PackedCollection> a, Producer<PackedCollection> b,
			Evaluable<PackedCollection> shortCircuit) {
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
				CollectionProducer result = multiply(((SingleConstantComputation) a).getConstantValue(), b);
				if (result != null) return withShortCircuit(result, shortCircuit);
			}

			if (b instanceof SingleConstantComputation) {
				CollectionProducer result = multiply(((SingleConstantComputation) b).getConstantValue(), a);
				if (result != null) return withShortCircuit(result, shortCircuit);
			}
		}

		return withShortCircuit(compute((shape, args) -> {
					if (args.stream().anyMatch(Algebraic::isZero)) {
						// Mathematical optimization: anything * 0 = 0
						// Returns CollectionZerosComputation to avoid unnecessary computation
						return zeros(shape);
					}

					return (Producer<PackedCollection>) new CollectionProductComputation(shape, args.toArray(new Producer[0]));
				},
				args -> String.join(" * ", applyParentheses(args)), a, b), shortCircuit);
	}

	/**
	 * Multiplies a collection by a scalar value.
	 * This is an optimized operation for scaling all elements of a collection
	 * by the same constant factor.
	 *
	 * @param scale the scalar value to multiply by
	 * @param a the collection to scale
	 * @return a {@link CollectionProducer} that generates the scaled collection, or null if no optimization available
	 * 
	 *
	 * <pre>{@code
	 * // Scale a vector by 2
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer doubled = multiply(2.0, vector);
	 * // Result: Producer that generates [2.0, 4.0, 6.0]
	 * 
	 * // Scale by zero to create zero vector
	 * CollectionProducer zeros = multiply(0.0, vector);
	 * // Result: Producer that generates [0.0, 0.0, 0.0]
	 * 
	 * // Scale by -1 to negate
	 * CollectionProducer negated = multiply(-1.0, vector);
	 * // Result: Producer that generates [-1.0, -2.0, -3.0]
	 * }</pre>
	 */
	default CollectionProducer multiply(double scale, Producer<PackedCollection> a) {
		if (scale == 0) {
			// Mathematical optimization: 0 * anything = 0
			// Returns CollectionZerosComputation with same shape as input
			return zeros(shape(a));
		} else if (scale == 1.0) {
			return c(a);
		} else if (scale == -1.0) {
			return minus(a);
		} else if (a instanceof ArithmeticSequenceComputation) {
			return ((ArithmeticSequenceComputation) a).multiply(scale);
		} else if (a.isConstant()) {
			return multiply(shape(a), scale, a.get());
		} else {
			return null;
		}
	}

	default CollectionProducer multiply(TraversalPolicy shape, double scale, Evaluable<PackedCollection> a) {
		return c(shape, a.evaluate().doubleStream().parallel().map(d -> d * scale).toArray());
	}

	/**
	 * Performs element-wise division of two collections.
	 * This operation divides corresponding elements of the first collection
	 * by the corresponding elements of the second collection.
	 *
	 * @param a the dividend collection (numerator)
	 * @param b the divisor collection (denominator)
	 * @return a {@link CollectionProducer} that generates the element-wise quotient
	 * @throws UnsupportedOperationException if attempting to divide by zero
	 * 
	 *
	 * <pre>{@code
	 * // Divide two vectors element-wise
	 * CollectionProducer numerator = c(12.0, 15.0, 20.0);
	 * CollectionProducer denominator = c(3.0, 5.0, 4.0);
	 * CollectionProducer quotient = divide(numerator, denominator);
	 * // Result: Producer that generates [4.0, 3.0, 5.0] (12/3, 15/5, 20/4)
	 * 
	 * // Divide by a constant (scalar division)
	 * CollectionProducer vector = c(10.0, 20.0, 30.0);
	 * CollectionProducer divisor = constant(2.0);
	 * CollectionProducer halved = divide(vector, divisor);
	 * // Result: Producer that generates [5.0, 10.0, 15.0]
	 * }</pre>
	 */
	default CollectionProducer divide(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		if (Algebraic.isZero(b)) {
			throw new UnsupportedOperationException();
		} else if (Algebraic.isZero(a)) {
			// Mathematical optimization: 0 / anything = 0
			// Returns CollectionZerosComputation for efficiency
			return zeros(outputShape(a, b));
		}

		CollectionProducer p = compute("divide",
				shape -> (args) ->
						quotient(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				(List<String> args) -> String.join(" / ", applyParentheses(args)), a, b);

		return CollectionProducerComputationBase.assignDeltaAlternate(
				p, multiply(a, pow(b, c(-1.0))));
	}

	/**
	 * Negates all elements in a collection (unary minus operation).
	 * This operation multiplies every element by -1, effectively flipping
	 * the sign of all values in the collection.
	 *
	 *
	 * @param a   the collection to negate
	 * @return a {@link CollectionProducerComputationBase} that generates the negated collection
	 *
	 *
	 * <pre>{@code
	 * // Negate a vector
	 * CollectionProducer vector = c(1.0, -2.0, 3.0, -4.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negated = minus(vector);
	 * // Result: Producer that generates [-1.0, 2.0, -3.0, 4.0]
	 *
	 * // Negate a constant (optimized case)
	 * CollectionProducer constant = constant(5.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negatedConstant = minus(constant);
	 * // Result: Producer that generates [-5.0]
	 *
	 * // Negate a matrix
	 * CollectionProducer matrix = c(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negatedMatrix = minus(matrix);
	 * // Result: Producer that generates 2x2 matrix [[-1,-2], [-3,-4]]
	 * }</pre>
	 */
	default CollectionProducer minus(Producer<PackedCollection> a) {
		TraversalPolicy shape = shape(a);
		int w = shape.length(0);

		if (shape.getTotalSizeLong() == 1 && a.isConstant() && Countable.isFixedCount(a)) {
			return new AtomicConstantComputation(-a.get().evaluate().toDouble());
		} else if (Algebraic.isIdentity(w, a)) {
			return new ScalarMatrixComputation(shape, c(-1))
				.setDescription(args -> "-" + DescribableParent.description(a));
		}

		return new CollectionMinusComputation(shape, a)
				.setDescription(args -> "-" + args.get(0));
	}

	/**
	 * Computes the square root of each element in a collection.
	 * This is a convenience method that raises each element to the power of 0.5,
	 * providing a more readable way to compute square roots.
	 *
	 * @param value the collection containing values to compute square roots for
	 * @return a {@link CollectionProducer} that generates the element-wise square roots
	 * 
	 *
	 * <pre>{@code
	 * // Compute square roots of elements
	 * CollectionProducer values = c(4.0, 9.0, 16.0, 25.0);
	 * CollectionProducer roots = sqrt(values);
	 * // Result: Producer that generates [2.0, 3.0, 4.0, 5.0]
	 * 
	 * // Square root of a single value
	 * CollectionProducer number = c(64.0);
	 * CollectionProducer root = sqrt(number);
	 * // Result: Producer that generates [8.0]
	 * 
	 * // Square root in mathematical expressions
	 * CollectionProducer squares = c(1.0, 4.0, 9.0);
	 * CollectionProducer magnitude = sqrt(sum(squares));
	 * // Result: sqrt(1+4+9) = sqrt(14) ~= 3.74
	 * }</pre>
	 */
	default CollectionProducer sqrt(Producer<PackedCollection> value) {
		PackedCollection half = new PackedCollection(1);
		half.setMem(0.5);
		return pow(value, c(half));
	}

	/**
	 * Raises elements of the base collection to the power of corresponding elements in the exponent collection.
	 * This operation performs element-wise exponentiation, computing base[i]^exp[i] for each element.
	 *
	 * @param base the base collection (values to be raised to powers)
	 * @param exp the exponent collection (power values)
	 * @return a {@link CollectionProducer} that generates the element-wise power results
	 * 
	 *
	 * <pre>{@code
	 * // Raise elements to specified powers
	 * CollectionProducer base = c(2.0, 3.0, 4.0);
	 * CollectionProducer exponent = c(2.0, 3.0, 0.5);
	 * CollectionProducer powers = pow(base, exponent);
	 * // Result: Producer that generates [4.0, 27.0, 2.0] (2^2, 3^3, 4^0.5)
	 * 
	 * // Square all elements (power of 2)
	 * CollectionProducer values = c(1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer two = constant(2.0);
	 * CollectionProducer squares = pow(values, two);
	 * // Result: Producer that generates [1.0, 4.0, 9.0, 16.0]
	 * 
	 * // Square root (power of 0.5)
	 * CollectionProducer numbers = c(4.0, 9.0, 16.0, 25.0);
	 * CollectionProducer half = constant(0.5);
	 * CollectionProducer roots = pow(numbers, half);
	 * // Result: Producer that generates [2.0, 3.0, 4.0, 5.0]
	 * }</pre>
	 */
	default CollectionProducer pow(Producer<PackedCollection> base, Producer<PackedCollection> exp) {
		if (Algebraic.isIdentity(1, base)) {
			TraversalPolicy shape = shape(exp);

			if (shape.getTotalSizeLong() == 1) {
				return (CollectionProducer) base;
			} else {
				return repeat(shape.getTotalSize(), base).reshape(shape);
			}
		} else if (base.isConstant() && exp.isConstant()) {
			if (shape(base).getTotalSizeLong() == 1 && shape(exp).getTotalSizeLong() == 1) {
				return c(Math.pow(base.get().evaluate().toDouble(), exp.get().evaluate().toDouble()));
			}

			console.warn("Computing power of constants");
		}

		// When neither operand is constant or identity, delegate to CollectionExponentComputation
		// for efficient element-wise power operations with support for automatic differentiation
		return compute((shape, args) ->
						new CollectionExponentComputation(largestTotalSize(args), args.get(0), args.get(1)),
				args -> applyParentheses(args.get(0)) + " ^ " + applyParentheses(args.get(1)),
				base, exp);
	}

	default CollectionProducer exp(Producer<PackedCollection> value) {
		return new CollectionExponentialComputation(shape(value), false, value);
	}

	default CollectionProducer expIgnoreZero(Producer<PackedCollection> value) {
		return new CollectionExponentialComputation(shape(value), true, value);
	}

	default CollectionProducer log(Producer<PackedCollection> value) {
		return new CollectionLogarithmComputation(shape(value), value);
	}

	default CollectionProducer sq(Producer<PackedCollection> value) {
		return multiply(value, value);
	}

	default CollectionProducerComputationBase floor(Producer<PackedCollection> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation(
				"floor", shape,
				args -> new UniformCollectionExpression("floor", shape, in -> Floor.of(in[0]), args[1]),
				value);
	}

	default CollectionProducerComputationBase min(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("min", shape,
				args -> new UniformCollectionExpression("min", shape,
								in -> Min.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	default CollectionProducerComputationBase max(
			Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("max", shape,
				args -> new UniformCollectionExpression("max", shape,
								in -> Max.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	default CollectionProducer rectify(Producer<PackedCollection> a) {
		// TODO  Add short-circuit
		return compute("rectify", shape -> args ->
						rectify(shape, args[1]), a);
	}

	default CollectionProducer mod(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		// TODO  Add short-circuit
		return compute("mod", shape -> args ->
						mod(shape, args[1], args[2]), a, b);
	}

	default CollectionProducerComputationBase bound(Producer<PackedCollection> a, double min, double max) {
		return min(max(a, c(min)), c(max));
	}

	/**
	 * Computes the absolute value of each element in a collection.
	 * This operation converts all negative values to positive while
	 * leaving positive values unchanged.
	 *
	 * @param value the collection containing values to compute absolute values for
	 * @return a {@link CollectionProducer} that generates the element-wise absolute values
	 * 
	 *
	 * <pre>{@code
	 * // Compute absolute values
	 * CollectionProducer values = c(-3.0, -1.0, 0.0, 2.0, -5.0);
	 * CollectionProducer absolutes = abs(values);
	 * // Result: Producer that generates [3.0, 1.0, 0.0, 2.0, 5.0]
	 * 
	 * // Absolute value of differences
	 * CollectionProducer a = c(10.0, 5.0, 8.0);
	 * CollectionProducer b = c(7.0, 9.0, 3.0);
	 * CollectionProducer distance = abs(subtract(a, b));
	 * // Result: Producer that generates [3.0, 4.0, 5.0] (absolute differences)
	 * }</pre>
	 */
	default CollectionProducer abs(Producer<PackedCollection> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation(
				"abs", shape, DeltaFeatures.MultiTermDeltaStrategy.NONE, true,
				args -> new UniformCollectionExpression("abs", shape, in -> new Absolute(in[0]), args[1]),
				value);
	}

	default CollectionProducer magnitude(Producer<PackedCollection> vector) {
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
	 * @param input the collection to find the maximum element in
	 * @return a {@link CollectionProducerComputationBase} that generates the maximum value
	 * 
	 *
	 * <pre>{@code
	 * // Find maximum in a vector
	 * CollectionProducer values = c(3.0, 7.0, 2.0, 9.0, 5.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> maximum = max(values);
	 * // Result: Producer that generates [9.0]
	 * 
	 * // Find maximum in a matrix (flattened)
	 * CollectionProducer matrix = c(shape(2, 3), 1.0, 8.0, 3.0, 4.0, 2.0, 6.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> matrixMax = max(matrix);
	 * // Result: Producer that generates [8.0] (maximum across all elements)
	 * 
	 * // Maximum of negative numbers
	 * CollectionProducer negatives = c(-5.0, -2.0, -8.0, -1.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negMax = max(negatives);
	 * // Result: Producer that generates [-1.0] (least negative = maximum)
	 * }</pre>
	 */
	default CollectionProducerComputationBase max(Producer<PackedCollection> input) {
		DynamicIndexProjectionProducerComputation projection =
				new DynamicIndexProjectionProducerComputation("projectMax", shape(input).replace(shape(1)),
						(args, idx) -> args[2].getValueAt(idx).toInt(),
						true, input, indexOfMax(input));

		CollectionMaxComputation c = new CollectionMaxComputation(input);
		if (enableIndexProjectionDeltaAlt) c.setDeltaAlternate(projection);
		return c;
	}

	/**
	 * Creates a computation that finds the index of the maximum value in a collection.
	 * <p>
	 * This method uses {@link TraversableRepeatedProducerComputation} to identify the index.
	 * 
	 * <p>The computation works by:
	 * <ul>
	 *   <li>Initializing with index 0 as the current maximum location</li>
	 *   <li>Iterating through all elements comparing values</li>
	 *   <li>Updating the stored index when a larger value is found</li>
	 * </ul>
	 * 
	 * @param input The collection to find the maximum index in
	 *
	 * @return A computation that produces the index of the maximum element
	 */
	default CollectionProducerComputationBase indexOfMax(Producer<PackedCollection> input) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		return new TraversableRepeatedProducerComputation("indexOfMax", shape.replace(shape(1)), size,
				(args, index) -> e(0),
				(args, currentIndex) -> index ->
						conditional(args[1].getValueAt(kernel().multiply(size).add(index))
										.greaterThan(args[1].getValueAt(kernel().multiply(size).add(currentIndex))),
								index, currentIndex),
				input);
	}

	/**
	 * Computes the sum of all elements in a collection.
	 * This reduction operation adds up all elements to produce a single scalar result.
	 * It's one of the most common aggregation operations in numerical computing.
	 * 
	 * @param input the collection to sum
	 * @return a {@link CollectionProducerComputation} that generates a single-element collection containing the sum
	 * 
	 *
	 * <pre>{@code
	 * // Sum all elements in a vector
	 * CollectionProducer vector = c(1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer total = sum(vector);
	 * // Result: Producer that generates [10.0] (1+2+3+4)
	 * 
	 * // Sum elements in a matrix (flattened)
	 * CollectionProducer matrix = c(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer matrixSum = sum(matrix);
	 * // Result: Producer that generates [10.0] (1+2+3+4)
	 * 
	 * // Sum of zeros returns zero
	 * CollectionProducer zeros = zeros(shape(5));
	 * CollectionProducer zeroSum = sum(zeros);
	 * // Result: Producer that generates [0.0]
	 * }</pre>
	 */
	default CollectionProducer sum(Producer<PackedCollection> input) {
		TraversalPolicy targetShape = shape(input).replace(shape(1));

		if (Algebraic.isZero(input)) {
			// Mathematical optimization: sum(zeros) = 0
			return zeros(targetShape);
		}

		CollectionProducer result = null;

		boolean isWeightedSum = input instanceof WeightedSumComputation ||
				(input instanceof ReshapeProducer &&
						((ReshapeProducer) input).getComputation() instanceof WeightedSumComputation);
		boolean isAggregated = input instanceof AggregatedProducerComputation ||
				(input instanceof ReshapeProducer &&
						((ReshapeProducer) input).getComputation() instanceof AggregatedProducerComputation);

		if (enableSubdivide && shape(input).getSize() > KernelPreferences.getWorkSubdivisionMinimum()) {
			CollectionProducer sum = subdivide(input, this::sum);

			if (sum != null) {
				if (!shape(sum).equals(targetShape)) {
					result = sum.reshape(targetShape);
				} else {
					result = sum;
				}
			}
		}

		boolean tryUnaryWeighted = enableUnaryWeightedSum && !isWeightedSum && !isAggregated;

		if (result == null && tryUnaryWeighted &&
				shape(input).getSize() <= KernelPreferences.getWorkSubdivisionMinimum()) {
			TraversalPolicy shape = shape(input);

			TraversalPolicy resultShape = shape.replace(new TraversalPolicy(1));
			TraversalPolicy positions = padDimensions(resultShape, 1, shape.getDimensions(), true);
			TraversalPolicy groupShape = padDimensions(shape.item(), shape.getDimensions());
			result = new WeightedSumComputation(
					positions, positions, positions,
					groupShape, groupShape,
					input, c(1.0).reshape(shape)).reshape(resultShape);
		}

		if (result == null) {
			return new CollectionSumComputation(input);
		} else {
			return CollectionProducerComputationBase.assignDeltaAlternate(
					result, new CollectionSumComputation(input));
		}
	}

	/**
	 * Computes the arithmetic mean (average) of all elements in a collection.
	 * This is calculated as the sum of all elements divided by the number of elements.
	 * 
	 *
	 * @param input the collection to compute the mean for
	 * @return a {@link CollectionProducer} that generates a single-element collection containing the mean
	 * 
	 *
	 * <pre>{@code
	 * // Calculate mean of a vector
	 * CollectionProducer vector = c(2.0, 4.0, 6.0, 8.0);
	 * CollectionProducer average = mean(vector);
	 * // Result: Producer that generates [5.0] ((2+4+6+8)/4)
	 * 
	 * // Mean of a single element
	 * CollectionProducer single = c(42.0);
	 * CollectionProducer singleMean = mean(single);
	 * // Result: Producer that generates [42.0] (42/1)
	 * 
	 * // Mean of mixed positive/negative values
	 * CollectionProducer mixed = c(-2.0, 0.0, 2.0);
	 * CollectionProducer mixedMean = mean(mixed);
	 * // Result: Producer that generates [0.0] ((-2+0+2)/3)
	 * }</pre>
	 */
	default CollectionProducer mean(Producer<PackedCollection> input) {
		return sum(input).divide(c(shape(input).getSize()));
	}

	default CollectionProducer subtractMean(Producer<PackedCollection> input) {
		CollectionProducer mean = mean(input);
		return subtract(input, mean);
	}

	default CollectionProducer variance(Producer<PackedCollection> input) {
		return mean(sq(subtractMean(input)));
	}

	default CollectionProducer sigmoid(Producer<PackedCollection> input) {
		return divide(c(1.0), minus(input).exp().add(c(1.0)));
	}

	/**
	 * Performs element-wise equality comparison between two collections with custom return values.
	 * This method compares corresponding elements and returns specified values based on the comparison result.
	 * 
	 * to produce
	 * @param a the first collection to compare
	 * @param b the second collection to compare  
	 * @param trueValue the value to return when elements are equal
	 * @param falseValue the value to return when elements are not equal
	 * @return a {@link CollectionProducer} that generates comparison results
	 * 
	 * @see org.almostrealism.collect.computations.CollectionComparisonComputation
	 */
	default CollectionProducer equals(Producer<PackedCollection> a, Producer<PackedCollection> b,
																   Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return compute((shape, args) ->
						new CollectionComparisonComputation("equals", shape,
								args.get(0), args.get(1), args.get(2), args.get(3)),
				null,
				a, b, trueValue, falseValue);
	}

	/**
	 * Performs element-wise greater-than comparison between two collections with custom return values.
	 * This method compares corresponding elements and returns specified values based on the comparison result.
	 *
	 * to produce
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @param trueValue the value to return when the first element is greater than the second
	 * @param falseValue the value to return when the first element is not greater than the second
	 * @return a {@link CollectionProducer} that generates comparison results
	 *
	 * @see org.almostrealism.collect.computations.CollectionComparisonComputation
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
																		Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return greaterThan(a, b, trueValue, falseValue, false);
	}

	/**
	 * Performs element-wise greater-than comparison between two collections with custom return values.
	 * This method compares corresponding elements and returns specified values based on the comparison result.
	 *
	 * to produce
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @param trueValue the value to return when the first element is greater than the second
	 * @param falseValue the value to return when the first element is not greater than the second
	 * @param includeEqual whether to treat elements which are equal as meeting the comparison condition
	 * @return a {@link CollectionProducer} that generates comparison results
	 *
	 * @see org.almostrealism.collect.computations.CollectionComparisonComputation
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
																		Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
																		boolean includeEqual) {
		return compute((shape, args) ->
						new GreaterThanCollection(shape,
								args.get(0), args.get(1), args.get(2), args.get(3), includeEqual),
				null,
				a, b, trueValue, falseValue);
	}

	default CollectionProducer greaterThanConditional(Producer<PackedCollection> a, Producer<PackedCollection> b,
																				   Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return greaterThanConditional(a, b, trueValue, falseValue, false);
	}

	default CollectionProducer greaterThanConditional(Producer<PackedCollection> a, Producer<PackedCollection> b,
																				   Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
																				   boolean includeEqual) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("greaterThan", shape,
				args -> new ComparisonExpression("greaterThan", shape,
						(l, r) -> greater(l, r, includeEqual),
						args[1], args[2], args[3], args[4]),
				a, b, trueValue, falseValue);
	}

	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
																	 Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return lessThan(a, b, trueValue, falseValue, false);
	}

	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
																	 Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
																	 boolean includeEqual) {
		return compute((shape, args) ->
						new LessThanCollection(shape,
								args.get(0), args.get(1), args.get(2), args.get(3), includeEqual),
				null,
				a, b, trueValue, falseValue);
	}

	/**
	 * Performs element-wise greater-than comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a > b, 0.0 otherwise
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return greaterThan(a, b, c(1.0), c(0.0));
	}

	/**
	 * Performs element-wise greater-than-or-equal comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a >= b, 0.0 otherwise
	 */
	default CollectionProducer greaterThanOrEqual(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return greaterThan(a, b, c(1.0), c(0.0), true);
	}

	/**
	 * Performs element-wise less-than comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a &lt; b, 0.0 otherwise
	 */
	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return lessThan(a, b, c(1.0), c(0.0), false);
	}

	/**
	 * Performs element-wise less-than-or-equal comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a &lt;= b, 0.0 otherwise
	 */
	default CollectionProducer lessThanOrEqual(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return lessThan(a, b, c(1.0), c(0.0), true);
	}

	/**
	 * Performs element-wise logical AND operation on two collections with custom return values.
	 * Returns trueValue if both operands are non-zero (considered true), otherwise returns falseValue.
	 * This is useful for combining multiple conditions with custom result values.
	 *
	 * to produce
	 * @param a the first operand (non-zero = true)
	 * @param b the second operand (non-zero = true)
	 * @param trueValue the value to return when both a AND b are non-zero
	 * @param falseValue the value to return otherwise
	 * @return a {@link CollectionProducer} that generates the logical AND result
	 */
	default CollectionProducer and(
			Producer<PackedCollection> a, Producer<PackedCollection> b,
			Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return compute((shape, args) ->
						new CollectionConjunctionComputation(shape,
								args.get(0), args.get(1), args.get(2), args.get(3)),
				null,
				a, b, trueValue, falseValue);
	}

	/**
	 * Performs element-wise logical AND operation on two collections, returning 1.0 for true
	 * and 0.0 for false. Returns 1.0 if both operands are non-zero, otherwise returns 0.0.
	 * This is useful for chaining multiple conditions.
	 *
	 * @param a the first operand (non-zero = true)
	 * @param b the second operand (non-zero = true)
	 * @return a {@link CollectionProducer} that generates 1.0 where both operands are non-zero, 0.0 otherwise
	 */
	default CollectionProducer and(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return and(a, b, c(1.0), c(0.0));
	}

	default CollectionProducer delta(Producer<PackedCollection> producer, Producer<?> target) {
		CollectionProducer result = MatrixFeatures.getInstance().attemptDelta(producer, target);
		if (result != null) return result;

		return c(producer).delta(target);
	}

	default CollectionProducer combineGradient(
			CollectionProducer func,
			Producer<PackedCollection> input, Producer<PackedCollection> gradient) {
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

	default CollectionProducer multiplyGradient(
			CollectionProducer p, Producer<PackedCollection> gradient, int inSize) {
		int outSize = shape(gradient).getTotalSize();
		return p.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize));
	}

	default CollectionProducer subdivide(
			Producer<PackedCollection> input, Function<Producer<PackedCollection>, CollectionProducer> operation) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		int split = KernelPreferences.getWorkSubdivisionUnit();

		if (size > split) {
			while (split > 1) {
				CollectionProducer slice = subdivide(input, operation, split);
				if (slice != null) return slice;
				split /= 2;
			}
		}

		return null;
	}

	default CollectionProducer subdivide(
			Producer<PackedCollection> input, Function<Producer<PackedCollection>, CollectionProducer> operation, int sliceSize) {
		TraversalPolicy shape = shape(input);
		int size = shape.getSize();

		if (size % sliceSize == 0) {
			TraversalPolicy split = shape.replace(shape(sliceSize, size / sliceSize)).traverse();
			CollectionProducer inner = operation.apply((Producer<PackedCollection>) reshape(split, input)).consolidate();
			return operation.apply(inner);
		}

		return null;
	}

	default <P extends Producer<PackedCollection>> P withShortCircuit(P producer, Evaluable<PackedCollection> shortCircuit) {
		if (producer instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) producer).setShortCircuit(shortCircuit);
		}

		return producer;
	}

	default CollectionProducer withShortCircuit(CollectionProducer producer, Evaluable<PackedCollection> shortCircuit) {
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
		if (p instanceof CollectionProducerComputation || p instanceof CollectionProviderProducer) {
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
