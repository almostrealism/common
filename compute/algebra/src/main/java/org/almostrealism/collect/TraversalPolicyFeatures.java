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

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryDataComputation;

import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Factory interface for creating and manipulating {@link TraversalPolicy} shapes.
 * This interface extends {@link ShapeFeatures} and provides the primary documented
 * API for shape creation and dimension manipulation in the Almost Realism framework.
 *
 * <p>Methods here cover:</p>
 * <ul>
 *   <li>Creating {@link TraversalPolicy} instances from integer or long dimensions</li>
 *   <li>Creating position policies</li>
 *   <li>Extracting shapes from {@link Supplier} or {@link TraversableExpression} objects</li>
 *   <li>Querying element counts via {@code size()}</li>
 *   <li>Padding shapes to a target number of dimensions</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see ShapeFeatures
 * @see TraversalPolicy
 * @see CollectionFeatures
 */
public interface TraversalPolicyFeatures extends ShapeFeatures {

	/**
	 * Creates a new {@link TraversalPolicy} with the specified dimensions.
	 * This is one of the most fundamental methods for creating shapes that define
	 * how data is organized and accessed in collections.
	 *
	 * @param dims the dimensions of the shape (e.g., width, height, depth)
	 * @return a new {@link TraversalPolicy} representing the specified shape
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
	@Override
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
	@Override
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
	@Override
	default TraversalPolicy position(int... dims) { return new TraversalPolicy(true, dims); }

	/**
	 * Extracts the {@link TraversalPolicy} shape from a {@link Supplier}.
	 * This method is useful for determining the shape of collections at runtime
	 * by examining the supplier object.
	 *
	 * @param s the supplier to extract shape from
	 * @return the {@link TraversalPolicy} representing the supplier's shape,
	 *         or {@link #shape(int...)} with a single element if no shape is available
	 *
	 * <pre>{@code
	 * // Extract shape from a CollectionProducer created with c()
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
	 * // Extract shape from reshaped CollectionProducer
	 * CollectionProducer reshaped = vector.reshape(shape(1, 3));
	 * TraversalPolicy reshapedShape = shape(reshaped);
	 * // Result: shape with dimensions [1, 3]
	 * }</pre>
	 */
	@Override
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
	 * @return the {@link TraversalPolicy} representing the expression's shape,
	 *         or {@link #shape(int...)} with a single element if no shape is available
	 *
	 * <pre>{@code
	 * // Create an expression with known shape
	 * TraversableExpression expr = new PackedCollectionMap(shape(2, 3), someProducer, mapper);
	 * TraversalPolicy extractedShape = shape(expr);
	 * // Result: shape with dimensions [2, 3]
	 * }</pre>
	 */
	@Override
	default TraversalPolicy shape(TraversableExpression t) {
		if (t instanceof Shape) {
			return ((Shape) t).getShape();
		} else {
			if (enableShapelessWarning) {
				console.warn(t.getClass() + " does not have a Shape");
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
	 * <pre>{@code
	 * // Get size of a CollectionProducer created with c()
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
	@Override
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
	 * <pre>{@code
	 * // Get size of a shape object
	 * Shape<?> collection = new PackedCollection(shape(2, 3, 4));
	 * int totalElements = size(collection);
	 * // Result: 24 (2 * 3 * 4 elements)
	 * }</pre>
	 */
	@Override
	default int size(Shape s) {
		return s.getShape().getSize();
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions of length 1.
	 * This utility method adds dimensions to a shape until it reaches the target
	 * number of dimensions, useful for making shapes compatible for operations.
	 *
	 * @param shape the original shape to pad
	 * @param target the desired number of dimensions
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
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
	@Override
	default TraversalPolicy padDimensions(TraversalPolicy shape, int target) {
		return padDimensions(shape, 1, target);
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, but only if it has
	 * at least {@code min} dimensions. This overload provides more control by specifying a
	 * minimum number of dimensions that must be present before padding occurs.
	 *
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @return a new {@link TraversalPolicy} with the target number of dimensions (if min is met)
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
	@Override
	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target) {
		return padDimensions(shape, min, target, false);
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, with control over
	 * padding direction. This is the most flexible padding method, allowing you to specify
	 * whether padding dimensions should be added at the beginning (false) or end (true) of the shape.
	 *
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @param post whether to append dimensions at the end (true) or prepend at the beginning (false)
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
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
	@Override
	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target, boolean post) {
		if (shape.getDimensions() < min) {
			return shape;
		}

		while (shape.getDimensions() < target) {
			shape = post ? shape.appendDimension(1) : shape.prependDimension(1);
		}

		return shape;
	}
}
