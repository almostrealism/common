/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * An adapter that converts {@link TraversableExpression} operations into 
 * {@link RepeatedProducerComputation} format, enabling traversable computations
 * to be executed as repeated iterative operations.
 * 
 * <p>This class implements the Adapter pattern to bridge the gap between two
 * different computation paradigms:
 * <ul>
 *   <li><strong>Traversable expressions:</strong> Direct value access at specific indices</li>
 *   <li><strong>Repeated computations:</strong> Iterative processing with initialization, 
 *       conditions, and incremental operations</li>
 * </ul>
 * 
 * <p>The adapter automatically configures the repeated computation with:
 * <ul>
 *   <li><strong>Initialization:</strong> Sets all values to 0.0 at the start</li>
 *   <li><strong>Condition:</strong> Continues iteration while index is less than array length</li>
 *   <li><strong>Expression:</strong> Evaluates the provided {@link TraversableExpression} 
 *       at each index position</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong>
 * 
 * <p>Basic adapter usage for element-wise operations:
 * <pre>{@code
 * // Create input data
 * PackedCollection<?> input = new PackedCollection<>(shape(100)).randFill();
 * 
 * // Create a traversable expression (e.g., multiplication by 2)
 * TraversableExpression expression = input.multiply(c(2.0));
 * 
 * // Convert to repeated computation using adapter
 * RepeatedProducerComputationAdapter<PackedCollection<?>> adapter = 
 *     new RepeatedProducerComputationAdapter<>(
 *         shape(100),           // Output shape matches input
 *         expression,           // The traversable expression to adapt
 *         () -> input.get()     // Input data supplier
 *     );
 * 
 * // Execute the repeated computation
 * PackedCollection<?> result = adapter.get().evaluate();
 * }</pre>
 * 
 * <p>Integration with existing computations via {@code toRepeated()}:
 * <pre>{@code
 * // Start with a collection computation
 * CollectionProducerComputationAdapter<PackedCollection<?>> computation = 
 *     add(v(shape(100), 0), v(shape(100), 1));  // Element-wise addition
 * 
 * // Convert to repeated form for different execution strategy
 * RepeatedProducerComputationAdapter<PackedCollection<?>> repeated = 
 *     computation.toRepeated();
 * 
 * // Both forms produce identical results but use different execution patterns
 * PackedCollection<?> directResult = computation.get().evaluate(a, b);
 * PackedCollection<?> repeatedResult = repeated.get().evaluate(a, b);
 * }</pre>
 * 
 * <p>Advanced usage with custom shapes and multiple inputs:
 * <pre>{@code
 * // Multi-dimensional data processing
 * TraversalPolicy shape = new TraversalPolicy(10, 20);  // 2D: 10x20 matrix
 * 
 * // Complex expression involving multiple traversable inputs
 * TraversableExpression complexExpr = 
 *     input1.add(input2).multiply(input3.pow(c(2.0)));
 * 
 * RepeatedProducerComputationAdapter<PackedCollection<?>> matrixAdapter = 
 *     new RepeatedProducerComputationAdapter<>(
 *         shape,
 *         complexExpr,
 *         () -> input1.get(),
 *         () -> input2.get(), 
 *         () -> input3.get()
 *     );
 * 
 * PackedCollection<?> matrixResult = matrixAdapter.get().evaluate();
 * }</pre>
 * 
 * <p><strong>Performance Characteristics:</strong>
 * <ul>
 *   <li><strong>Memory Usage:</strong> Processes elements sequentially, reducing peak memory requirements</li>
 *   <li><strong>Execution Pattern:</strong> Fixed iteration count based on output array length</li>
 *   <li><strong>Optimization:</strong> Enables kernel-level optimizations through repeated computation framework</li>
 *   <li><strong>Thread Safety:</strong> Safe for concurrent execution on GPU kernels</li>
 * </ul>
 * 
 * <p><strong>When to Use:</strong>
 * <ul>
 *   <li>Converting traversable operations for execution in repeated computation pipelines</li>
 *   <li>Standardizing computation interfaces across different execution strategies</li>
 *   <li>Enabling iterative optimizations on traversable expressions</li>
 *   <li>Integration with frameworks that expect repeated computation patterns</li>
 * </ul>
 * 
 * <p><strong>Implementation Details:</strong>
 * The adapter uses a fixed initialization to zero, which works correctly for most traversable
 * expressions since the expression evaluation replaces these initial values. The condition
 * ensures iteration continues until all elements in the output array are processed.
 * 
 * @param <T> The type of {@link PackedCollection} this adapter operates on
 * 
 * @see RepeatedProducerComputation
 * @see TraversableExpression  
 * @see CollectionProducerComputationAdapter#toRepeated()
 * @see RelativeTraversableProducerComputation#toRepeated()
 * @see TraversalPolicy
 * 
 * @author Michael Murray
 */
public class RepeatedProducerComputationAdapter<T extends PackedCollection<?>> extends RepeatedProducerComputation<T> {

	/**
	 * Creates a new adapter that converts the specified {@link TraversableExpression}
	 * into a {@link RepeatedProducerComputation} format.
	 * 
	 * <p>This constructor establishes the computational pattern where:
	 * <ol>
	 *   <li><strong>Initialization:</strong> All output elements start as 0.0</li>
	 *   <li><strong>Iteration:</strong> The provided expression is evaluated at each index</li>
	 *   <li><strong>Termination:</strong> Processing stops when all array elements are filled</li>
	 * </ol>
	 * 
	 * <p>The adapter automatically configures the underlying {@link RepeatedProducerComputation}
	 * with appropriate functions for initialization, condition checking, and expression evaluation.
	 * 
	 * <p><strong>Example Usage:</strong>
	 * <pre>{@code
	 * // Element-wise multiplication of an array by a constant
	 * PackedCollection<?> data = new PackedCollection<>(shape(1000)).randFill();
	 * TraversableExpression multiplyBy3 = data.multiply(c(3.0));
	 * 
	 * RepeatedProducerComputationAdapter<PackedCollection<?>> adapter = 
	 *     new RepeatedProducerComputationAdapter<>(
	 *         shape(1000),        // Match input data shape
	 *         multiplyBy3,        // Expression to evaluate
	 *         () -> data.get()    // Data source
	 *     );
	 * 
	 * PackedCollection<?> result = adapter.get().evaluate();
	 * // result[i] = data[i] * 3.0 for all i
	 * }</pre>
	 * 
	 * <p><strong>Multi-input Operations:</strong>
	 * <pre>{@code
	 * // Complex expression with multiple data sources
	 * TraversableExpression complexOp = 
	 *     vectorA.add(vectorB).multiply(vectorC);
	 * 
	 * RepeatedProducerComputationAdapter<PackedCollection<?>> multiInputAdapter = 
	 *     new RepeatedProducerComputationAdapter<>(
	 *         shape(vectorA.length()),
	 *         complexOp,
	 *         () -> vectorA.get(),  // First input
	 *         () -> vectorB.get(),  // Second input  
	 *         () -> vectorC.get()   // Third input
	 *     );
	 * }</pre>
	 * 
	 * @param shape The {@link TraversalPolicy} defining the multi-dimensional shape and
	 *              access pattern of the output data. This determines the iteration space
	 *              and how elements are addressed during computation.
	 * @param expression The {@link TraversableExpression} to be evaluated at each iteration.
	 *                   This expression defines the actual computation performed at each
	 *                   index position and can reference the provided arguments.
	 * @param arguments Variable number of {@link Supplier}s providing {@link Evaluable}
	 *                  collections that serve as input data for the expression. These
	 *                  suppliers are called to obtain the actual data collections during
	 *                  computation execution.
	 * 
	 * @see TraversalPolicy
	 * @see TraversableExpression#getValueAt(Expression)
	 * @see RepeatedProducerComputation#RepeatedProducerComputation(String, TraversalPolicy, BiFunction, BiFunction, BiFunction, Supplier[])
	 */
	@SafeVarargs
	public RepeatedProducerComputationAdapter(TraversalPolicy shape,
											  TraversableExpression expression,
											  Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(null, shape,
				(args, index) ->
						new DoubleConstant(0.0),
				null,
				(args, index) ->
						expression.getValueAt(index),
				arguments);

		setCondition((args, index) -> index.lessThan(((ArrayVariable) getOutputVariable()).length()));
	}

	/**
	 * Returns the destination expression for writing computation results at the specified index.
	 * 
	 * <p>This override provides a simplified destination addressing strategy specifically
	 * designed for the adapter pattern. Unlike the parent class implementation that may
	 * use global index offsets, this method directly writes results to the local index
	 * position in the output array variable.
	 * 
	 * <p>The method ensures that each iteration result is written to the correct position
	 * in the output array, maintaining the sequential processing pattern expected by the
	 * adapter design.
	 * 
	 * <p><strong>Implementation Details:</strong>
	 * <ul>
	 *   <li>Uses {@code localIndex} for direct array addressing</li>
	 *   <li>Ignores {@code globalIndex} and {@code offset} parameters as they are not
	 *       needed for the adapter's sequential processing pattern</li>
	 *   <li>Assumes output variable is an {@link ArrayVariable} as established by the
	 *       parent {@link RepeatedProducerComputation} class</li>
	 * </ul>
	 * 
	 * @param globalIndex The global index in the overall computation space (unused in this implementation)
	 * @param localIndex The local index within the current iteration, used as the direct
	 *                   array index for writing results
	 * @param offset The offset within the memory block (unused in this implementation)
	 * @return An {@link Expression} representing the destination location {@code outputArray[localIndex]}
	 * 
	 * @see ArrayVariable#valueAt(Expression)
	 * @see RepeatedProducerComputation#getDestination(Expression, Expression, Expression)
	 */
	@Override
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset) {
		return ((ArrayVariable) getOutputVariable()).valueAt(localIndex);
	}
}
