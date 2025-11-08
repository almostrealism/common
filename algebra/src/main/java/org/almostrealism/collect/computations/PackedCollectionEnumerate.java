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
 * <h3>Performance Considerations</h3>
 * <p>This class includes several optimization features:</p>
 * <ul>
 * <li><strong>Parallel Processing:</strong> Supports isolation for better parallelization</li>
 * <li><strong>One-to-One Optimization:</strong> Special handling for bijective mappings</li>
 * <li><strong>Expression Simplification:</strong> Reduces computational overhead in index calculations</li>
 * <li><strong>Memory-Aware:</strong> Considers memory limits for isolation decisions</li>
 * </ul>
 * 
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe for read operations once constructed. The static
 * configuration flags ({@link #enablePreferIsolation}, {@link #enableDetectTraversalDepth}, etc.)
 * should be set before creating instances in multi-threaded environments as they
 * are not synchronized.</p>
 * 
 * @param <T> the type of {@link PackedCollection} being enumerated
 *
 * <h2>Examples</h2>
 *
 * <h3>Basic 1D Sliding Window</h3>
 * <pre>{@code
 * // Input: [1, 2, 3, 4, 5, 6] (shape: [6])
 * // enumerate(shape(3), stride(1), input)
 * // Output: [[1,2,3], [2,3,4], [3,4,5], [4,5,6]] (shape: [4, 3])
 * }</pre>
 *
 * <h3>2D Patch Extraction</h3>
 * <pre>{@code
 * // Input: 4x4 matrix (shape: [4, 4])
 * // enumerate(shape(2, 2), stride(1, 1), input)
 * // Output: 3x3x2x2 tensor (9 patches of size 2x2)
 * }</pre>
 *
 * <h3>Strided Enumeration</h3>
 * <pre>{@code
 * // Input: [1, 2, 3, 4, 5, 6, 7, 8] (shape: [8])
 * // enumerate(shape(2), stride(2), input)
 * // Output: [[1,2], [3,4], [5,6], [7,8]] (shape: [4, 2])
 * }</pre>
 *
 * <h3>4D Tensor Convolution Window</h3>
 * <pre>{@code
 * // Input: batch=2, channels=5, height=10, width=6 (shape: [2, 5, 10, 6])
 * // enumerate(axis=3, len=3, stride=1, input.traverse(2))
 * // Output: [2, 5, 4, 10, 3] - 4 windows of size 3 from each row
 * // Common pattern for 1D convolution along width dimension
 * }</pre>
 * 
 * @see org.almostrealism.collect.CollectionFeatures#enumerate(int, int, io.almostrealism.relation.Producer)
 * @see io.almostrealism.collect.TraversalPolicy
 * @see IndexProjectionProducerComputation
 */
public class PackedCollectionEnumerate<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	/**
	 * Enable optimization for preferring isolation in parallel processing.
	 * When enabled, enumeration operations will prefer to run in isolation
	 * when the parallelism count is above the minimum threshold and output
	 * size is within memory limits. This can improve performance for
	 * compute-intensive enumeration operations.
	 * 
	 * @see #isIsolationTarget(ProcessContext)
	 */
	public static boolean enablePreferIsolation = false;
	
	/**
	 * Enable automatic detection of traversal depth for multi-dimensional operations.
	 * When enabled, the enumeration will automatically determine the appropriate
	 * traversal axis based on the input collection's shape. When disabled,
	 * traversal depth defaults to 0.
	 * 
	 * @see TraversalPolicy#getTraversalAxis()
	 */
	public static boolean enableDetectTraversalDepth = true;
	
	/**
	 * Enable position simplification optimizations during index projection.
	 * When enabled, mathematical expressions used in index calculations are
	 * simplified using kernel structure context information. This can reduce
	 * computational overhead at the cost of additional compilation time.
	 * 
	 * @see #projectIndex(Expression)
	 */
	public static boolean enablePositionSimplification = true;
	
	/**
	 * Enable unique index optimization for one-to-one mappings.
	 * When enabled and the enumeration represents a one-to-one mapping,
	 * specialized index calculations are used that can significantly
	 * improve performance for certain enumeration patterns.
	 * 
	 * @see #isOneToOne()
	 * @see #uniqueNonZeroOffsetMapped(Index, Index)
	 */
	public static boolean enableUniqueIndexOptimization = true;

	/** The shape of the input collection after applying traversal transformations */
	private TraversalPolicy inputShape;
	/** The depth at which traversal operations are performed */
	private int traversalDepth;

	/** The shape of each enumerated subset/window */
	private TraversalPolicy subsetShape;
	/** The stride pattern determining spacing between consecutive enumerations */
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
		init();
	}

	/**
	 * Returns false to indicate that the output is not relative to the input indexing.
	 * This enumeration creates absolute indices into the input collection based on
	 * the enumeration pattern, rather than relative offsets.
	 * 
	 * @return always false for enumeration operations
	 */
	@Override
	protected boolean isOutputRelative() { return false; }

	/**
	 * Determines whether this enumeration should be executed in isolation
	 * for better parallel processing performance.
	 * 
	 * <p>Isolation is preferred when:</p>
	 * <ul>
	 * <li>The parent class indicates isolation is beneficial</li>
	 * <li>Prefer isolation is enabled and parallelism count exceeds minimum threshold</li>
	 * <li>Output size fits within memory reservation limits</li>
	 * </ul>
	 * 
	 * @param context the processing context providing parallelism information
	 * @return true if this enumeration should run in isolation
	 * 
	 * @see #enablePreferIsolation
	 * @see ParallelismTargetOptimization#minCount
	 */
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

	/**
	 * Determines if this enumeration results in a zero-valued output.
	 * This occurs when the input collection itself is algebraically zero.
	 * 
	 * @return true if the enumeration output will be all zeros
	 * 
	 * @see Algebraic#isZero(Object)
	 */
	@Override
	public boolean isZero() {
		return Algebraic.isZero(getInputs().get(1));
	}

	/**
	 * Projects an output index to the corresponding input index for enumeration.
	 * This is the core method that implements the enumeration transformation by
	 * mapping each position in the output enumerated collection back to the
	 * appropriate position in the input collection.
	 * 
	 * <p>The projection algorithm works as follows:</p>
	 * <ol>
	 * <li><strong>Block Determination:</strong> Calculate which enumeration block 
	 *     the index belongs to based on traversal depth</li>
	 * <li><strong>Slice Calculation:</strong> Determine which enumerated slice 
	 *     within the block</li>
	 * <li><strong>Offset Calculation:</strong> Find the position within the slice</li>
	 * <li><strong>Stride Application:</strong> Apply stride pattern to determine 
	 *     the starting position</li>
	 * <li><strong>Final Mapping:</strong> Combine block, slice, and offset to get 
	 *     the input index</li>
	 * </ol>
	 * 
	 * @param index the output index to project back to input space
	 * @return an {@link Expression} representing the corresponding input index
	 * 
	 * @see #enablePositionSimplification
	 * @see TraversalPolicy#subset(TraversalPolicy, Expression, Expression[])
	 */
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

	/**
	 * Optimized calculation of unique non-zero offset for specific index patterns.
	 * This method provides an optimization for cases where the enumeration can
	 * determine unique index mappings more efficiently than the general case.
	 * 
	 * <p>The optimization is applied when the global and local indices match
	 * the target index, allowing for a specialized mapping calculation that
	 * can avoid the full index projection computation.</p>
	 * 
	 * @param globalIndex the global index context
	 * @param localIndex the local index within the current context  
	 * @param targetIndex the index being targeted for offset calculation
	 * @return the calculated offset expression, or delegates to parent if optimization not applicable
	 * 
	 * @see #uniqueNonZeroOffsetMapped(Index, Index)
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (Index.child(globalIndex, localIndex).equals(targetIndex)) {
			Expression<?> idx = uniqueNonZeroOffsetMapped(globalIndex, localIndex);
			if (idx != null) return idx;
		}

		return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	/**
	 * Specialized unique offset calculation for one-to-one enumeration mappings.
	 * This optimization is used when the enumeration represents a bijective
	 * (one-to-one) mapping between input and output elements.
	 * 
	 * <p>The optimization applies when:</p>
	 * <ul>
	 * <li>Unique index optimization is enabled</li>  
	 * <li>The enumeration is a one-to-one mapping</li>
	 * <li>Index limits are properly defined</li>
	 * <li>Subset shape size matches the local output limit</li>
	 * </ul>
	 * 
	 * <p>When applicable, this method can significantly reduce computational
	 * overhead by using direct index mapping instead of general projection.</p>
	 * 
	 * @param globalOut the global output index
	 * @param localOut the local output index
	 * @return the optimized offset expression, or null if optimization not applicable
	 * 
	 * @see #enableUniqueIndexOptimization
	 * @see #isOneToOne()
	 */
	protected Expression<?> uniqueNonZeroOffsetMapped(Index globalOut, Index localOut) {
		if (!enableUniqueIndexOptimization || !isOneToOne()) return null;
		if (localOut.getLimit().isEmpty() || globalOut.getLimit().isEmpty()) return null;
		if (subsetShape.getSizeLong() != localOut.getLimit().getAsLong()) return null;

		long limit = subsetShape.getCountLong();
		DefaultIndex g = new DefaultIndex(getNameProvider().getVariablePrefix() + "_g", limit);
		DefaultIndex l = new DefaultIndex(getNameProvider().getVariablePrefix() + "_l", inputShape.getTotalSizeLong() / limit);

		Expression<?> idx = getCollectionArgumentVariable(1).uniqueNonZeroOffset(g, l, Index.child(g, l));
		if (idx != null && !idx.isValue(IndexValues.of(g))) return null;

		return idx == null ? null : idx.withIndex(g, (Expression<?>) globalOut).imod(subsetShape.getSizeLong());
	}

	/**
	 * Generates a new enumeration instance with the same configuration but different inputs.
	 * This method is used by the framework to create optimized versions of the enumeration
	 * with potentially transformed or optimized child processes.
	 * 
	 * @param children the list of child processes to use in the new instance
	 * @return a new {@link PackedCollectionEnumerate} instance with the same configuration
	 *         but using the provided child processes
	 */
	@Override
	public PackedCollectionEnumerate<T> generate(List<Process<?, ?>> children) {
		return (PackedCollectionEnumerate)
				new PackedCollectionEnumerate<>(subsetShape, strideShape,
								(Producer) children.get(1), traversalDepth)
						.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null || strideShape == null || subsetShape == null)
			return signature;

		return signature + "{" + subsetShape.toStringDetail() + "|" +
				strideShape.toStringDetail() + "|" + traversalDepth + "}";
	}

	/**
	 * Extracts the {@link TraversalPolicy} shape from a producer.
	 * This helper method ensures that the producer implements the {@link Shape}
	 * interface and can provide traversal policy information needed for enumeration.
	 * 
	 * @param collection the producer to extract shape from
	 * @return the {@link TraversalPolicy} representing the collection's shape
	 * @throws IllegalArgumentException if the producer doesn't implement {@link Shape}
	 */
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
