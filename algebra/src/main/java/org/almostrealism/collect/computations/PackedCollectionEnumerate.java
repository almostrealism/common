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

package org.almostrealism.collect.computations;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.compute.ParallelismTargetOptimization;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;
import java.util.stream.IntStream;

/**
 * A computation that creates enumerated subsequences from a {@link PackedCollection}.
 * This class implements sliding window operations, patch extraction, and strided 
 * enumeration patterns commonly used in machine learning and signal processing.
 * 
 * <p>The enumeration operation extracts overlapping or non-overlapping windows from
 * the input collection along specified dimensions, creating a new output collection
 * with an additional dimension representing the enumerated sequences.</p>
 * 
 * <h3>Shape Transformation</h3>
 * <p>For an input collection with shape {@code [d1, d2, ..., dn]} and enumeration
 * along axis {@code k} with subset shape {@code [s1, s2, ..., sm]} and stride 
 * {@code [t1, t2, ..., tm]}, the output shape becomes:</p>
 * <pre>
 * [d1, ..., d(k-1), count, d(k+1), ..., dn, s1, s2, ..., sm]
 * </pre>
 * <p>where {@code count} is determined by how many times the subset pattern fits
 * within the input dimension with the given stride.</p>
 * 
 * <h3>Common Use Cases</h3>
 * <ul>
 * <li><strong>Sliding Windows:</strong> Extract sequential patterns from time series data</li>
 * <li><strong>Image Patches:</strong> Extract 2D patches for convolution operations</li>
 * <li><strong>Stride Operations:</strong> Down-sampling with configurable step sizes</li>
 * <li><strong>Attention Patterns:</strong> Create attention windows for transformer models</li>
 * </ul>
 * 
 * @param <T> the type of {@link PackedCollection} being enumerated
 * 
 * @example
 * <p><strong>Basic 1D Sliding Window:</strong></p>
 * <pre>{@code
 * // Input: [1, 2, 3, 4, 5, 6] (shape: [6])
 * // enumerate(shape(3), stride(1), input)
 * // Output: [[1,2,3], [2,3,4], [3,4,5], [4,5,6]] (shape: [4, 3])
 * }</pre>
 * 
 * @example
 * <p><strong>2D Patch Extraction:</strong></p>
 * <pre>{@code
 * // Input: 4x4 matrix (shape: [4, 4])
 * // enumerate(shape(2, 2), stride(1, 1), input)
 * // Output: 3x3x2x2 tensor (9 patches of size 2x2)
 * }</pre>
 * 
 * @example
 * <p><strong>Strided Enumeration:</strong></p>
 * <pre>{@code
 * // Input: [1, 2, 3, 4, 5, 6, 7, 8] (shape: [8])
 * // enumerate(shape(2), stride(2), input) 
 * // Output: [[1,2], [3,4], [5,6], [7,8]] (shape: [4, 2])
 * }</pre>
 * 
 * @see org.almostrealism.collect.CollectionFeatures#enumerate(int, int, io.almostrealism.relation.Producer)
 * @see io.almostrealism.collect.TraversalPolicy
 */
public class PackedCollectionEnumerate<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	/** Enable optimization for preferring isolation in parallel processing */
	public static boolean enablePreferIsolation = false;
	/** Enable automatic detection of traversal depth for multi-dimensional operations */
	public static boolean enableDetectTraversalDepth = true;
	/** Enable position simplification optimizations during index projection */
	public static boolean enablePositionSimplification = true;
	/** Enable unique index optimization for one-to-one mappings */
	public static boolean enableUniqueIndexOptimization = true;

	private TraversalPolicy inputShape;
	private int traversalDepth;

	private TraversalPolicy subsetShape;
	private TraversalPolicy strideShape;

	/**
	 * Creates a new enumeration with automatically computed stride.
	 * The stride is calculated to evenly divide the input collection
	 * along the enumeration dimensions.
	 * 
	 * @param shape the shape of each enumerated subset
	 * @param collection the input collection to enumerate
	 */
	public PackedCollectionEnumerate(TraversalPolicy shape, Producer<?> collection) {
		this(shape, computeStride(shape, collection, enableDetectTraversalDepth ? shape(collection).getTraversalAxis() : 0), collection);
	}

	/**
	 * Creates a new enumeration with explicit stride specification.
	 * 
	 * @param shape the shape of each enumerated subset
	 * @param stride the stride pattern for enumeration
	 * @param collection the input collection to enumerate
	 */
	public PackedCollectionEnumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		this(shape, stride, collection, enableDetectTraversalDepth ? shape(collection).getTraversalAxis() : 0);
	}

	/**
	 * Creates a new enumeration with full parameter control.
	 * 
	 * @param shape the shape of each enumerated subset
	 * @param stride the stride pattern for enumeration  
	 * @param collection the input collection to enumerate
	 * @param traversalDepth the depth at which to perform traversal operations
	 */
	public PackedCollectionEnumerate(TraversalPolicy shape, TraversalPolicy stride,
									 Producer<?> collection, int traversalDepth) {
		super("enumerate", computeShape(shape, stride, collection, traversalDepth), null, collection);
		this.inputShape = shape(collection).traverse(traversalDepth).item();
		this.traversalDepth = traversalDepth;
		this.subsetShape = shape;
		this.strideShape = stride;
	}

	@Override
	protected boolean isOutputRelative() { return false; }

	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		if (super.isIsolationTarget(context)) return true;

		if (enablePreferIsolation &&
				getParallelism() > ParallelismTargetOptimization.minCount &&
				getOutputSize() <= MemoryProvider.MAX_RESERVATION) {
			return true;
		}

		return false;
	}

	/**
	 * Returns true if this enumeration is a one-to-one mapping of the input collection,
	 * where each element in the input collection is mapped to exactly one element in
	 * the output collection, false otherwise.
	 */
	public boolean isOneToOne() {
		for (int i = 0; i < subsetShape.getDimensions(); i++) {
			boolean match;

			if (strideShape.length(i) == 0) {
				match = subsetShape.length(i) == inputShape.length(i);
			} else {
				match = subsetShape.length(i) == strideShape.length(i);
			}

			if (!match) return false;
		}

		return true;
	}

	@Override
	public boolean isZero() {
		return Algebraic.isZero(getInputs().get(1));
	}

	@Override
	protected Expression<?> projectIndex(Expression<?> index) {
		Expression block;
		long blockSize = getShape().sizeLong(traversalDepth);

		if (!isFixedCount() || getShape().getTotalSizeLong() != blockSize) {
			// Determine the current block
			block = index.divide(blockSize);
			index = index.imod(blockSize);
		} else {
			// There can be only one block
			block = e(0);
		}

		// Determine which slice to extract
		// Starting over from the beginning for each new block
		Expression<?> slice;

		if (subsetShape.getTotalSizeLong() == 1) {
			slice = index;
		} else if (!index.isFP()) {
			slice = index.divide(e(subsetShape.getTotalSizeLong()));
		} else {
			throw new IllegalArgumentException();
		}

		// Find the index in that slice
		Expression<?> offset = index.toInt().imod(subsetShape.getTotalSizeLong());

		// Determine the location of the slice
		Expression<?> p[] = new Expression[subsetShape.getDimensions()];

		if (enablePositionSimplification) {
			KernelStructureContext ctx = index.getStructureContext();
			if (ctx == null) ctx = new NoOpKernelStructureContext();

			offset = offset.simplify(ctx);
			slice = slice.simplify(ctx);
		}

		for (int j = 0; j < subsetShape.getDimensions(); j++) {
			if (strideShape.length(j) > 0) {
				p[j] = slice.multiply(e(strideShape.length(j)));
			} else {
				p[j] = e(0);
			}
		}

		Expression blockOffset = inputShape.subset(subsetShape, offset, p);
		return block.multiply(inputShape.getTotalSizeLong()).add(blockOffset);
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (Index.child(globalIndex, localIndex).equals(targetIndex)) {
			Expression<?> idx = uniqueNonZeroOffsetMapped(globalIndex, localIndex);
			if (idx != null) return idx;
		}

		return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	protected Expression<?> uniqueNonZeroOffsetMapped(Index globalOut, Index localOut) {
		if (!enableUniqueIndexOptimization || !isOneToOne()) return null;
		if (localOut.getLimit().isEmpty() || globalOut.getLimit().isEmpty()) return null;
		if (subsetShape.getSizeLong() != localOut.getLimit().getAsLong()) return null;

		long limit = subsetShape.getCountLong();
		DefaultIndex g = new DefaultIndex(getVariablePrefix() + "_g", limit);
		DefaultIndex l = new DefaultIndex(getVariablePrefix() + "_l", inputShape.getTotalSizeLong() / limit);

		Expression<?> idx = getCollectionArgumentVariable(1).uniqueNonZeroOffset(g, l, Index.child(g, l));
		if (idx != null && !idx.isValue(IndexValues.of(g))) return null;

		return idx == null ? null : idx.withIndex(g, (Expression<?>) globalOut).imod(subsetShape.getSizeLong());
	}

	@Override
	public PackedCollectionEnumerate<T> generate(List<Process<?, ?>> children) {
		return (PackedCollectionEnumerate)
				new PackedCollectionEnumerate<>(subsetShape, strideShape,
								(Producer) children.get(1), traversalDepth)
						.addAllDependentLifecycles(getDependentLifecycles());
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Enumerate cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}

	/**
	 * Computes the output shape for the enumeration operation.
	 * This determines the dimensions of the resulting collection based on
	 * the input shape, subset shape, stride, and traversal depth.
	 * 
	 * <p>The computation follows these steps:</p>
	 * <ol>
	 * <li>Extract the item shape after applying traversal depth</li>
	 * <li>Calculate how many complete subsets fit within each dimension</li>
	 * <li>Construct the output shape by inserting the count dimension</li>
	 * </ol>
	 * 
	 * @param shape the shape of each enumerated subset
	 * @param stride the stride pattern for enumeration
	 * @param collection the input collection
	 * @param traversalDepth the depth for traversal operations
	 * @return the computed output {@link TraversalPolicy}
	 * 
	 * @example
	 * <pre>{@code
	 * // Input: shape [6, 4], subset shape [2, 2], stride [1, 1]
	 * // Output: shape [5, 3, 2, 2] (5x3 patches of size 2x2)
	 * 
	 * // Input: shape [8], subset shape [3], stride [2] 
	 * // Output: shape [3, 3] (3 sequences of length 3)
	 * }</pre>
	 */
	public static TraversalPolicy computeShape(TraversalPolicy shape, TraversalPolicy stride,
												Producer<?> collection, int traversalDepth) {
		TraversalPolicy superShape = shape(collection);
		TraversalPolicy itemShape = superShape.traverse(traversalDepth).item();
		if (itemShape.getDimensions() <= 0) {
			throw new IllegalArgumentException("Invalid traversal depth");
		}

		int count = IntStream.range(0, shape.getDimensions()).map(dim -> {
						int pad = stride.length(dim) - shape.length(dim);
						return stride.length(dim) > 0 ? (itemShape.length(dim) + pad) / stride.length(dim) : -1;
					})
					.filter(i -> i > 0).min()
					.orElseThrow(() -> new IllegalArgumentException("Invalid stride"));

		int dims[] = new int[superShape.getDimensions() + 1];

		for (int i = 0; i < dims.length; i++) {
			int axis = i - traversalDepth;

			if (axis < 0) {
				dims[i] = superShape.length(i);
			} else if (axis == 0) {
				dims[i] = count;
			} else {
				dims[i] = shape.length(axis - 1);
			}
		}

		return new TraversalPolicy(dims).traverse(traversalDepth);
	}

	/**
	 * Automatically computes an appropriate stride pattern for enumeration.
	 * This method calculates stride values that evenly divide the input 
	 * dimensions by the corresponding subset dimensions.
	 * 
	 * <p>The stride computation ensures that the enumeration can be performed
	 * without partial subsets at the boundaries, by requiring that each
	 * input dimension is evenly divisible by the corresponding subset dimension.</p>
	 * 
	 * @param shape the shape of each enumerated subset
	 * @param collection the input collection 
	 * @param traversalDepth the depth for traversal operations
	 * @return the computed stride {@link TraversalPolicy}
	 * @throws IllegalArgumentException if dimensions are not evenly divisible
	 * @throws UnsupportedOperationException if enumeration spans multiple axes
	 * 
	 * @example
	 * <pre>{@code
	 * // Input: [12, 8], subset: [3, 2]
	 * // Computed stride: [3, 2] (evenly divides: 12/3=4, 8/2=4)
	 * 
	 * // Input: [10], subset: [2] 
	 * // Computed stride: [2] (evenly divides: 10/2=5)
	 * }</pre>
	 */
	private static TraversalPolicy computeStride(TraversalPolicy shape, Producer<?> collection, int traversalDepth) {
		TraversalPolicy superShape = shape(collection);

		int dims[] = new int[shape.getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			if (i >= traversalDepth) {
				int axis = i - traversalDepth;

				if (superShape.length(i) % shape.length(axis) != 0) {
					throw new IllegalArgumentException("Dimension " + i +
							" of collection is not divisible by the corresponding dimension of the subset shape");
				} else {
					dims[axis] = superShape.length(i) / shape.length(axis);
				}
			}
		}

		int axis = -1;

		for (int i = 0; i < dims.length; i++) {
			if (dims[i] > 1 || (axis < 0 && (i + 1) >= dims.length)) {
				if (axis >= 0) {
					throw new UnsupportedOperationException("Enumeration across more than one axis is not currently supported");
				} else {
					axis = i;
					dims[i] = shape.length(i);
				}
			} else {
				dims[i] = 0;
			}
		}

		return new TraversalPolicy(true, dims);
	}
}
