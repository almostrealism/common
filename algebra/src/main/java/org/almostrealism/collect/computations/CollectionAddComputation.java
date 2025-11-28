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
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A computation that performs element-wise addition of multiple {@link PackedCollection}s.
 *
 * <p>This class extends {@link TransitiveDeltaExpressionComputation} to provide efficient
 * element-wise addition operations with automatic differentiation support. It sums all input
 * collections element-by-element, producing an output collection of the same shape.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For input collections A, B, C, ..., the computation produces:</p>
 * <pre>
 * result[i] = A[i] + B[i] + C[i] + ...
 * </pre>
 * <p>where i ranges over all elements in the collections.</p>
 *
 * <h2>Broadcasting and Shape Compatibility</h2>
 * <p>All input collections must be compatible with the specified output shape. The computation
 * supports broadcasting rules where smaller dimensions can be automatically expanded to match
 * larger ones. The output shape is specified at construction time.</p>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>The gradient of addition with respect to any input is simply 1 (or the identity operation).
 * Because this class extends {@link TransitiveDeltaExpressionComputation}, gradients flow
 * transitively through all input arguments during backpropagation:</p>
 * <pre>
 * d(A + B + C)/dA = 1
 * d(A + B + C)/dB = 1
 * d(A + B + C)/dC = 1
 * </pre>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Adding two vectors:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(100); // 100-element vectors
 * CollectionProducer<PackedCollection> a = c(new PackedCollection(shape));
 * CollectionProducer<PackedCollection> b = c(new PackedCollection(shape));
 *
 * CollectionAddComputation<PackedCollection> add =
 *     new CollectionAddComputation<>(shape, a, b);
 *
 * PackedCollection result = add.get().evaluate();
 * }</pre>
 *
 * <p><strong>Adding multiple collections:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(10, 10); // 10x10 matrices
 * CollectionProducer<PackedCollection>[] matrices = new CollectionProducer[5];
 * // ... initialize matrices ...
 *
 * CollectionAddComputation<PackedCollection> sumAll =
 *     new CollectionAddComputation<>(shape, matrices);
 *
 * PackedCollection sum = sumAll.get().evaluate();
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
 * @see TransitiveDeltaExpressionComputation
 * @see CollectionMinusComputation
 * @see CollectionProductComputation
 * @see org.almostrealism.collect.CollectionFeatures#add(List)
 *
 * @author Michael Murray
 */
public class CollectionAddComputation<T extends PackedCollection> extends TransitiveDeltaExpressionComputation<T> {

	/**
	 * Constructs a new addition computation with default name "add".
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of input {@link Producer}s to be added together
	 */
	public CollectionAddComputation(TraversalPolicy shape, Producer<PackedCollection>... arguments) {
		this("add", shape, arguments);
	}

	/**
	 * Constructs a new addition computation with a custom name.
	 * This constructor allows subclasses or specialized versions to use different names
	 * for debugging and profiling purposes.
	 *
	 * @param name The name identifier for this computation
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of input {@link Producer}s to be added together
	 */
	protected CollectionAddComputation(String name, TraversalPolicy shape,
									   Producer<PackedCollection>... arguments) {
		super(name, shape, arguments);
	}

	/**
	 * Generates the expression that sums all input arguments element-wise.
	 * This method creates a {@link CollectionExpression} that adds together all input
	 * traversable expressions (excluding the destination at index 0).
	 *
	 * <p>The expression is created using the {@link #sum(TraversalPolicy, TraversableExpression[])}
	 * method from the parent interface, which produces an efficient element-wise summation.</p>
	 *
	 * @param args Array of {@link TraversableExpression}s where args[0] is the destination
	 *             and args[1..n] are the input collections to be added
	 * @return A {@link CollectionExpression} that computes the element-wise sum
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return sum(getShape(), Stream.of(args).skip(1).toArray(TraversableExpression[]::new));
	}

	/**
	 * Generates a new addition computation with the specified child processes.
	 * This method is used for computation graph optimization and parallel processing,
	 * creating a new instance with different input sources while maintaining the
	 * same addition semantics.
	 *
	 * <p>The method extracts producer arguments from the child processes (skipping
	 * the destination at index 0) and creates a new addition computation using the
	 * factory method {@link #add(List)}.</p>
	 *
	 * @param children List of child {@link Process} instances to use as inputs
	 * @return A new {@link CollectionProducerParallelProcess} that performs addition
	 *         on the child processes
	 * @see org.almostrealism.collect.CollectionFeatures#add(List)
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		List<Producer<?>> args = children.stream().skip(1)
				.map(p -> (Producer<?>) p).collect(Collectors.toList());
		return (CollectionProducerParallelProcess) add(args);
	}
}
