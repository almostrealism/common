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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * An adapter that can be used to evaluate {@link TraversableExpression} operations as
 * {@link RepeatedProducerComputation}s, enabling execution as sequential steps in a
 * loop.
 * 
 * <p>This class bridges the gap between two different computation paradigms:
 * <ul>
 *   <li><strong>Traversable expressions:</strong> Direct value access at specific indices</li>
 *   <li><strong>Repeated computations:</strong> Iterative processing with initialization, 
 *       conditions, and an increment operation</li>
 * </ul>
 * 
 * <p>The adapter automatically configures the repeated computation with:
 * <ul>
 *   <li><strong>Initialization:</strong> Sets all values to 0.0 at the start</li>
 *   <li><strong>Condition:</strong> Continues iteration while output index is less than
 *   the {@link #getOutputVariable() output variable} length</li>
 *   <li><strong>Expression:</strong> Evaluates the provided {@link TraversableExpression} 
 *       at each index</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong>
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
 * <p><strong>Performance Characteristics:</strong>
 * <ul>
 *   <li><strong>Memory Usage:</strong> Processes elements sequentially, reducing peak memory requirements</li>
 *   <li><strong>Execution Pattern:</strong> Fixed iteration count based on {@link #getOutputVariable() output variable} length</li>
 * </ul>
 *
 * <p><strong>When to Use:</strong>
 * <ul>
 *   <li>Integration with systems expecting repeated computation interfaces</li>
 *   <li>Memory optimization scenarios where sequential processing is preferred</li>
 *   <li>Optimization opportunities when used with other repeated computations</li>
 *   <li>Circumstances where kernel parallelism needs to be avoided</li>
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
	 * into a {@link RepeatedProducerComputation}.
	 * 
	 * <p>This constructor establishes the computational pattern where:
	 * <ol>
	 *   <li><strong>Initialization:</strong> All output elements start as 0.0</li>
	 *   <li><strong>Iteration:</strong> The provided expression is evaluated at each index</li>
	 *   <li><strong>Termination:</strong> Processing stops when all output positions are filled</li>
	 * </ol>
	 * 
	 * <p>The adapter configures the {@link RepeatedProducerComputation} with appropriate
	 * functions for initialization, condition checking, and expression evaluation.
	 *
	 * @param shape The {@link TraversalPolicy} defining the multi-dimensional shape and
	 *              access pattern of the output data. This determines the iteration space
	 *              and how elements are addressed during computation.
	 * @param expression The {@link TraversableExpression} to be evaluated at each iteration.
	 *                   This expression defines the actual computation performed at each
	 *                   index position and can reference the provided arguments.
	 * @param arguments {@link Evaluable} {@link Supplier}s providing any collection data
	 *                  referenced by the {@code expression}.
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
		ArrayVariable out = (ArrayVariable) getOutputVariable();
		return out.reference(globalIndex.multiply(out.length()).add(localIndex));
	}
}
