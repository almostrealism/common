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

import io.almostrealism.collect.Algebraic;
import org.almostrealism.collect.CollectionFeatures;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.CollectionPermute;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionPad;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.collect.computations.SingleConstantComputation;


/**
 * Factory interface for slicing, subsetting, and transformation operations on collections.
 * This interface provides methods for extracting subsets, repeating, enumerating,
 * permuting, padding, and mapping collections.
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author Michael Murray
 * @see CollectionFeatures
 * @see TraversalPolicy
 */
public interface SlicingFeatures extends CollectionCreationFeatures {

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
	default CollectionProducerComputation enumerate(TraversalPolicy shape, Producer<?> collection) {
		PackedCollectionEnumerate enumerate = new PackedCollectionEnumerate(shape, collection);

		if (Algebraic.isZero(enumerate)) {
			return zeros(enumerate.getShape());
		}

		return enumerate;
	}

	/**
	 * Creates an enumeration using explicit {@link TraversalPolicy} shapes for both subset and stride patterns.
	 *
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param stride the {@link TraversalPolicy} defining the stride pattern
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 */
	default CollectionProducerComputation enumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
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
	 * @return A CollectionProducer that produces the padded collection
	 * 
	 * @see PackedCollectionPad
	 */
	default CollectionProducer pad(TraversalPolicy shape, Producer<?> collection, int... pos) {
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
	 * @return A CollectionProducerComputation that implements the padding operation,
	 *         or a zeros collection if the input is zero
	 * 
	 * @see PackedCollectionPad
	 * @see TraversalPolicy
	 * @see Algebraic#isZero(Object) 
	 */
	default CollectionProducerComputation pad(TraversalPolicy shape, TraversalPolicy position, Producer<?> collection) {
		if (Algebraic.isZero(collection)) {
			return zeros(shape);
		}

		return new PackedCollectionPad(shape, position, collection);
	}

	/**
	 * Computes the cumulative product of the elements of the input collection.
	 * If {@code pad} is true, the result is shifted by one position with 1.0 prepended
	 * (making element i the product of input elements 0 through i-1).
	 *
	 * @param input the input collection
	 * @param pad   if true, prepend 1.0 and compute the exclusive cumulative product
	 * @return a producer for the cumulative product collection
	 */
	default CollectionProducer cumulativeProduct(Producer<PackedCollection> input, boolean pad) {
		return func(shape(input), inputs -> args -> {
			PackedCollection in = inputs[0];
			PackedCollection result = new PackedCollection(in.getShape());

			double r = 1.0;
			int offset = 0;

			if (pad) {
				CollectionFeatures.getInstance().a(CollectionFeatures.getInstance().cp(result.range(new TraversalPolicy(1), 0)), CollectionFeatures.getInstance().c(r)).get().run();
				offset = 1;
			}

			for (int i = offset; i < in.getMemLength(); i++) {
				r *= in.toDouble(i - offset);
				CollectionFeatures.getInstance().a(CollectionFeatures.getInstance().cp(result.range(new TraversalPolicy(1), i)), CollectionFeatures.getInstance().c(r)).get().run();
			}

			return result;
		}, input);
	}
}
