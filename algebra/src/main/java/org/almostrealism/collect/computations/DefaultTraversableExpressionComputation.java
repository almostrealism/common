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

import io.almostrealism.collect.ConditionalIndexExpression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A concrete implementation of {@link TraversableExpressionComputation} that accepts
 * a function to define the expression logic. This is the most commonly used implementation
 * for creating custom traversable expression computations without needing to extend
 * the abstract base class.
 * 
 * <p>This class allows users to provide a {@link Function} that transforms input
 * {@link TraversableExpression} arguments into a {@link CollectionExpression}, making it
 * easy to create custom mathematical operations on multi-dimensional collections.
 * 
 * <h3>Common Usage Patterns</h3>
 * 
 * <p><strong>Element-wise operations:</strong>
 * <pre>{@code
 * // Create a computation that squares each element
 * DefaultTraversableExpressionComputation<PackedCollection<?>> square = 
 *     new DefaultTraversableExpressionComputation<>(
 *         "square", inputShape,
 *         args -> DefaultCollectionExpression.create(inputShape,
 *             idx -> Product.of(
 *                 args[1].getValueAt(idx),
 *                 args[1].getValueAt(idx))),
 *         inputProducer);
 * }</pre>
 * 
 * <p><strong>Reduction operations:</strong>
 * <pre>{@code
 * // Sum pairs of adjacent elements
 * DefaultTraversableExpressionComputation<PackedCollection<?>> pairSum = 
 *     new DefaultTraversableExpressionComputation<>(
 *         "pairSum", outputShape,
 *         args -> DefaultCollectionExpression.create(outputShape,
 *             idx -> Sum.of(
 *                 args[1].getValueRelative(new IntegerConstant(0)),
 *                 args[1].getValueRelative(new IntegerConstant(1)))),
 *         inputProducer);
 * }</pre>
 * 
 * @param <T> The type of {@link PackedCollection} this computation produces
 * 
 * @see TraversableExpressionComputation
 * @see TraversableExpression
 * @see CollectionExpression
 * 
 * @author Michael Murray
 */
public class DefaultTraversableExpressionComputation<T extends PackedCollection<?>>
		extends TraversableExpressionComputation<T> {

	/**
	 * The function that defines how to transform input traversable expressions
	 * into a collection expression representing the desired computation.
	 */
	private Function<TraversableExpression[], CollectionExpression> expression;

	/**
	 * Constructs a DefaultTraversableExpressionComputation with default delta strategy.
	 * This constructor uses {@link MultiTermDeltaStrategy#NONE} for automatic differentiation.
	 * 
	 * @param name The name of this computation for debugging and identification
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param expression A function that transforms input {@link TraversableExpression} arrays
	 *                   into a {@link CollectionExpression} representing the computation logic
	 * @param args Variable number of {@link Producer} arguments providing input collections
	 */
	/**
	 * Constructs a DefaultTraversableExpressionComputation with default delta strategy.
	 * This constructor uses {@link MultiTermDeltaStrategy#NONE} for automatic differentiation
	 * and accepts {@link Producer} arguments directly.
	 * 
	 * @param name The name of this computation for debugging and identification
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param expression A function that transforms input {@link TraversableExpression} arrays
	 *                   into a {@link CollectionExpression} representing the computation logic
	 * @param args Variable number of {@link Producer} arguments providing input collections
	 */
	@SafeVarargs
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   Function<TraversableExpression[], CollectionExpression> expression,
												   Producer<? extends PackedCollection<?>>... args) {
		this(name, shape, MultiTermDeltaStrategy.NONE, expression, args);
	}

	/**
	 * Constructs a DefaultTraversableExpressionComputation with default delta strategy.
	 * This constructor uses {@link MultiTermDeltaStrategy#NONE} for automatic differentiation.
	 * 
	 * @param name The name of this computation for debugging and identification
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param expression A function that transforms input {@link TraversableExpression} arrays
	 *                   into a {@link CollectionExpression} representing the computation logic
	 * @param args Variable number of {@link Supplier} arguments providing evaluable input collections
	 */
	@SafeVarargs
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   Function<TraversableExpression[], CollectionExpression> expression,
												   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(name, shape, MultiTermDeltaStrategy.NONE, expression, args);
	}

	/**
	 * Constructs a DefaultTraversableExpressionComputation with a specified delta strategy.
	 * This constructor allows full control over automatic differentiation behavior.
	 * 
	 * @param name The name of this computation for debugging and identification
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param deltaStrategy The {@link MultiTermDeltaStrategy} to use for handling derivatives
	 * @param expression A function that transforms input {@link TraversableExpression} arrays
	 *                   into a {@link CollectionExpression} representing the computation logic
	 * @param args Variable number of {@link Supplier} arguments providing evaluable input collections
	 */
	@SafeVarargs
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   MultiTermDeltaStrategy deltaStrategy,
												   Function<TraversableExpression[], CollectionExpression> expression,
												   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(name, shape, deltaStrategy, validateArgs(args));
		this.expression = expression;
	}

	/**
	 * Constructs a DefaultTraversableExpressionComputation with a fixed collection expression.
	 * This constructor is useful when the computation logic doesn't depend on the input arguments
	 * and always produces the same expression pattern.
	 * 
	 * @param name The name of this computation for debugging and identification
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param expression A fixed {@link CollectionExpression} that will be used regardless of inputs
	 */
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   CollectionExpression expression) {
		this(name, shape, MultiTermDeltaStrategy.NONE, expression);
	}

	/**
	 * Constructs a DefaultTraversableExpressionComputation with a fixed collection expression and delta strategy.
	 * This constructor is useful when the computation logic doesn't depend on the input arguments
	 * and always produces the same expression pattern, but requires a specific differentiation strategy.
	 * 
	 * @param name The name of this computation for debugging and identification
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param deltaStrategy The {@link MultiTermDeltaStrategy} to use for handling derivatives
	 * @param expression A fixed {@link CollectionExpression} that will be used regardless of inputs
	 */
	public DefaultTraversableExpressionComputation(String name, TraversalPolicy shape,
												   MultiTermDeltaStrategy deltaStrategy,
												   CollectionExpression expression) {
		super(name, shape, deltaStrategy);
		this.expression = (arguments) -> expression;
	}

	/**
	 * Implements the abstract method from {@link TraversableExpressionComputation}.
	 * Applies the stored expression function to transform the input traversable expressions
	 * into a collection expression.
	 * 
	 * @param args Array of {@link TraversableExpression} objects representing the inputs
	 * @return The {@link CollectionExpression} resulting from applying the expression function
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return expression.apply(args);
	}

	/**
	 * Generates a new instance of this computation with different child processes.
	 * This method is used internally for computation graph transformations and optimizations.
	 * 
	 * @param children List of child {@link Process} objects to use as inputs for the new instance
	 * @return A new {@link DefaultTraversableExpressionComputation} with the specified children
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (DefaultTraversableExpressionComputation<T>) new DefaultTraversableExpressionComputation(getName(), getShape(),
						getDeltaStrategy(), expression,
					children.stream().skip(1).toArray(Supplier[]::new))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	/**
	 * Creates a computation that always returns a fixed collection value.
	 * This is useful for creating constant computations that don't depend on any inputs.
	 * 
	 * @param <T> The type of {@link PackedCollection} to return
	 * @param value The fixed collection value to return
	 * @return A {@link DefaultTraversableExpressionComputation} that always produces the fixed value
	 */
	public static <T extends PackedCollection<?>> DefaultTraversableExpressionComputation<T> fixed(T value) {
		return fixed(value, null);
	}

	/**
	 * Creates a computation that always returns a fixed collection value with custom post-processing.
	 * This is useful for creating constant computations that don't depend on any inputs but require
	 * specific post-processing of the output.
	 * 
	 * @param <T> The type of {@link PackedCollection} to return
	 * @param value The fixed collection value to return
	 * @param postprocessor Optional function for post-processing the output, or null for no post-processing
	 * @return A {@link DefaultTraversableExpressionComputation} that always produces the fixed value
	 */
	public static <T extends PackedCollection<?>> DefaultTraversableExpressionComputation<T> fixed(
			T value, BiFunction<MemoryData, Integer, T> postprocessor) {
		return (DefaultTraversableExpressionComputation<T>)
				new DefaultTraversableExpressionComputation<T>("constant", value.getShape(),
						args -> new ConditionalIndexExpression(value.getShape(), value))
						.setDescription(children -> value.describe())
						.setPostprocessor(postprocessor).setShortCircuit(args -> {
							PackedCollection v = new PackedCollection(value.getShape());
							v.setMem(value.toArray(0, value.getMemLength()));
							return postprocessor == null ? (T) v : postprocessor.apply(v, 0);
						});
	}
}
