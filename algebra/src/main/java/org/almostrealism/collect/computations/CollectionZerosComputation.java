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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A {@link CollectionZerosComputation} represents a computation that produces a 
 * {@link PackedCollection} where every element is zero (0.0). This is a specialized
 * and highly optimized implementation for creating zero-filled collections, which are
 * fundamental building blocks in linear algebra and numerical computing.
 * 
 * <p>This class extends {@link CollectionConstantComputation} and provides the most
 * efficient implementation for zero-valued collections. It includes extensive optimizations
 * for algebraic operations where zero operands allow for significant simplifications,
 * such as multiplication by zero yielding zero, and addition with zero being a no-op.</p>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>{@code
 * // Create a zero vector
 * TraversalPolicy vectorShape = new TraversalPolicy(5);
 * CollectionZerosComputation<PackedCollection> zeroVector = 
 *     new CollectionZerosComputation<>(vectorShape);
 * PackedCollection result = zeroVector.get().evaluate();
 * // result: [0.0, 0.0, 0.0, 0.0, 0.0]
 * 
 * // Create a zero matrix
 * TraversalPolicy matrixShape = new TraversalPolicy(3, 4);
 * CollectionZerosComputation<PackedCollection> zeroMatrix = 
 *     new CollectionZerosComputation<>(matrixShape);
 * PackedCollection matrix = zeroMatrix.get().evaluate();
 * // result: 3x4 matrix filled with zeros
 * 
 * // Create a 3D tensor of zeros
 * TraversalPolicy tensorShape = new TraversalPolicy(2, 3, 4);
 * CollectionZerosComputation<PackedCollection> zeroTensor = 
 *     new CollectionZerosComputation<>(tensorShape);
 * // result: 2x3x4 tensor with all 24 elements as 0.0
 * 
 * // Using with CollectionFeatures
 * CollectionProducer<PackedCollection> zeros = zeros(shape(10));
 * PackedCollection result = zeros.get().evaluate();
 * // result: 10-element vector of zeros
 * }</pre>
 * 
 * <h3>Optimizations:</h3>
 * <p>This class provides several key optimizations:</p>
 * <ul>
 *   <li><strong>Zero Detection</strong> - {@link #isZero()} always returns true, enabling
 *       algebraic simplifications throughout the computation pipeline</li>
 *   <li><strong>Multiplication Optimization</strong> - Any multiplication involving a zero
 *       collection can be replaced with another zero collection</li>
 *   <li><strong>Addition Optimization</strong> - Adding zero collections is optimized to
 *       return the non-zero operand directly</li>
 *   <li><strong>Memory Efficiency</strong> - No storage of actual zero values; the computation
 *       generates zeros on demand</li>
 *   <li><strong>Shape Operations</strong> - Reshape and traverse operations are highly efficient
 *       as they only modify metadata without affecting the zero content</li>
 *   <li><strong>Delta Operations</strong> - Derivatives of zero functions remain zero,
 *       enabling optimization in calculus operations</li>
 * </ul>
 * 
 * <h3>Mathematical Properties:</h3>
 * <p>Zero collections have special mathematical properties that enable optimizations:</p>
 * <ul>
 *   <li><strong>Additive Identity</strong>: A + 0 = A for any collection A</li>
 *   <li><strong>Multiplicative Zero</strong>: A * 0 = 0 for any collection A</li>
 *   <li><strong>Derivative Property</strong>: d/dx(0) = 0</li>
 *   <li><strong>Sum Property</strong>: sum(zeros) = 0</li>
 * </ul>
 * 
 * <h3>Common Use Cases:</h3>
 * <ul>
 *   <li>Initializing collections to a known state</li>
 *   <li>Creating padding or boundary conditions</li>
 *   <li>Representing empty or null spaces in algorithms</li>
 *   <li>Serving as identity elements in additive operations</li>
 *   <li>Implementing sparse data structures</li>
 *   <li>Creating baseline values for gradient computations</li>
 * </ul>
 *
 * @author Michael Murray
 * @see CollectionConstantComputation
 * @see PackedCollection
 * @see TraversalPolicy
 * @see org.almostrealism.collect.CollectionFeatures#zeros(TraversalPolicy)
 * @see org.almostrealism.collect.CollectionFeatures#constant(TraversalPolicy, double)
 */
public class CollectionZerosComputation extends CollectionConstantComputation {
	/**
	 * Creates a new CollectionZerosComputation that will produce a zero-filled collection
	 * with the specified shape. The computation is automatically named "zeros" to clearly
	 * indicate its purpose in computation graphs and debugging output.
	 * 
	 * @param shape The traversal policy defining the dimensions and structure of the 
	 *              zero-filled collection to be produced
	 * 
	 * @throws IllegalArgumentException if shape is null
	 * 
	 * @see TraversalPolicy
	 * @see CollectionConstantComputation#CollectionConstantComputation(String, TraversalPolicy)
	 */
	public CollectionZerosComputation(TraversalPolicy shape) {
		super("zeros", shape);
	}

	/**
	 * Always returns true since this computation produces collections filled with zeros.
	 * This method enables critical algebraic optimizations throughout the computation
	 * pipeline, such as:
	 * <ul>
	 *   <li>Multiplication by zero -&gt; zero result</li>
	 *   <li>Addition with zero -&gt; identity operation</li>
	 *   <li>Sum of zeros -&gt; zero result</li>
	 * </ul>
	 * 
	 * <p>This optimization is extensively used in {@link org.almostrealism.collect.CollectionFeatures}
	 * to simplify complex mathematical expressions at compile time.</p>
	 * 
	 * @return Always true, indicating this computation produces zero values
	 * 
	 * @see org.almostrealism.collect.CollectionFeatures#multiply(Producer, Producer)
	 * @see org.almostrealism.collect.CollectionFeatures#add(Producer...)
	 * @see org.almostrealism.collect.CollectionFeatures#sum(Producer)
	 */
	@Override
	public boolean isZero() { return true; }

	/**
	 * Creates the underlying expression that represents a constant zero collection
	 * with the shape defined by this computation. This method delegates to the
	 * {@link ExpressionFeatures#constantZero(TraversalPolicy)} method to generate
	 * the most efficient zero representation.
	 * 
	 * <p>The returned expression will be used during kernel compilation to generate
	 * optimized code that directly produces zero values without unnecessary 
	 * computation overhead.</p>
	 * 
	 * @param args Traversable expressions representing input arguments (unused for 
	 *             constant zero computations as they have no dependencies)
	 * @return A ConstantCollectionExpression representing a zero-filled collection
	 *         with this computation's shape
	 * 
	 * @see ExpressionFeatures#constantZero(TraversalPolicy)
	 * @see ConstantCollectionExpression
	 */
	@Override
	protected ConstantCollectionExpression getExpression(TraversableExpression... args) {
		return ExpressionFeatures.getInstance().constantZero(getShape());
	}

	/**
	 * Generates a parallel process for this zero computation. Since zero computations
	 * have no dependencies and represent constant values, this method returns the
	 * computation itself as the parallel process, enabling efficient execution
	 * without additional overhead.
	 * 
	 * @param children The list of child processes (unused for zero computations as 
	 *                 they are leaf nodes in the computation graph)
	 * @return This computation instance, serving as its own parallel process
	 * 
	 * @see CollectionProducerParallelProcess
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return this;
	}

	/**
	 * Isolation is not supported for zero computations.
	 *
	 * <p>In practice, constant computations are so trivial that isolation
	 * would provide no benefit and might actually reduce performance
	 * by adding unnecessary computation steps while wasting memory.</p>
	 *
	 * @return Never returns normally
	 * @throws UnsupportedOperationException Always thrown as isolation is not
	 *                                       applicable to zero computations
	 *
	 * @see Process#isolate()
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends PackedCollection>> isolate() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Creates a new {@link CollectionZerosComputation} with the shape traversed along the
	 * specified axis. The zero property is preserved while the shape is transformed 
	 * according to the traversal policy's traverse operation. This is useful for
	 * operations that need to work with different dimensional views of zero data.
	 * 
	 * <p>For example, traversing a 2D zero matrix along axis 1 might convert it to
	 * a 1D zero vector representing row-wise or column-wise operations.</p>
	 * 
	 * @param axis The axis along which to perform the traversal transformation
	 * @return A new {@link CollectionZerosComputation} with the traversed shape, still
	 *         containing all zero values
	 * 
	 * @see TraversalPolicy#traverse(int)
	 * @see CollectionProducer
	 */
	@Override
	public CollectionProducer traverse(int axis) {
		return new CollectionZerosComputation(getShape().traverse(axis));
	}

	/**
	 * Creates a new CollectionZerosComputation with the specified shape while 
	 * preserving the zero content. This allows reshaping zero collections without 
	 * changing their fundamental property of containing all zero values. The reshape
	 * operation is highly efficient as it only modifies metadata.
	 * 
	 * <p>This is commonly used when zero collections need to be broadcast or resized
	 * to match the dimensions of other collections in mathematical operations.</p>
	 * 
	 * <p>Example reshaping operations:</p>
	 * <pre>{@code
	 * // Reshape a zero vector into a zero matrix
	 * CollectionZerosComputation<PackedCollection> zeroVector = 
	 *     new CollectionZerosComputation<>(new TraversalPolicy(6));
	 * CollectionZerosComputation<PackedCollection> zeroMatrix = 
	 *     zeroVector.reshape(new TraversalPolicy(2, 3));
	 * // Result: 2x3 matrix of zeros instead of 6-element vector of zeros
	 * }</pre>
	 * 
	 * @param shape The new traversal policy defining the desired output shape
	 * @return A new CollectionZerosComputation with the specified shape and zero content
	 * 
	 * @throws IllegalArgumentException if the new shape has a different total size
	 *                                  than the current shape (reshape preserves total elements)
	 * 
	 * @see TraversalPolicy
	 * @see CollectionProducerComputation
	 */
	@Override
	public CollectionProducerComputation reshape(TraversalPolicy shape) {
		return new CollectionZerosComputation(shape);
	}

	/**
	 * Computes the derivative (delta) of this zero computation with respect to the
	 * specified target. Since the derivative of any constant (including zero) is 
	 * zero, this method returns a new zero computation with an expanded shape that
	 * includes the dimensions of the target variable.
	 * 
	 * <p>This is a fundamental property in calculus: d/dx(0) = 0 for any variable x.
	 * The method creates a zero collection whose shape is the combination of this
	 * computation's shape and the target's shape, representing the zero gradient.</p>
	 * 
	 * <p>Example delta computation:</p>
	 * <pre>{@code
	 * // Zero vector: shape [3]  
	 * CollectionZerosComputation<PackedCollection> zeros = 
	 *     new CollectionZerosComputation<>(new TraversalPolicy(3));
	 * 
	 * // Target variable: shape [2]
	 * Producer<?> target = someVariable; // shape [2]
	 *
	 * // Delta result: shape [3, 2] - zero gradient
	 * CollectionProducer<T> gradient = zeros.delta(target);
	 * // Result: 3x2 matrix of zeros representing d(zeros)/d(target) = 0
	 * }</pre>
	 * 
	 * @param target The target variable with respect to which the derivative is computed
	 * @return A new CollectionZerosComputation with shape expanded to include the 
	 *         target's dimensions, representing the zero derivative
	 * 
	 * @see Producer
	 * @see TraversalPolicy#append(TraversalPolicy)
	 * @see org.almostrealism.calculus.DeltaFeatures
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		return new CollectionZerosComputation(getShape().append(shape(target)));
	}
}

