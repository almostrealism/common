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

import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * An {@link EpsilonConstantComputation} represents a computation that produces a 
 * {@link PackedCollection} where every element contains the machine epsilon value.
 * Machine epsilon is the smallest representable increment between two floating-point 
 * numbers, providing a precision-aware constant for numerical computations.
 * 
 * <p>This class extends {@link SingleConstantComputation} but overrides the expression
 * generation to use the actual machine epsilon value instead of a literal constant.
 * While the constructor passes 0.0 to the parent class, the actual computation uses
 * the precision-specific epsilon value obtained from {@link io.almostrealism.expression.Epsilon}.</p>
 * 
 * <h3>Primary Use Case:</h3>
 * <p>EpsilonConstantComputation is primarily used for floating-point comparisons where
 * numerical precision matters. It provides collections filled with epsilon values that
 * can be used as tolerance thresholds in equality operations and other precision-sensitive
 * computations.</p>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>{@code
 * // Create a 3x3 matrix filled with machine epsilon values
 * TraversalPolicy shape = new TraversalPolicy(3, 3);
 * EpsilonConstantComputation<PackedCollection> epsilon = 
 *     new EpsilonConstantComputation<>(shape);
 * 
 * // Use in floating-point equality comparison (typical usage)
 * CollectionProducer<PackedCollection> a = ...; // some computation
 * CollectionProducer<PackedCollection> b = ...; // another computation
 * CollectionProducer<PackedCollection> areEqual = 
 *     equals(a, b, new EpsilonConstantComputation<>(a.getShape()), 
 *            c(1.0).reshape(a.getShape()));
 * 
 * // Create epsilon values for different shapes
 * EpsilonConstantComputation<PackedCollection> vectorEpsilon = 
 *     new EpsilonConstantComputation<>(new TraversalPolicy(10));
 * EpsilonConstantComputation<PackedCollection> matrixEpsilon = 
 *     new EpsilonConstantComputation<>(new TraversalPolicy(5, 5));
 * }</pre>
 * 
 * <h3>Machine Epsilon Behavior:</h3>
 * <ul>
 *   <li><strong>Runtime Evaluation:</strong> Returns 0.0 for compatibility in test environments</li>
 *   <li><strong>Compiled Code:</strong> Uses actual machine epsilon from the target precision</li>
 *   <li><strong>Precision-Aware:</strong> Automatically adapts to the current floating-point precision</li>
 *   <li><strong>Platform-Specific:</strong> Respects the underlying hardware epsilon characteristics</li>
 * </ul>
 * 
 * <h3>Optimizations:</h3>
 * <p>Like its parent class, EpsilonConstantComputation provides:</p>
 * <ul>
 *   <li>Shape transformations via {@link #reshape(TraversalPolicy)} and {@link #traverse(int)}</li>
 *   <li>Efficient parallel processing through {@link #generate(List)}</li>
 *   <li>Integration with the computation pipeline</li>
 * </ul>
 * 
 * @param <T> The type of PackedCollection this computation produces
 * 
 * @author Michael Murray
 * @see SingleConstantComputation
 * @see io.almostrealism.expression.Epsilon
 * @see PackedCollection
 * @see TraversalPolicy
 * @see org.almostrealism.collect.CollectionFeatures#equals(CollectionProducer, CollectionProducer, CollectionProducer, CollectionProducer)
 */
public class EpsilonConstantComputation<T extends PackedCollection<?>> extends SingleConstantComputation<T> {
	/**
	 * Creates a new EpsilonConstantComputation with the specified shape.
	 * The computation will produce a collection where every element contains
	 * the machine epsilon value for the current floating-point precision.
	 * 
	 * <p>Note: While 0.0 is passed to the parent constructor for compatibility,
	 * the actual computation uses machine epsilon via {@link #getExpression(TraversableExpression...)}.</p>
	 * 
	 * @param shape The traversal policy defining the dimensions and structure of the output collection
	 * 
	 * @throws IllegalArgumentException if shape is null
	 * 
	 * @see TraversalPolicy
	 * @see io.almostrealism.expression.Epsilon
	 */
	public EpsilonConstantComputation(TraversalPolicy shape) {
		super("epsilon", shape, 0.0);
	}

	/**
	 * Returns the expression that represents machine epsilon values for this computation.
	 * This method overrides the parent's constant expression to provide the actual
	 * machine epsilon instead of the literal 0.0 value passed to the constructor.
	 * 
	 * <p>The returned expression uses {@link io.almostrealism.expression.Epsilon} which
	 * provides precision-aware epsilon values that adapt to the target platform's
	 * floating-point characteristics.</p>
	 * 
	 * @param args Traversable expressions (not used for epsilon computation)
	 * @return A ConstantCollectionExpression containing machine epsilon values
	 * 
	 * @see io.almostrealism.expression.Epsilon
	 * @see io.almostrealism.collect.ConstantCollectionExpression
	 */
	@Override
	protected ConstantCollectionExpression getExpression(TraversableExpression... args) {
		return new ConstantCollectionExpression(getShape(), epsilon());
	}

	/**
	 * Generates a parallel process for this epsilon computation. Since epsilon computations
	 * have no dependencies and represent constant values, this method returns the computation
	 * itself as the process implementation.
	 * 
	 * @param children The list of child processes (unused for epsilon computations)
	 * @return This computation instance as it serves as its own parallel process
	 * 
	 * @see CollectionProducerParallelProcess
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return this;
	}

	/**
	 * Creates a new EpsilonConstantComputation with the shape transformed along the specified axis.
	 * This operation preserves the epsilon semantics while changing the output collection's
	 * structure according to the traversal policy.
	 * 
	 * <p>The resulting computation will still produce epsilon values, but arranged according
	 * to the traversed shape. This is useful for operations that need epsilon values with
	 * specific dimensional layouts.</p>
	 * 
	 * @param axis The axis along which to traverse the shape
	 * @return A new EpsilonConstantComputation with the traversed shape
	 * 
	 * @see TraversalPolicy#traverse(int)
	 */
	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new EpsilonConstantComputation<>(getShape().traverse(axis));
	}

	/**
	 * Creates a new EpsilonConstantComputation with the specified shape.
	 * This operation preserves the epsilon semantics while changing the output collection's
	 * dimensions and structure. The resulting computation will produce epsilon values
	 * arranged according to the new shape.
	 * 
	 * <p>This is particularly useful when epsilon values are needed for collections
	 * with different dimensional layouts, such as converting between matrix and vector
	 * representations while maintaining epsilon-based precision semantics.</p>
	 * 
	 * @param shape The new traversal policy defining the desired output structure
	 * @return A new EpsilonConstantComputation with the specified shape
	 * 
	 * @throws IllegalArgumentException if shape is null
	 * 
	 * @see TraversalPolicy
	 */
	@Override
	public CollectionProducerComputation<T> reshape(TraversalPolicy shape) {
		return new EpsilonConstantComputation<>(shape);
	}
}
