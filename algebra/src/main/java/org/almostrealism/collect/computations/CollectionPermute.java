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

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;

/**
 * A {@link CollectionPermute} computation performs dimension reordering (permutation) on
 * multi-dimensional collections. It transforms a collection by rearranging its dimensions
 * according to a specified order, effectively transposing the data layout while preserving
 * all element values.
 * 
 * <p><strong>Dimension Permutation Concept:</strong></p>
 * <p>Given a collection with shape (d₀, d₁, d₂, ..., dₙ) and a permutation order [p₀, p₁, p₂, ..., pₙ],
 * the result will have shape (d_{p₀}, d_{p₁}, d_{p₂}, ..., d_{pₙ}). The element that was at position
 * [i₀, i₁, i₂, ..., iₙ] in the original collection will be accessible at position 
 * [i_{p₀}, i_{p₁}, i_{p₂}, ..., i_{pₙ}] in the permuted collection.</p>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>{@code
 * // Example 1: Simple 2D transpose (swap dimensions)
 * PackedCollection<?> input = new PackedCollection<>(shape(2, 4));  // Shape: (2, 4)
 * PackedCollection<?> result = cp(input).permute(1, 0).evaluate(); // Shape: (4, 2)
 * // Element at input[i][j] is now at result[j][i]
 * 
 * // Example 2: 4D dimension reordering  
 * PackedCollection<?> input = new PackedCollection<>(shape(2, 4, 3, 8));     // Shape: (2, 4, 3, 8)
 * PackedCollection<?> result = cp(input).permute(0, 2, 1, 3).evaluate();    // Shape: (2, 3, 4, 8)
 * // Element at input[i][j][k][l] is now at result[i][k][j][l]
 * 
 * // Example 3: More complex reordering
 * PackedCollection<?> input = new PackedCollection<>(shape(5, 6, 7));        // Shape: (5, 6, 7)
 * PackedCollection<?> result = cp(input).permute(2, 0, 1).evaluate();       // Shape: (7, 5, 6)
 * // Element at input[i][j][k] is now at result[k][i][j]
 * }</pre>
 * 
 * <p><strong>Implementation Details:</strong></p>
 * <p>This class extends {@link IndexProjectionProducerComputation}, which provides the framework
 * for index-based transformations. The core permutation logic is implemented in the 
 * {@link #projectIndex(Expression)} method, which transforms output space indices back to 
 * input space indices using {@link TraversalPolicy} operations.</p>
 * 
 * <p>The permutation operation requires that the input collection implements the {@link Shape}
 * interface to provide access to its {@link TraversalPolicy}, which defines the dimensional
 * structure and indexing scheme.</p>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li>Permutation is a zero-copy operation at the computation level - no data is physically moved</li>
 *   <li>The transformation is applied through index mapping during evaluation</li>
 *   <li>Memory access patterns may change significantly depending on the permutation order</li>
 * </ul>
 * 
 * @param <T> The type of collection being permuted, must extend {@link PackedCollection}
 * 
 * @see IndexProjectionProducerComputation
 * @see TraversalPolicy#permute(int...)
 * @see org.almostrealism.collect.CollectionFeatures#permute(io.almostrealism.relation.Producer, int...)
 * 
 * @author Michael Murray
 */
public class CollectionPermute<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	/** 
	 * The dimension permutation order array. Each element specifies which dimension 
	 * from the input should appear at that position in the output. For example,
	 * order = [1, 0] means dimension 1 becomes dimension 0, and dimension 0 becomes dimension 1.
	 */
	private int order[];

	/**
	 * Creates a new CollectionPermute computation that reorders the dimensions of a collection.
	 * 
	 * <p>The permutation order specifies how input dimensions map to output dimensions.
	 * Each value in the order array indicates which input dimension should be placed 
	 * at that position in the output. The order array must:</p>
	 * <ul>
	 *   <li>Have the same length as the number of dimensions in the input collection</li>
	 *   <li>Contain each dimension index exactly once (be a valid permutation)</li>
	 *   <li>Use zero-based indexing (0 to numDimensions-1)</li>
	 * </ul>
	 * 
	 * <p><strong>Examples:</strong></p>
	 * <pre>{@code
	 * // For a 2D collection with shape (3, 4):
	 * new CollectionPermute(collection, 1, 0)  // Transpose: (3,4) -> (4,3)
	 * 
	 * // For a 3D collection with shape (2, 3, 4):
	 * new CollectionPermute(collection, 2, 0, 1)  // Rotate: (2,3,4) -> (4,2,3)
	 * new CollectionPermute(collection, 0, 2, 1)  // Swap last two: (2,3,4) -> (2,4,3)
	 * }</pre>
	 * 
	 * @param collection The input collection to permute. Must implement {@link Shape} 
	 *                  to provide dimensional information via {@link TraversalPolicy}.
	 * @param order The dimension permutation order. Each element specifies which input
	 *              dimension should appear at that position in the output.
	 * 
	 * @throws IllegalArgumentException if the collection does not implement {@link Shape}
	 * @throws IllegalArgumentException if the order array is invalid (wrong length, 
	 *                                 duplicate indices, or out of range values)
	 * 
	 * @see TraversalPolicy#permute(int...)
	 * @see #computeShape(Producer, int...)
	 */
	public CollectionPermute(Producer<?> collection, int... order) {
		super("permute", computeShape(collection, order), null, collection);
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Permute cannot be performed without a TraversalPolicy");

		this.order = order;
	}

	/**
	 * Returns the memory length required for this computation.
	 * For CollectionPermute, this is always 1 since it performs a direct index mapping
	 * without requiring additional memory allocation.
	 * 
	 * @return Always returns 1, indicating minimal memory requirements for index transformation
	 */
	@Override
	public int getMemLength() { return 1; }

	/**
	 * Returns the total number of elements that will be processed by this computation.
	 * This equals the total size of the output collection, which is the same as the
	 * input collection size since permutation preserves all elements.
	 * 
	 * @return The total count of elements to be processed, obtained from the traversal
	 *         policy of the permuted shape
	 */
	@Override
	public long getCountLong() {
		return getShape().traverseEach().getCountLong();
	}

	/**
	 * Returns the number of statements required for this computation in a kernel context.
	 * For CollectionPermute, this equals the memory length since each element requires
	 * one index transformation statement.
	 * 
	 * @param context The kernel structure context (unused for this computation)
	 * @return The number of kernel statements needed, equal to {@link #getMemLength()}
	 */
	@Override
	protected int getStatementCount(KernelStructureContext context) {
		return getMemLength();
	}

	/**
	 * Projects an output space index back to the corresponding input space index.
	 * This is the core method that implements the dimension permutation logic.
	 * 
	 * <p><strong>Algorithm:</strong></p>
	 * <ol>
	 *   <li>Get the input collection's original traversal policy (shape)</li>
	 *   <li>Create the permuted traversal policy using the specified order</li>
	 *   <li>Convert the output index to multi-dimensional position coordinates</li>
	 *   <li>Map these coordinates back to the input space using the permuted policy</li>
	 * </ol>
	 * 
	 * <p><strong>Example:</strong></p>
	 * <p>For a permutation order [1, 0] on a (2,4) collection:</p>
	 * <ul>
	 *   <li>Output index 5 in the (4,2) result corresponds to position [1, 1]</li>
	 *   <li>This maps back to input position [1, 1] which has linear index 5 in the original (2,4) layout</li>
	 *   <li>But due to the transpose, we need to map [1,1] in output space to [1,1] in input space</li>
	 * </ul>
	 * 
	 * @param index The linear index in the output (permuted) collection space
	 * @return An expression representing the corresponding linear index in the input collection space
	 * 
	 * @see TraversalPolicy#position(Expression)
	 * @see TraversalPolicy#index(Expression[])
	 * @see TraversalPolicy#permute(int...)
	 */
	@Override
	protected Expression projectIndex(Expression index) {
		// Get the original input shape and create the permuted version
		TraversalPolicy inputShape = ((Shape) getInputs().get(1)).getShape();
		TraversalPolicy outputShape = inputShape.permute(order);

		// Convert the output linear index to multi-dimensional coordinates
		Expression actualPosition[] = getShape().position(index);
		
		// Map these coordinates back to input space using the permuted traversal policy
		return outputShape.index(actualPosition);
	}

	/**
	 * Creates a new instance of CollectionPermute with the same permutation order
	 * but using different child processes. This method is used internally by the
	 * computation framework for process composition and optimization.
	 * 
	 * <p>The method expects exactly 2 children: the first is typically a destination
	 * or output process, and the second is the input collection process that provides
	 * the data to be permuted.</p>
	 * 
	 * @param children The list of child processes. Must contain exactly 2 processes,
	 *                where the second process is the input collection producer.
	 * @return A new CollectionPermute instance with the same order but new input
	 * 
	 * @throws UnsupportedOperationException if the number of children is not exactly 2
	 * 
	 * @see IndexProjectionProducerComputation#generate(List)
	 */
	@Override
	public CollectionPermute<T> generate(List<Process<?, ?>> children) {
		if (getChildren().size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new CollectionPermute<>((Producer<?>) children.get(1), order);
	}

	/**
	 * Computes the resulting shape (TraversalPolicy) after applying the specified
	 * dimension permutation to a collection. This static utility method is used
	 * during construction to determine the output shape without creating the full computation.
	 * 
	 * <p>The computation process:</p>
	 * <ol>
	 *   <li>Validates that the collection implements {@link Shape}</li>
	 *   <li>Retrieves the collection's current {@link TraversalPolicy}</li>
	 *   <li>Applies the permutation using {@link TraversalPolicy#permute(int...)}</li>
	 *   <li>Returns the extent shape of the permuted policy</li>
	 * </ol>
	 * 
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * // For a collection with shape (2, 4, 3) and order [2, 0, 1]:
	 * TraversalPolicy result = computeShape(collection, 2, 0, 1);
	 * // Result will have shape (3, 2, 4)
	 * }</pre>
	 * 
	 * @param collection The input collection whose shape will be permuted.
	 *                  Must implement {@link Shape} interface.
	 * @param order The dimension permutation order array. Each element specifies
	 *              which input dimension should appear at that output position.
	 * @return The {@link TraversalPolicy} representing the shape after permutation
	 * 
	 * @throws IllegalArgumentException if the collection does not implement {@link Shape}
	 * 
	 * @see TraversalPolicy#permute(int...)
	 * @see TraversalPolicy#extentShape()
	 */
	protected static TraversalPolicy computeShape(Producer<?> collection, int... order) {
		if (!(collection instanceof Shape)) {
			throw new IllegalArgumentException("Collection must implement Shape to compute permute shape");
		}

		return ((Shape) collection).getShape().permute(order).extentShape();
	}
}
