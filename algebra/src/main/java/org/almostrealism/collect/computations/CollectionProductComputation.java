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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Stream;

/**
 * A computation that performs element-wise multiplication of multiple {@link PackedCollection}s.
 *
 * <p>This class extends {@link TraversableExpressionComputation} to provide efficient element-wise
 * multiplication (Hadamard product) operations. It multiplies corresponding elements from multiple
 * input collections, producing an output collection of the same shape.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For input collections A, B, C, ..., the computation produces:</p>
 * <pre>
 * result[i] = A[i] × B[i] × C[i] × ...
 * </pre>
 * <p>where i ranges over all elements in the collections.</p>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>The product rule is applied for gradient computation. For two operands u and v:</p>
 * <pre>
 * ∂(u × v)/∂target = u × ∂v/∂target + v × ∂u/∂target
 * </pre>
 * <p>Currently, automatic differentiation is supported for exactly two operands with fixed counts.
 * For more than two operands or variable counts, the computation falls back to the parent's delta implementation.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Element-wise multiplication of two vectors:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(5);
 * CollectionProducer<PackedCollection<?>> a = c(1.0, 2.0, 3.0, 4.0, 5.0);
 * CollectionProducer<PackedCollection<?>> b = c(2.0, 2.0, 2.0, 2.0, 2.0);
 *
 * CollectionProductComputation<PackedCollection<?>> product =
 *     new CollectionProductComputation<>(shape, a, b);
 *
 * PackedCollection<?> result = product.get().evaluate();
 * // Result: [2.0, 4.0, 6.0, 8.0, 10.0]
 * }</pre>
 *
 * <p><strong>Multiple operand multiplication:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(3);
 * CollectionProducer<PackedCollection<?>> x = c(2.0, 3.0, 4.0);
 * CollectionProducer<PackedCollection<?>> y = c(3.0, 2.0, 1.0);
 * CollectionProducer<PackedCollection<?>> z = c(0.5, 1.0, 2.0);
 *
 * CollectionProductComputation<PackedCollection<?>> product =
 *     new CollectionProductComputation<>(shape, x, y, z);
 *
 * PackedCollection<?> result = product.get().evaluate();
 * // Result: [3.0, 6.0, 8.0]  (2×3×0.5, 3×2×1, 4×1×2)
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the total number of elements</li>
 *   <li><strong>Memory:</strong> Output collection of size matching the input shape</li>
 *   <li><strong>Parallelization:</strong> Fully parallelizable across all elements</li>
 *   <li><strong>Hardware Acceleration:</strong> Compiles to efficient GPU/CPU kernels</li>
 * </ul>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see TraversableExpressionComputation
 * @see CollectionAddComputation
 * @see org.almostrealism.collect.CollectionFeatures#multiply(io.almostrealism.relation.Producer, io.almostrealism.relation.Producer)
 *
 * @author Michael Murray
 */
public class CollectionProductComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {

	/**
	 * Constructs a new product computation with default name "multiply".
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of input {@link Producer}s to be multiplied together
	 */
	public CollectionProductComputation(TraversalPolicy shape,
										Producer<PackedCollection<?>>... arguments) {
		this("multiply", shape, arguments);
	}

	/**
	 * Constructs a new product computation with a custom name.
	 * This constructor allows subclasses or specialized versions to use different names
	 * for debugging and profiling purposes.
	 *
	 * <p>Note: This constructor uses {@link MultiTermDeltaStrategy#NONE} because
	 * the delta computation is handled by the custom {@link #delta(Producer)} method.</p>
	 *
	 * @param name The name identifier for this computation
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of input {@link Producer}s to be multiplied together
	 */
	protected CollectionProductComputation(String name, TraversalPolicy shape,
										   Producer<PackedCollection<?>>... arguments) {
		super(name, shape, MultiTermDeltaStrategy.NONE, arguments);
	}

	/**
	 * Generates the expression that multiplies all input arguments element-wise.
	 * This method creates a {@link CollectionExpression} that performs element-wise
	 * multiplication of all input traversable expressions (excluding the destination at index 0).
	 *
	 * @param args Array of {@link TraversableExpression}s where args[0] is the destination
	 *             and args[1..n] are the input collections to be multiplied
	 * @return A {@link CollectionExpression} that computes the element-wise product
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return product(getShape(), Stream.of(args).skip(1).toArray(TraversableExpression[]::new));
	}

	/**
	 * Generates a new product computation with the specified child processes.
	 * This method is used for computation graph optimization and parallel processing,
	 * creating a new instance with different input sources while preserving all
	 * configuration settings (postprocessor, description, short circuit, lifecycles).
	 *
	 * @param children List of child {@link Process} instances to use as inputs
	 * @return A new {@link CollectionProductComputation} that performs multiplication
	 *         on the child processes with the same configuration as this instance
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProductComputation<T>) new CollectionProductComputation(getName(), getShape(),
				children.stream().skip(1).toArray(Producer[]::new))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	/**
	 * Computes the derivative (delta) of this product computation with respect to the
	 * specified target using the product rule for automatic differentiation.
	 *
	 * <p>This method implements the product rule of calculus for gradient computation.
	 * For two operands u and v, the derivative is:</p>
	 * <pre>
	 * ∂(u × v)/∂target = u × ∂v/∂target + v × ∂u/∂target
	 * </pre>
	 *
	 * <h3>Limitations</h3>
	 * <p>The current implementation has several constraints:</p>
	 * <ul>
	 *   <li>Only supports exactly two operands (binary multiplication)</li>
	 *   <li>Both operands must have fixed counts (not variable-sized)</li>
	 *   <li>All child processes must be {@link CollectionProducer}s</li>
	 * </ul>
	 * <p>If any of these constraints are violated, the method falls back to the
	 * parent's delta implementation.</p>
	 *
	 * <h3>Implementation Details</h3>
	 * <p>The computation follows these steps:</p>
	 * <ol>
	 *   <li>Validates that the operands meet the requirements (2 operands, fixed counts)</li>
	 *   <li>Computes u.delta(target) and v.delta(target)</li>
	 *   <li>Reshapes the deltas to align dimensions properly</li>
	 *   <li>Applies the product rule: u×vDelta + v×uDelta</li>
	 *   <li>Reshapes the result to match the expected output shape</li>
	 * </ol>
	 *
	 * <p>The result shape is constructed by appending the target shape to the
	 * computation shape, allowing proper gradient accumulation in backpropagation.</p>
	 *
	 * @param target The {@link Producer} with respect to which the derivative should be computed
	 * @return A {@link CollectionProducer} that computes the derivative using the product rule,
	 *         or delegates to the parent implementation if constraints are not met
	 * @see #expandAndMultiply(io.almostrealism.relation.Producer, io.almostrealism.relation.Producer)
	 * @see org.almostrealism.collect.CollectionProducer#reshape(int...)
	 */
	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		TraversalPolicy targetShape = shape(target);

		List<CollectionProducer<PackedCollection<?>>> operands = List.of(
				getChildren().stream().skip(1)
					.filter(p -> p instanceof CollectionProducer)
					.toArray(CollectionProducer[]::new));

		boolean supported = true;

		if (operands.size() != getChildren().size() - 1) {
			supported = false;
		} else if (operands.size() != 2) {
			warn("Product delta not implemented for more than two operands");
			supported = false;
		} else if (operands.stream().anyMatch(o -> !o.isFixedCount())) {
			warn("Product delta not implemented for variable count operands");
			supported = false;
		}

		if (!supported) {
			return super.delta(target);
		}

		TraversalPolicy shape = getShape().append(targetShape);

		CollectionProducer<PackedCollection<?>> u = operands.get(0);
		CollectionProducer<PackedCollection<?>> v = operands.get(1);
		CollectionProducer<PackedCollection<?>> uDelta = u.delta(target);
		CollectionProducer<PackedCollection<?>> vDelta = v.delta(target);

		uDelta = uDelta.reshape(v.getShape().getTotalSize(), -1).traverse(0);
		vDelta = vDelta.reshape(u.getShape().getTotalSize(), -1).traverse(0);
		return (CollectionProducer) expandAndMultiply(u.flatten(), vDelta)
				.add(expandAndMultiply(v.flatten(), uDelta)).reshape(shape);
	}
}
