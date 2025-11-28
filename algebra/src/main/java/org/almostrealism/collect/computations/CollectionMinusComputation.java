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

/**
 * A computation that performs element-wise negation (unary minus) of a collection.
 * This class implements the mathematical unary minus operation, which multiplies each 
 * element by -1, effectively flipping the sign of all values in the collection.
 * 
 * <p>{@link CollectionMinusComputation} extends {@link TransitiveDeltaExpressionComputation} 
 * and provides specialized support for automatic differentiation through delta computation.
 * The computation is transitive, meaning it can efficiently propagate gradients during
 * backpropagation in machine learning and optimization scenarios.</p>
 * 
 * <h2>Mathematical Operation</h2>
 * <p>For a collection C with elements [c1, c2, ..., cn], the minus operation produces
 * a new collection with elements [-c1, -c2, ..., -cn].</p>
 * 
 * <h2>Usage Patterns</h2>
 * <p>This computation is typically used indirectly through higher-level methods such as:</p>
 * <ul>
 *   <li>{@link org.almostrealism.collect.CollectionFeatures#minus(io.almostrealism.relation.Producer)} - Direct negation</li>
 *   <li>{@link org.almostrealism.collect.CollectionFeatures#subtract(io.almostrealism.relation.Producer, io.almostrealism.relation.Producer)} - Element-wise subtraction</li>
 *   <li>Gradient computations in neural networks and optimization algorithms</li>
 *   <li>Mathematical expressions requiring sign inversion</li>
 * </ul>
 * 
 * <h2>Performance Characteristics</h2>
 * <p>The computation is optimized for various scenarios:</p>
 * <ul>
 *   <li><strong>Constants</strong>: Single constant values are handled by {@link org.almostrealism.collect.computations.AtomicConstantComputation}</li>
 *   <li><strong>Identity matrices</strong>: Special optimization for identity matrix negation using {@link org.almostrealism.algebra.computations.ScalarMatrixComputation}</li>
 *   <li><strong>Large collections</strong>: Efficient kernel-based computation for multi-dimensional arrays</li>
 *   <li><strong>Delta computation</strong>: Optimized gradient propagation for automatic differentiation</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <p><strong>Basic negation through CollectionFeatures:</strong></p>
 * <pre>{@code
 * // Negate a vector
 * CollectionProducer<PackedCollection> vector = c(1.0, -2.0, 3.0);
 * CollectionProducer<PackedCollection> negated = minus(vector);
 * // Result: [-1.0, 2.0, -3.0]
 * 
 * // Negate a matrix
 * CollectionProducer<PackedCollection> matrix = c(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
 * CollectionProducer<PackedCollection> negatedMatrix = minus(matrix);
 * // Result: 2x2 matrix [[-1.0, -2.0], [-3.0, -4.0]]
 * }</pre>
 * 
 * <p><strong>Usage in mathematical expressions:</strong></p>
 * <pre>{@code
 * // Subtraction using negation: a - b = a + (-b)
 * CollectionProducer<PackedCollection> a = c(5.0, 8.0, 12.0);
 * CollectionProducer<PackedCollection> b = c(2.0, 3.0, 4.0);
 * CollectionProducer<PackedCollection> difference = add(a, minus(b));
 * // Result: [3.0, 5.0, 8.0]
 * 
 * // Mean centering: data - mean(data)
 * CollectionProducer<PackedCollection> data = c(1.0, 2.0, 3.0, 4.0, 5.0);
 * CollectionProducer<PackedCollection> centered = add(data, minus(mean(data).repeat(5)));
 * // Result: data with zero mean
 * }</pre>
 * 
 * <p><strong>Direct instantiation (advanced usage):</strong></p>
 * <pre>{@code
 * // Create minus computation directly
 * TraversalPolicy shape = new TraversalPolicy(3);
 * Producer<PackedCollection> input = c(2.0, -4.0, 6.0);
 * CollectionMinusComputation<PackedCollection> computation =
 *     new CollectionMinusComputation<>(shape, input);
 * 
 * // Evaluate the computation
 * PackedCollection result = computation.get().evaluate();
 * // Result: [-2.0, 4.0, -6.0]
 * }</pre>
 * 
 * <h2>Integration with Automatic Differentiation</h2>
 * <p>The computation supports automatic differentiation through the delta method:</p>
 * <pre>{@code
 * // Create a computation with gradient tracking
 * CollectionProducer<PackedCollection> x = c(1.0, 2.0, 3.0);
 * CollectionProducer<PackedCollection> y = minus(x);
 * 
 * // Compute gradient: d(-x)/dx = -1
 * CollectionProducer<PackedCollection> gradient = y.delta(x);
 * // Result: constant -1 for each element
 * }</pre>
 * 
 * @see TransitiveDeltaExpressionComputation
 * @see org.almostrealism.collect.CollectionFeatures#minus(io.almostrealism.relation.Producer)
 * @see org.almostrealism.collect.CollectionFeatures#subtract(io.almostrealism.relation.Producer, io.almostrealism.relation.Producer)
 * @see io.almostrealism.expression.Minus
 * @see org.almostrealism.collect.computations.AtomicConstantComputation
 * @see org.almostrealism.algebra.computations.ScalarMatrixComputation
 *
 * @author Michael Murray
 * @since 0.69
 */
public class CollectionMinusComputation extends TransitiveDeltaExpressionComputation {

	/**
	 * Creates a new CollectionMinusComputation with the specified shape and producer arguments.
	 * This is the primary constructor for creating minus computations that operate on
	 * {@link Producer} instances.
	 * 
	 * <p>This constructor automatically assigns the operation name as "minus" and delegates
	 * to the protected constructor for initialization. It's designed for use cases where
	 * you have {@link Producer} instances that need to be negated.</p>
	 * 
	 * @param shape The {@link TraversalPolicy} defining the dimensional structure of the computation.
	 *              This determines how the collection will be traversed and processed during computation.
	 *              Must match the shape of the input collection for element-wise negation.
	 * @param arguments Variable number of {@link Producer} arguments representing the collections to negate.
	 *                  In practice, this should contain exactly one producer representing the input collection.
	 *                  The producer will be evaluated to obtain the collection to negate.
	 * 
	 * @throws IllegalArgumentException if the shape is null or incompatible with the arguments
	 * 
	 * <p><strong>Usage Example:</strong></p>
	 * <pre>{@code
	 * // Create a producer for input data
	 * Producer<PackedCollection> inputProducer = () -> pack(1.0, -2.0, 3.0);
	 * 
	 * // Create minus computation
	 * TraversalPolicy shape = new TraversalPolicy(3);
	 * CollectionMinusComputation<PackedCollection> computation =
	 *     new CollectionMinusComputation<>(shape, inputProducer);
	 * 
	 * // Evaluate to get negated result
	 * PackedCollection result = computation.get().evaluate();
	 * // Result: [-1.0, 2.0, -3.0]
	 * }</pre>
	 * 
	 * @see #CollectionMinusComputation(TraversalPolicy, Producer[])
	 * @see TraversalPolicy
	 * @see Producer
	 */
	public CollectionMinusComputation(TraversalPolicy shape, Producer<PackedCollection>... arguments) {
		this("minus", shape, arguments);
	}

	/**
	 * Protected constructor that allows customization of the operation name.
	 * This constructor provides the most flexibility by allowing subclasses or internal
	 * code to specify a custom name for the minus operation while maintaining all
	 * other functionality.
	 * 
	 * <p>This constructor is primarily used internally and by subclasses that need to
	 * customize the operation name for debugging, profiling, or specialized computation
	 * tracking purposes.</p>
	 * 
	 * @param name A custom name for this minus computation operation. This name is used
	 *             for debugging, logging, and operation identification purposes. Should
	 *             be descriptive and unique within the computational context.
	 * @param shape The {@link TraversalPolicy} defining how the computation will traverse
	 *              and process the collection elements. Must be compatible with the
	 *              input collections provided by the suppliers.
	 * @param arguments Variable number of {@link PackedCollection} {@link Producer}s
	 * 
	 * @throws IllegalArgumentException if name is null, shape is null, or arguments are incompatible
	 * 
	 * <p><strong>Usage Example (Advanced):</strong></p>
	 * <pre>{@code
	 * // Create a custom-named minus computation for debugging
	 * Supplier<Evaluable<PackedCollection>> inputSupplier = () ->
	 *     () -> pack(1.0, 2.0, 3.0);
	 * 
	 * TraversalPolicy shape = new TraversalPolicy(3);
	 * CollectionMinusComputation<PackedCollection> computation =
	 *     new CollectionMinusComputation<>("debug_negate", shape, inputSupplier);
	 * 
	 * // The custom name appears in operation metadata
	 * System.out.println(computation.getMetadata().getName()); // "debug_negate"
	 * }</pre>
	 * 
	 * @see TransitiveDeltaExpressionComputation#TransitiveDeltaExpressionComputation(String, TraversalPolicy, Producer[])
	 */
	protected CollectionMinusComputation(String name, TraversalPolicy shape,
										 Producer<PackedCollection>... arguments) {
		super(name, shape, arguments);
	}

	/**
	 * Creates the {@link CollectionExpression} that implements the actual minus operation.
	 * This method is called during expression compilation to generate the low-level
	 * expression tree that performs element-wise negation.
	 * 
	 * <p>The implementation delegates to {@link io.almostrealism.code.ExpressionFeatures#minus(TraversalPolicy, TraversableExpression)}
	 * which creates a {@link io.almostrealism.collect.UniformCollectionExpression} that applies
	 * the {@link io.almostrealism.expression.Minus} operation to each element.</p>
	 * 
	 * <p><strong>Note:</strong> This method expects exactly one argument (args[1]) representing 
	 * the collection to negate. The args[0] position is reserved for the output collection
	 * in the expression framework.</p>
	 * 
	 * @param args Array of {@link TraversableExpression} representing the computational arguments.
	 *             args[1] should contain the expression for the collection to negate.
	 *             Additional arguments beyond index 1 are ignored.
	 * @return A {@link CollectionExpression} that performs element-wise negation when evaluated
	 * 
	 * @throws ArrayIndexOutOfBoundsException if args does not contain at least 2 elements
	 * 
	 * <p><strong>Expression Tree Structure:</strong></p>
	 * <p>The returned expression creates a computation tree like:</p>
	 * <pre>
	 * UniformCollectionExpression("minus")
	 *   +-- Minus operation
	 *       +-- Input expression (args[1])
	 * </pre>
	 * 
	 * @see io.almostrealism.code.ExpressionFeatures#minus(TraversalPolicy, TraversableExpression)
	 * @see io.almostrealism.expression.Minus
	 * @see io.almostrealism.collect.UniformCollectionExpression
	 * @see TraversableExpression
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return minus(getShape(), args[1]);
	}

	/**
	 * Generates a {@link CollectionProducerParallelProcess} for executing the minus computation.
	 * This method is called during computation compilation to create the actual executable
	 * process that performs the negation operation in parallel.
	 * 
	 * <p>The method validates that exactly two child processes are provided (the output
	 * and the input collections) and then delegates to the {@link #minus(Producer)} method
	 * to create the appropriate parallel process implementation.</p>
	 * 
	 * <p><strong>Implementation Note:</strong> The first child (index 0) represents the output
	 * collection, while the second child (index 1) represents the input collection to negate.
	 * This follows the standard pattern for unary operations in the computation framework.</p>
	 * 
	 * @param children List of child {@link Process} instances representing the computational
	 *                 dependencies. Must contain exactly 2 processes: [output, input].
	 *                 The second process provides the collection to negate.
	 * @return A {@link CollectionProducerParallelProcess} that executes the minus operation
	 *         when invoked, producing the negated collection
	 * 
	 * @throws IllegalArgumentException if children.size() != 2, indicating incorrect number
	 *                                  of child processes for a unary minus operation
	 * 
	 * <p><strong>Parallel Execution:</strong></p>
	 * <p>The returned process supports parallel execution strategies including:</p>
	 * <ul>
	 *   <li>CPU-based multi-threading for large collections</li>
	 *   <li>GPU kernel execution for highly parallel operations</li>
	 *   <li>Optimized SIMD instructions for vector operations</li>
	 *   <li>Memory-efficient streaming for very large datasets</li>
	 * </ul>
	 * 
	 * <p><strong>Usage in Computation Pipeline:</strong></p>
	 * <pre>{@code
	 * // This method is typically called internally during compilation:
	 * CollectionMinusComputation<PackedCollection> computation =
	 *     new CollectionMinusComputation<>(shape, inputProducer);
	 * 
	 * // During compilation, generate() is called to create the executable process
	 * List<Process<?, ?>> childProcesses = Arrays.asList(outputProcess, inputProcess);
	 * CollectionProducerParallelProcess<PackedCollection> executableProcess =
	 *     computation.generate(childProcesses);
	 * 
	 * // The process can then be executed to perform the computation
	 * PackedCollection result = executableProcess.get().evaluate();
	 * }</pre>
	 * 
	 * @see CollectionProducerParallelProcess
	 * @see #minus(Producer)
	 * @see Process
	 * @see io.almostrealism.compute.Process
	 */
	@Override
	public CollectionProducerParallelProcess<PackedCollection> generate(List<Process<?, ?>> children) {
		if (children.size() != 2) {
			throw new IllegalArgumentException();
		}

		return (CollectionProducerParallelProcess) minus((Producer) children.stream().skip(1).findFirst().orElseThrow());
	}
}
